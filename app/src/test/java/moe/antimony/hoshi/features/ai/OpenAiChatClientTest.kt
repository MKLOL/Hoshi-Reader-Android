package moe.antimony.hoshi.features.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatClientTest {
    @Test
    fun buildRequestBodyAppendsBubbleTextAfterPrompt() {
        val body = OpenAiChatClient.buildRequestBody(
            model = "gpt-5.5",
            prompt = "Translate this:",
            bubbleText = "もうすぐだぞー",
        )
        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("gpt-5.5", root["model"]!!.jsonPrimitive.content)
        val messages = root["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val message = messages[0].jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        assertEquals("Translate this:\n\nもうすぐだぞー", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun buildRequestBodyWithBlankPromptSendsOnlyBubbleText() {
        val body = OpenAiChatClient.buildRequestBody(model = "gpt-5.5", prompt = "  ", bubbleText = "おーっ")
        val content = Json.parseToJsonElement(body)
            .jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("おーっ", content)
    }

    @Test
    fun buildRequestBodyFallsBackToDefaultModelWhenBlank() {
        val body = OpenAiChatClient.buildRequestBody(model = "   ", prompt = "p", bubbleText = "b")
        val model = Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content
        assertEquals(AiChatSettings.DEFAULT_MODEL, model)
    }

    @Test
    fun parseResponseExtractsAssistantContent() {
        val raw = """
            {"id":"chatcmpl-1","choices":[
              {"index":0,"message":{"role":"assistant","content":"  It means \"almost there!\"  "}}
            ]}
        """.trimIndent()
        assertEquals("It means \"almost there!\"", OpenAiChatClient.parseResponse(raw))
    }

    @Test
    fun parseResponseThrowsOnEmptyChoices() {
        val error = assertThrows(OpenAiException::class.java) {
            OpenAiChatClient.parseResponse("""{"choices":[]}""")
        }
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun parseResponseThrowsOnUnparseableBody() {
        assertThrows(OpenAiException::class.java) {
            OpenAiChatClient.parseResponse("not json at all")
        }
    }

    @Test
    fun parseErrorMessageSurfacesOpenAiErrorText() {
        val raw = """{"error":{"message":"The model `gpt-5.5` does not exist.","type":"invalid_request_error"}}"""
        assertEquals(
            "The model `gpt-5.5` does not exist.",
            OpenAiChatClient.parseErrorMessage(404, raw),
        )
    }

    @Test
    fun parseErrorMessageFallsBackToHttpCode() {
        val message = OpenAiChatClient.parseErrorMessage(500, "<html>gateway error</html>")
        assertTrue(message.contains("500"))
    }
}
