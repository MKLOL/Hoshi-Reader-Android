package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Reflection guard against the `third_party/hoshidicts-kotlin-bridge` submodule silently
 * drifting back to an older revision than the host app expects.
 *
 * Background: this exact bug has bitten the project twice already. The first time, an
 * upstream merge restaged the stale on-disk submodule checkout (commit 46cc91e) over the
 * incoming upgrade (8a71115) during a `git add -A` conflict resolution — see parent
 * commit ef9f3c7 ("fix: restore hoshidicts submodule to upstream v1.0.1 version"). The
 * second time, the working-tree submodule directory drifted to the same old commit
 * without being recorded anywhere, so `git status` showed `M
 * third_party/hoshidicts-kotlin-bridge` and the build picked up the older
 * [de.manhhao.hoshi.HoshiDicts] declarations.
 *
 * Both times the failure mode was a runtime `JNI DETECTED ERROR IN APPLICATION: JNI
 * NewObjectV called with pending exception java.lang.NoSuchMethodError: no non-static
 * method "Lde/manhhao/hoshi/ImportResult;.<init>(...)"` — a hard SIGABRT crash on the
 * first dictionary import, in a build that compiled cleanly and ran the rest of the app
 * fine.
 *
 * Compile-time alone doesn't catch it because the host's `HoshiDictionaryNativeBridge`
 * compiles against whatever shape the submodule's Kotlin class currently has — if the
 * submodule reverts, that file also reverts to a callable (but wrong-arity) shape, and
 * the JNI signature mismatch only surfaces at runtime. This test asserts the *expected*
 * shape via reflection so it fails at unit-test time instead, before any APK ships.
 *
 * Uses `Class.forName(..., initialize = false)` to avoid triggering [HoshiDicts]' static
 * `System.loadLibrary("hoshidicts_jni")`, which has no native library on the JVM unit-
 * test classpath.
 */
