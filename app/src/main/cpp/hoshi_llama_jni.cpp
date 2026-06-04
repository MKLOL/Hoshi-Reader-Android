// JNI bridge for offline on-device translation (fork addition).
//
// Wraps the core llama.cpp C API just enough to: load a GGUF model, run a
// single instruction-tuned generation, and report timing so the UI can show a
// tokens/sec debug line. CPU-only — no GPU offload on Android.
//
// The control-flow mirrors llama.cpp's own `examples/simple-chat` (model load →
// chat-template → tokenize → decode/sample loop → detokenize) so it tracks the
// upstream API. Text crosses the JNI boundary as UTF-8 byte arrays (not
// jstring) so Japanese/CJK and 4-byte code points survive intact rather than
// going through JNI's modified-UTF-8.
//
// Threading: callers must serialize calls per model handle (the Kotlin side
// guards with a Mutex). The backend is initialized once, lazily.

#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define HOSHI_LOG_TAG "HoshiLlama"
#define HOSHI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, HOSHI_LOG_TAG, __VA_ARGS__)

namespace {

std::once_flag g_backend_once;

void ensure_backend_initialized() {
    std::call_once(g_backend_once, []() {
        // Forward only llama.cpp's real errors to logcat; its info logging is noisy.
        llama_log_set(
            [](enum ggml_log_level level, const char *text, void * /*user_data*/) {
                if (level >= GGML_LOG_LEVEL_ERROR) {
                    __android_log_print(ANDROID_LOG_ERROR, HOSHI_LOG_TAG, "%s", text);
                }
            },
            nullptr);
        ggml_backend_load_all();
        llama_backend_init();
    });
}

struct HoshiLlamaSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
};

