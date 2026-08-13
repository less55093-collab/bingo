package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.StreamInterruptedException
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaudeProviderStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = ClaudeProvider(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `DONE closes flow while server keeps connection open`() {
        enqueueStalledStream("data: [DONE]\n\n")

        collectStream()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `json message_stop closes flow while server keeps connection open`() {
        enqueueStalledStream("data: {\"type\":\"message_stop\"}\n\n")

        collectStream()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `connection close after a partial event is interrupted`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}\n\n")
                .build()
        )

        val failure = runCatching { collectStream() }.exceptionOrNull()

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed event is interrupted without retrying the post`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {not-json}\n\n")
                .build()
        )

        val failure = runCatching { collectStream() }.exceptionOrNull()

        assertTrue(failure is StreamInterruptedException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `upstream error event reaches collector without retrying the post`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body("data: {\"type\":\"error\",\"error\":{\"message\":\"upstream unavailable\"}}\n\n")
                .build()
        )

        val failure = runCatching { collectStream() }.exceptionOrNull()

        assertEquals("upstream unavailable", failure?.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `claude streaming post does not follow a temporary redirect`() {
        server.enqueue(
            MockResponse.Builder()
                .code(307)
                .addHeader("Location", server.url("/redirected").toString())
                .build()
        )
        provider = ClaudeProvider(
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        )

        val failure = runCatching { collectStream() }.exceptionOrNull()

        assertTrue(failure is StreamInterruptedException)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(1, server.requestCount)
    }

    private fun enqueueStalledStream(body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .onResponseEnd(SocketEffect.Stall)
                .build()
        )
    }

    private fun collectStream() = runBlocking {
        withTimeout(2_000) {
            provider.streamText(
                providerSetting = ProviderSetting.Claude(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "sk-test",
                ),
                messages = listOf(UIMessage.user("hello")),
                params = TextGenerationParams(model = Model(modelId = "claude-test")),
            ).toList()
        }
    }
}