class HoshiDictsNativeApiContractTest {
    @Test
    fun importResultHasSevenArgConstructor() {
        // de.manhhao.hoshi.ImportResult must be a 7-arg data class matching the JNI
        // bridge's `new_import_result` call. The crashing version had a 4-arg constructor
        // (no `title`, no `freqCount`, no `pitchCount`) and the JNI's NewObjectV failed
        // looking up the 7-arg `<init>` descriptor.
        // Kotlin's `?: fail(...)` evaluates to Unit (fail returns void), so unwrap with
        // `error()` afterwards to keep the non-null type — fail throws first, so error
        // is unreachable, but the compiler needs the Nothing.
        val ctors = ImportResultClass.declaredConstructors
        if (ctors.size != 1) {
            fail("ImportResult must have exactly one constructor; found ${ctors.size}")
        }
        val ctor = ctors.single()
        val expected = listOf(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        assertEquals(
            "ImportResult constructor signature must match the JNI bridge's " +
                "new_import_result. If this is failing after a `git pull` or merge, " +
                "verify third_party/hoshidicts-kotlin-bridge is checked out at the " +
                "commit recorded by parent HEAD (see `git submodule status`).",
            expected,
            ctor.parameterTypes.toList(),
        )
    }

    @Test
    fun importDictionaryAcceptsLowRamFlag() {
        // The low-RAM import setting (commit 8743ebc, "feat: add low memory dictionary
        // import setting") added a third Boolean param. The rolled-back submodule only
        // had `(String, String)`, so the host's `bridge.importDictionary(path, dir, low)`
        // resolved to a 3-arg method that didn't exist on the native side, and the JNI
        // lookup landed on the wrong descriptor.
        val method = try {
            HoshiDictsClass.getDeclaredMethod(
                "importDictionary",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            )
        } catch (e: NoSuchMethodException) {
            fail(
                "HoshiDicts.importDictionary(String, String, Boolean) is missing. " +
                    "The submodule has likely reverted past the low_ram-bridge commit. " +
                    "Run `git submodule update` and re-check `git submodule status`. " +
                    "Cause: ${e.message}",
            )
            error("unreachable")
        }
        assertEquals(ImportResultClass, method.returnType)
    }

    @Test
    fun nativeBridgeCppMatchesEveryKotlinResultClassSignature() {
        // The submodule's C++ JNI shim builds eight different host Kotlin classes via
        // `env->GetMethodID(cls, "<init>", "(...)V")` + `NewObject`. Any one of them
        // could SIGABRT on its first use after a submodule drift, in exactly the same
        // way ImportResult did (see ef9f3c7). This test parses the cpp and asserts the
        // signature string for each one matches the JVM descriptor of the host class's
        // primary constructor. Catches a future drift at unit-test time instead of
        // crashing on first lookup / first import / first style render.
        val cppFile = candidateCppPaths.firstOrNull { it.exists() }
            ?: run {
                fail(
                    "Could not find hoshidicts_jni.cpp in the submodule. Tried: " +
                        candidateCppPaths.joinToString { it.absolutePath },
                )
                error("unreachable")
            }
        val cpp = cppFile.readText()
        for ((helperName, kotlinClassName) in JNI_HELPER_TO_KOTLIN_CLASS) {
            val kotlinClass = Class.forName(kotlinClassName, false, javaClass.classLoader)
            val expected = jvmDescriptorOfPrimaryCtor(kotlinClass)
            val helperStart = cpp.indexOf(helperName)
            assertTrue(
                "JNI helper `$helperName` missing from ${cppFile.absolutePath}",
                helperStart >= 0,
            )
            val helperEnd = cpp.indexOf("\n    }\n", helperStart)
                .takeIf { it > helperStart } ?: cpp.length
            val helperBlock = cpp.substring(helperStart, helperEnd)
            assertTrue(
                "C++ JNI helper `$helperName` must build $kotlinClassName with descriptor " +
                    "`$expected` (derived from the Kotlin primary constructor). Found in " +
                    "helper:\n$helperBlock\n" +
                    "If this is failing after a `git pull` or merge, the submodule has " +
                    "likely reverted — re-sync with `git submodule update --init --recursive`.",
                helperBlock.contains("\"$expected\""),
            )
        }
    }

    /**
     * JVM method descriptor for [kotlinClass]'s primary constructor — what
     * `env->GetMethodID(cls, "<init>", ...)` must use on the C++ side. Derived via
     * reflection so the C++ and Kotlin sides stay in lockstep automatically.
     */
    private fun jvmDescriptorOfPrimaryCtor(kotlinClass: Class<*>): String {
        val ctor = kotlinClass.declaredConstructors.single()
        val descriptors = ctor.parameterTypes.joinToString("") { jvmDescriptorFor(it) }
        return "($descriptors)V"
    }

    private fun jvmDescriptorFor(type: Class<*>): String = when (type) {
        Boolean::class.javaPrimitiveType -> "Z"
        Long::class.javaPrimitiveType -> "J"
        Int::class.javaPrimitiveType -> "I"
        // Arrays and reference types use the standard JVM internal name (slashes,
        // not dots) including the leading `[` for arrays, e.g. `[Ljava/lang/String;`.
        else -> if (type.isArray) "[" + jvmDescriptorFor(type.componentType!!) else "L${type.name.replace('.', '/')};"
    }

    /**
     * The eight `new_*` helpers in hoshidicts_jni.cpp that build a host Kotlin object,
     * paired with the host class they instantiate. Keep in sync with the cpp file — a
     * grep for `env->GetMethodID(cls, "<init>"` covers it.
     */
    private val JNI_HELPER_TO_KOTLIN_CLASS: List<Pair<String, String>> = listOf(
        "new_import_result" to "de.manhhao.hoshi.ImportResult",
        "new_transform_group" to "de.manhhao.hoshi.TransformGroup",
        "new_glossary_entry" to "de.manhhao.hoshi.GlossaryEntry",
        "new_frequency" to "de.manhhao.hoshi.Frequency",
        "new_frequency_entry" to "de.manhhao.hoshi.FrequencyEntry",
        "new_pitch_entry" to "de.manhhao.hoshi.PitchEntry",
        "new_term_result" to "de.manhhao.hoshi.TermResult",
        "new_lookup_result" to "de.manhhao.hoshi.LookupResult",
    )

    /**
     * Possible paths to the submodule cpp from the unit-test working directory. Gradle
     * runs tests with the module dir (`app/`) as CWD, but the test may also be invoked
     * directly from the repo root. Try both.
     */
    private val candidateCppPaths: List<File> = listOf(
        File("../third_party/hoshidicts-kotlin-bridge/app/src/main/cpp/hoshidicts_jni.cpp"),
        File("third_party/hoshidicts-kotlin-bridge/app/src/main/cpp/hoshidicts_jni.cpp"),
    )

    private val HoshiDictsClass: Class<*> by lazy {
        // initialize=false so the static `System.loadLibrary("hoshidicts_jni")` doesn't
        // run — the native library isn't on the unit-test classpath, and we only need
        // the declared method shape, not to actually call it.
        Class.forName("de.manhhao.hoshi.HoshiDicts", false, javaClass.classLoader)
    }

    private val ImportResultClass: Class<*> by lazy {
        Class.forName("de.manhhao.hoshi.ImportResult", false, javaClass.classLoader)
    }
}