std::string jbytes_to_std_string(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) {
        return {};
    }
    const jsize length = env->GetArrayLength(array);
    std::string result(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

jbyteArray std_string_to_jbytes(JNIEnv *env, const std::string &value) {
    const auto length = static_cast<jsize>(value.size());
    jbyteArray array = env->NewByteArray(length);
    if (array != nullptr && length > 0) {
        env->SetByteArrayRegion(array, 0, length, reinterpret_cast<const jbyte *>(value.data()));
    }
    return array;
}

// Wraps the user's text in the model's own chat template so instruction-tuned
// models (Gemma, Qwen, …) see the format they were trained on. Falls back to
// the raw text when the GGUF carries no template. Sets [applied_template] so the
// caller knows whether the template already emitted the model's BOS/turn markers
// (if so, tokenization must NOT add another special BOS — that would double it).
std::string apply_chat_template(llama_model *model, const std::string &user_content,
                                bool &applied_template) {
    applied_template = false;
    const char *tmpl = llama_model_chat_template(model, /*name=*/nullptr);
    if (tmpl == nullptr) {
        return user_content;
    }
    llama_chat_message message{"user", user_content.c_str()};
    const int needed = llama_chat_apply_template(tmpl, &message, 1, /*add_ass=*/true, nullptr, 0);
    if (needed <= 0) {
        return user_content;
    }
    std::vector<char> buffer(static_cast<size_t>(needed));
    const int written = llama_chat_apply_template(tmpl, &message, 1, /*add_ass=*/true,
                                                  buffer.data(),
                                                  static_cast<int32_t>(buffer.size()));
    if (written <= 0) {
        return user_content;
    }
    applied_template = true;
    return std::string(buffer.data(), static_cast<size_t>(written));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_moe_antimony_hoshi_features_ai_offline_LlamaBridge_nativeLoadModel(
    JNIEnv *env, jobject /*thiz*/, jstring model_path, jint n_threads, jint n_ctx) {
    ensure_backend_initialized();

    const char *path_chars = env->GetStringUTFChars(model_path, nullptr);
    const std::string path = path_chars != nullptr ? path_chars : "";
    if (path_chars != nullptr) {
        env->ReleaseStringUTFChars(model_path, path_chars);
    }
    if (path.empty()) {
        return 0;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU-only on Android.

    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        HOSHI_LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params ctx_params = llama_context_default_params();
    const auto ctx_size = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 2048);
    ctx_params.n_ctx = ctx_size;
    ctx_params.n_batch = ctx_size;
    if (n_threads > 0) {
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads;
    }

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        HOSHI_LOGE("failed to create llama context");
        llama_model_free(model);
        return 0;
    }

    auto *session = new HoshiLlamaSession();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_moe_antimony_hoshi_features_ai_offline_LlamaBridge_nativeFreeModel(
    JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *session = reinterpret_cast<HoshiLlamaSession *>(handle);
    if (session == nullptr) {
        return;
    }
    if (session->ctx != nullptr) {
        llama_free(session->ctx);
    }
    if (session->model != nullptr) {
        llama_model_free(session->model);
    }
    delete session;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_antimony_hoshi_features_ai_offline_LlamaBridge_nativeTranslate(
    JNIEnv *env, jobject /*thiz*/, jlong handle, jbyteArray prompt_utf8, jint max_tokens,
    jdoubleArray metrics_out) {
    auto *session = reinterpret_cast<HoshiLlamaSession *>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        return std_string_to_jbytes(env, "");
    }

    llama_context *ctx = session->ctx;
    const llama_vocab *vocab = session->vocab;

    const std::string user_content = jbytes_to_std_string(env, prompt_utf8);
    bool applied_template = false;
    const std::string prompt = apply_chat_template(session->model, user_content, applied_template);

    // Each translation is independent — wipe the KV cache so the previous bubble
    // never bleeds into this one. Reset perf counters for an accurate tok/s.
    llama_memory_clear(llama_get_memory(ctx), /*data=*/true);
    llama_perf_context_reset(ctx);

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Greedy decoding: translation wants the most faithful token, and it's
    // deterministic and a touch faster than full sampling.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    // The chat template (when present) already inserts the model's BOS/turn tokens, so only
    // add a special BOS for the raw-text fallback. Doubling BOS measurably degrades output.
    const bool add_special = !applied_template;
    const int n_prompt_tokens = -llama_tokenize(vocab, prompt.c_str(),
                                                static_cast<int32_t>(prompt.size()),
                                                nullptr, 0, add_special,
                                                /*parse_special=*/true);
    if (n_prompt_tokens <= 0) {
        // Empty/whitespace prompt (or a tokenizer that produced nothing): nothing to translate.
        llama_sampler_free(sampler);
        return std_string_to_jbytes(env, "");
    }
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt_tokens));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()),
                       add_special, /*parse_special=*/true) < 0) {
        llama_sampler_free(sampler);
        return std_string_to_jbytes(env, "");
    }

    std::string response;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(),
                                            static_cast<int32_t>(prompt_tokens.size()));
    llama_token new_token_id = 0;
    const int token_budget = max_tokens > 0 ? max_tokens : 256;
    int generated = 0;
    while (generated < token_budget) {
        const int n_ctx_used = llama_memory_seq_pos_max(llama_get_memory(ctx), 0) + 1;
        if (n_ctx_used + batch.n_tokens > session->n_ctx) {
            break;  // Out of context window.
        }
        if (llama_decode(ctx, batch) != 0) {
            break;
        }
        new_token_id = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }
        char piece[256];
        int piece_len = llama_token_to_piece(vocab, new_token_id, piece, sizeof(piece),
                                             /*lstrip=*/0, /*special=*/true);
        if (piece_len < 0) {
            // Buffer too small: token_to_piece returns -needed. Grow once and retry.
            std::vector<char> larger(static_cast<size_t>(-piece_len));
            piece_len = llama_token_to_piece(vocab, new_token_id, larger.data(),
                                             static_cast<int32_t>(larger.size()),
                                             /*lstrip=*/0, /*special=*/true);
            if (piece_len < 0) {
                break;
            }
            response.append(larger.data(), static_cast<size_t>(piece_len));
        } else {
            response.append(piece, static_cast<size_t>(piece_len));
        }
        ++generated;
        // Feed the just-sampled token back in. `new_token_id` lives across the
        // loop so taking its address here stays valid for the next decode.
        batch = llama_batch_get_one(&new_token_id, 1);
    }

    // Report timing so the Kotlin side can compute and show tokens/sec.
    if (metrics_out != nullptr && env->GetArrayLength(metrics_out) >= 4) {
        const llama_perf_context_data perf = llama_perf_context(ctx);
        jdouble metrics[4];
        metrics[0] = static_cast<jdouble>(prompt_tokens.size());  // prompt tokens
        metrics[1] = static_cast<jdouble>(generated);             // generated tokens (exact count)
        metrics[2] = perf.t_p_eval_ms;                            // prompt eval ms
        metrics[3] = perf.t_eval_ms;                              // generation ms
        env->SetDoubleArrayRegion(metrics_out, 0, 4, metrics);
    }

    llama_sampler_free(sampler);
    return std_string_to_jbytes(env, response);
}
