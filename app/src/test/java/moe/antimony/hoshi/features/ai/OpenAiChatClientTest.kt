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
    fun buildImageRequestBodySendsCustomPromptAndDataUrlImagePart() {
        val body = OpenAiChatClient.buildImageRequestBody(
            model = "gpt-5.5",
            prompt = "Translate this crop with terse notes.",
            imageBase64 = "abc123",
            imageMimeType = "image/png",
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals("gpt-5.5", root["model"]!!.jsonPrimitive.content)
        val contentParts = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, contentParts.size)
        assertEquals("text", contentParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Translate this crop with terse notes.",
            contentParts[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("image_url", contentParts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,abc123",
            contentParts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun buildImageRequestBodyFallsBackToDefaultModelAndPngMimeType() {
        val body = OpenAiChatClient.buildImageRequestBody(
            model = "  ",
            prompt = "   ",
            imageBase64 = "  xyz  ",
            imageMimeType = "  ",
        )

        val root = Json.parseToJsonElement(body).jsonObject
        assertEquals(AiChatSettings.DEFAULT_MODEL, root["model"]!!.jsonPrimitive.content)
        val contentParts = root["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(
            AiChatSettings.DEFAULT_IMAGE_PROMPT,
            contentParts[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "data:image/png;base64,xyz",
            contentParts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
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
