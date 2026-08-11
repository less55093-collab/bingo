package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 钉住 `/images/generations` 的请求体.
 *
 * `response_format: "b64_json"` 是省掉第二次网络往返的关键 —— 上游把图直接内联回来, 就不用再去
 * 图床拉一次. 2026-08-10 打真实网关验证过: generations 认这个参数, edits 不认(仍回 URL),
 * 所以下载兜底那条路不能删.
 */
class OpenAIProviderImageRequestBodyTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun setting() = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "probe",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        chatCompletionsPath = "/chat/completions",
        apiKey = "sk-test",
        enabled = true,
    )

    private fun model(modelId: String) = Model(
        id = Uuid.random(),
        modelId = modelId,
        displayName = modelId,
        type = ModelType.IMAGE,
    )

    /** 发一发请求, 把 provider 真正写出去的 body 解析成 JsonObject 返回. */
    private fun capture(
        modelId: String,
        customBody: List<CustomBody> = emptyList(),
        baseUrlOverride: String? = null,
    ): JsonObject {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"data":[{"b64_json":"AAAA"}]}""")
                .build()
        )
        val base = setting()
        val params = ImageGenerationParams(
            model = model(modelId),
            prompt = "a red circle",
            numOfImages = 1,
            size = "1024x1024",
            customBody = customBody,
        )
        runBlocking {
            provider.generateImage(
                baseUrlOverride?.let { base.copy(baseUrl = it) } ?: base,
                params,
            ).toList()
        }
        val sent = server.takeRequest().body!!.utf8()
        return Json.parseToJsonElement(sent) as JsonObject
    }

    @Test
    fun `gpt-image asks for inline base64`() {
        val body = capture("gpt-image-2")

        assertEquals("b64_json", body["response_format"]?.jsonPrimitive?.content)
        assertEquals("gpt-image-2", body["model"]?.jsonPrimitive?.content)
        assertEquals("1024x1024", body["size"]?.jsonPrimitive?.content)
    }

    @Test
    fun `grok gets neither size nor response_format`() {
        // Grok 连 size 都不认(既有的 isGrok 分支), response_format 同样不能发.
        val body = capture("grok-2-image")

        assertNull(body["response_format"])
        assertNull(body["size"])
    }

    @Test
    fun `grok detected by base url is also skipped`() {
        // isGrok 只做字符串匹配, 所以把 x.ai 放在 path 里就能触发, 不用真去连 x.ai.
        val body = capture(
            modelId = "some-image-model",
            baseUrlOverride = server.url("/x.ai/v1").toString().trimEnd('/'),
        )

        assertNull(body["response_format"])
        assertNull(body["size"])
    }

    @Test
    fun `non gpt-image models are left alone`() {
        // 其它后端要么忽略这个参数要么报错, 统一不发, 由 parseImageResponse 的下载兜底覆盖.
        val body = capture("dall-e-3")

        assertNull(body["response_format"])
        assertEquals("1024x1024", body["size"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gpt-image response_format cannot be overridden by custom body`() {
        // URL 模式实测不稳定, 旧高级设置不能把 gpt-image-* 静默切回二跳下载.
        val body = capture(
            modelId = "gpt-image-2",
            customBody = listOf(
                CustomBody(key = "response_format", value = Json.parseToJsonElement("\"url\"")),
            ),
        )

        assertEquals("b64_json", body["response_format"]?.jsonPrimitive?.content)
    }

    @Test
    fun `image post is not transparently replayed after disconnect`() {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.CloseSocket())
                .build()
        )
        // This response would make a transparent retry look successful.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"data":[{"b64_json":"AAAA"}]}""")
                .build()
        )
        val result = runCatching {
            runBlocking {
                provider.generateImage(
                    setting(),
                    ImageGenerationParams(
                        model = model("gpt-image-2"),
                        prompt = "a red circle",
                        numOfImages = 1,
                        size = "1024x1024",
                    ),
                ).toList()
            }
        }

        assertTrue(result.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `image posts never reuse pooled connections`() {
        repeat(2) {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("""{"data":[{"b64_json":"AAAA"}]}""")
                    .build()
            )
        }
        val params = ImageGenerationParams(
            model = model("gpt-image-2"),
            prompt = "a red circle",
            numOfImages = 1,
            size = "1024x1024",
        )

        runBlocking {
            repeat(2) {
                provider.generateImage(setting(), params).toList()
            }
        }

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNotEquals(first.connectionIndex, second.connectionIndex)
    }

    @Test
    fun `partialImages is gone from the wire`() {
        // 删掉的死字段不该以任何形式回到请求体里.
        val body = capture("gpt-image-2")

        assertNull(body["partial_images"])
        assertNull(body["partialImages"])
        assertFalse(body.containsKey("stream"))
    }
}
