package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.StreamInterruptedException
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderStreamInterruptionTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `chat completions requires terminal event before closing normally`() {
        enqueuePartial("data: {\"id\":\"chatcmpl-1\",\"model\":\"test\",\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n")
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = ProviderSetting.OpenAI(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "sk-test",
                ),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `chat completions done marker closes normally`() {
        enqueuePartial("data: [DONE]\n\n")
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure == null)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `chat completions finish reason is a compatible terminal event`() {
        enqueuePartial("data: {\"id\":\"chatcmpl-1\",\"model\":\"test\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure == null)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed chat completion event is interrupted`() {
        enqueuePartial("data: {not-json}\n\n")
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `response failure event reaches the collector`() {
        enqueuePartial(
            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"upstream unavailable\"}}}\n\n"
        )
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertEquals("upstream unavailable", failure?.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `responses requires terminal event before closing normally`() {
        enqueuePartial("data: {\"type\":\"response.output_text.delta\",\"item_id\":\"item-1\",\"delta\":\"partial\"}\n\n")
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = ProviderSetting.OpenAI(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "sk-test",
                ),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `responses completed event closes normally`() {
        enqueuePartial("data: {\"type\":\"response.completed\",\"response\":{}}\n\n")
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure == null)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `responses event header completes stream when body omits type`() {
        enqueuePartial("event: response.completed\ndata: {\"response\":{}}\n\n")
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure == null)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed responses event is interrupted without retrying the post`() {
        enqueuePartial("data: {not-json}\n\n")
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())

        val failure = collectFailure {
            api.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `openai streaming post does not follow a temporary redirect`() {
        server.enqueue(
            MockResponse.Builder()
                .code(307)
                .addHeader("Location", server.url("/redirected").toString())
                .build()
        )
        val provider = OpenAIProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        )

        val failure = collectFailure {
            provider.streamText(
                providerSetting = openAISetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `google requires finish reason before closing normally`() {
        enqueuePartial("data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"partial\"}]}}]}\n\n")
        val provider = GoogleProvider(OkHttpClient())

        val failure = collectFailure {
            provider.streamText(
                providerSetting = ProviderSetting.Google(
                    baseUrl = server.url("/v1beta").toString().trimEnd('/'),
                    apiKey = "test-key",
                ),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `google finish reason closes normally`() {
        enqueuePartial("data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"done\"}]}}]}\n\n")
        val provider = GoogleProvider(OkHttpClient())

        val failure = collectFailure {
            provider.streamText(
                providerSetting = googleSetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            )
        }

        assertTrue(failure == null)
    }

    @Test
    fun `google compatible done marker closes normally`() {
        enqueuePartial("data: [DONE]\n\n")
        val provider = GoogleProvider(OkHttpClient())

        val failure = collectFailure {
            provider.streamText(
                providerSetting = googleSetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            )
        }

        assertTrue(failure == null)
    }

    @Test
    fun `google error event reaches the collector`() {
        enqueuePartial("data: {\"error\":{\"message\":\"upstream unavailable\"}}\n\n")
        val provider = GoogleProvider(OkHttpClient())

        val failure = collectFailure {
            provider.streamText(
                providerSetting = googleSetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            )
        }

        assertEquals("upstream unavailable", failure?.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed google event is interrupted without retrying the post`() {
        enqueuePartial("data: {not-json}\n\n")
        val provider = GoogleProvider(OkHttpClient())

        val failure = collectFailure {
            provider.streamText(
                providerSetting = googleSetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `google streaming post does not follow a permanent redirect`() {
        server.enqueue(
            MockResponse.Builder()
                .code(308)
                .addHeader("Location", server.url("/redirected").toString())
                .build()
        )
        val provider = GoogleProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        )

        val failure = collectFailure {
            provider.streamText(
                providerSetting = googleSetting(),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            )
        }

        assertTrue(failure is StreamInterruptedException)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `caller cancellation is not reported as an interrupted stream`() {
        runBlocking {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(
                        "data: {\"id\":\"chatcmpl-1\",\"model\":\"test\",\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
                    )
                    .onResponseEnd(SocketEffect.Stall)
                    .build()
            )
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

            withTimeout(2_000) {
                api.streamText(
                    providerSetting = openAISetting(),
                    messages = listOf(UIMessage.user("hello")),
                    params = TextGenerationParams(model = Model(modelId = "test")),
                ).first()
            }

            assertEquals(1, server.requestCount)
        }
    }

    private fun enqueuePartial(body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build()
        )
    }

    private fun openAISetting() = ProviderSetting.OpenAI(
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = "sk-test",
    )

    private fun googleSetting() = ProviderSetting.Google(
        baseUrl = server.url("/v1beta").toString().trimEnd('/'),
        apiKey = "test-key",
    )

    private fun collectFailure(stream: suspend () -> kotlinx.coroutines.flow.Flow<*>) = runBlocking {
        withTimeout(2_000) {
            runCatching { stream().toList() }.exceptionOrNull()
        }
    }
}
