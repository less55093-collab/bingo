package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GoogleImageRequestTest {
    @Test fun `native Gemini image call uses the selected credential and nested image configuration`() = runBlocking {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            bodies += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"test-image"}}]}}]}""".toResponseBody())
                .build()
        }.build()
        val provider = GoogleProvider(client)
        val result = provider.generateImage(
            ProviderSetting.Google(baseUrl = "https://gateway.test/v1beta", apiKey = "sk-image-group"),
            ImageGenerationParams(model = Model(modelId = "new-gemini-image"), prompt = "draw", size = "1536x1024", numOfImages = 2),
        ).toList()
        assertEquals(2, result.size)
        assertEquals(2, requests.size)
        assertEquals("/v1beta/models/new-gemini-image:generateContent", requests[0].url.encodedPath)
        assertEquals("sk-image-group", requests[0].header("x-goog-api-key"))
        val config = bodies[0]["generationConfig"]!!.jsonObject
        assertEquals("3:2", config["imageConfig"]!!.jsonObject["aspectRatio"]!!.jsonPrimitive.content)
        assertTrue(config["responseModalities"]!!.jsonArray.any { it.jsonPrimitive.content == "IMAGE" })
    }
}
