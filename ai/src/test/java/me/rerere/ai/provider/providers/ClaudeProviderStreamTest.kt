package me.rerere.ai.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.StreamInterruptedException
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.handleMessageChunk
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

    @Test
    fun `streamed chinese search query remains one complete tool call`() {
        enqueueStalledStream(
            listOf(
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"search_web\",\"input\":{}}}",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"山\"}}",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"东大学 分数线\\\"}\"}}",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}",
            ).joinToString("\n\n", postfix = "\n\n")
        )

        val chunks = collectStream()
        val messages = chunks.fold(listOf(UIMessage.user("请查询山东大学分数线"))) { messages, chunk ->
            messages.handleMessageChunk(chunk)
        }
        val tool = messages.last().getTools().single()

        assertEquals("call-1", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals(
            "山东大学 分数线",
            tool.inputAsJson().jsonObject["query"]?.jsonPrimitive?.content,
        )
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
