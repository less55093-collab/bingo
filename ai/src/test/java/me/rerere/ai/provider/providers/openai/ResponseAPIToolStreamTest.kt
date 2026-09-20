package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelRequestException
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.StreamInterruptedException
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Local protocol fixtures only: no upstream model requests or image-generation charges. */
class ResponseAPIToolStreamTest {
    private lateinit var server: MockWebServer
    private val api = ResponseAPI(OkHttpClient())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.close()

    @Test fun `normal SSE publishes one complete call using call_id for the result`() {
        val fixture = javaClass.getResource("/responses/function-normal.sse")!!.readText()
        val result = collect(fixture)
        val tool = assertSingleImageCall(result)
        assertEquals("call_result_1", tool.toolCallId)
        assertEquals(1, result.chunks.count { chunk -> chunk.choices.any { it.delta?.getTools()?.isNotEmpty() == true } })
        assertEquals(28, result.chunks.last().usage?.totalTokens)

        val executed = tool.copy(output = listOf(UIMessagePart.Text("saved")))
        val input = api.buildMessages(listOf(UIMessage.assistant("").copy(parts = listOf(executed))))
        val output = input.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("call_result_1", output["call_id"]?.jsonPrimitive?.content)
    }

    @Test fun `output item done without added or deltas is recovered`() {
        assertSingleImageCall(collect(sse(itemEvent("done"), completed())))
    }

    @Test fun `completed snapshot alone supplies the tool with an SSE header type`() {
        val terminal = completed(includeOutput = true).toMutableMap().also { it.remove("type") }
        assertSingleImageCall(collect("event: response.completed\ndata: ${JsonObject(terminal)}\n\n"))
    }

    @Test fun `final snapshot repairs a missing middle delta without appending JSON twice`() {
        assertSingleImageCall(collect(sse(
            itemEvent("added", arguments = ""),
            delta("{\"prompt\":\"电"),
            delta("图\"}"),
            completed(includeOutput = true),
        )))
    }

    @Test fun `duplicate sequenced events and repeated final snapshots execute once`() {
        val fixture = javaClass.getResource("/responses/function-normal.sse")!!.readText()
        val repeated = fixture.trim().split("\n\n").joinToString("\n\n", postfix = "\n\n") { "$it\n\n$it" }
        assertSingleImageCall(collect(repeated))
    }

    @Test fun `out of order added and deltas cannot overwrite a done snapshot`() {
        assertSingleImageCall(collect(sse(
            argumentsDone(),
            delta("partial"),
            itemEvent("added", arguments = ""),
            completed(),
        )))
    }

    @Test fun `sequenced out of order deltas are assembled in sequence when snapshot is absent`() {
        assertSingleImageCall(collect(sse(
            itemEvent("added", arguments = ""),
            delta("产品图\"}", 3),
            delta("{\"prompt\":\"电商", 2),
            completed(),
        )))
    }

    @Test fun `done marker validates buffered arguments before exposing any tool`() {
        val result = collect(sse(itemEvent("added", arguments = "{\"prompt\":")) + "data: [DONE]\n\n")
        assertProtocolFailure(result)
        assertTrue(result.failure?.cause?.message.orEmpty().contains("JSON"))
    }

    @Test fun `missing call_id is a model error instead of substituting item id`() {
        val item = functionItem().toMutableMap().also { it.remove("call_id") }
        assertProtocolFailure(collect(sse(completed(outputItem = JsonObject(item)))))
    }

    @Test fun `conflicting identity is rejected before any tool executes`() {
        val changed = functionItem().toMutableMap().also { it["call_id"] = JsonPrimitive("other-call") }
        assertProtocolFailure(collect(sse(itemEvent("added", arguments = ""), completed(outputItem = JsonObject(changed)))))
    }

    @Test fun `an incomplete response with a valid tool never exposes that tool`() {
        val result = collect(sse(itemEvent("done"), completed(status = "incomplete")))
        assertProtocolFailure(result)
    }

    @Test fun `failure after completed arguments never exposes a tool or repeats POST`() {
        val result = collect(sse(itemEvent("done"), buildJsonObject {
            put("type", "response.failed")
            put("response", buildJsonObject { put("error", buildJsonObject { put("message", "model unavailable") }) })
        }))
        assertTrue(result.tools.isEmpty())
        assertEquals("model unavailable", result.failure?.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun `socket closure after a complete item is interruption without tool execution`() {
        val result = collect(sse(itemEvent("done")))
        assertTrue(result.failure is StreamInterruptedException)
        assertTrue(result.tools.isEmpty())
    }

    @Test fun `non object tool arguments are rejected`() {
        assertProtocolFailure(collect(sse(completed(outputItem = functionItem(arguments = "[]")))))
    }

    @Test fun `conflicting repeated event sequence fails instead of silently dropping content`() {
        assertProtocolFailure(collect(sse(
            itemEvent("added", arguments = ""),
            delta("first", 2),
            delta("different", 2),
            completed(includeOutput = true),
        )))
    }

    @Test fun `non streaming output uses the same argument validation`() = runBlocking {
        val response = completed(outputItem = functionItem(arguments = "{bad"))["response"]!!
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(response.toString()).build())
        val failure = runCatching {
            api.generateText(setting(), listOf(UIMessage.user("生成产品图")), params())
        }.exceptionOrNull()
        assertTrue(failure is ResponseProtocolException)
        assertEquals(1, server.requestCount)
    }

    private fun assertSingleImageCall(result: Result): UIMessagePart.Tool {
        assertNull(result.failure)
        val tool = result.tools.single()
        assertEquals("generate_image", tool.toolName)
        assertEquals("电商产品图", tool.inputAsJson().jsonObject["prompt"]?.jsonPrimitive?.content)
        assertFalse(tool.executionStarted)
        assertEquals(1, server.requestCount)
        return tool
    }

    private fun assertProtocolFailure(result: Result) {
        assertTrue(result.tools.isEmpty())
        assertTrue(result.failure is ModelRequestException)
        assertEquals("gpt-5.5", (result.failure as ModelRequestException).modelId)
        assertEquals(1, server.requestCount)
    }

    private fun collect(body: String): Result = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(body).build())
        val chunks = mutableListOf<MessageChunk>()
        val failure = runCatching {
            withTimeout(5_000) {
                api.streamText(setting(), listOf(UIMessage.user("生成产品图")), params()).collect(chunks::add)
            }
        }.exceptionOrNull()
        val messages = chunks.fold(listOf(UIMessage.user("生成产品图"))) { messages, chunk -> messages.handleMessageChunk(chunk) }
        Result(chunks, messages.last().getTools(), failure)
    }

    private data class Result(val chunks: List<MessageChunk>, val tools: List<UIMessagePart.Tool>, val failure: Throwable?)
    private fun setting() = ProviderSetting.OpenAI(baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "test-only")
    private fun params() = TextGenerationParams(model = Model(modelId = "gpt-5.5"))
    private fun sse(vararg events: JsonObject) = events.joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    private fun functionItem(arguments: String = "{\"prompt\":\"电商产品图\"}") = buildJsonObject {
        put("type", "function_call")
        put("id", "fc_item_1")
        put("call_id", "call_result_1")
        put("name", "generate_image")
        put("arguments", arguments)
    }

    private fun itemEvent(phase: String, arguments: String = "{\"prompt\":\"电商产品图\"}") = buildJsonObject {
        put("type", "response.output_item.$phase")
        put("output_index", 0)
        put("item", functionItem(arguments))
    }

    private fun delta(text: String, sequence: Int? = null) = buildJsonObject {
        put("type", "response.function_call_arguments.delta")
        put("item_id", "fc_item_1")
        put("output_index", 0)
        put("delta", text)
        if (sequence != null) put("sequence_number", sequence)
    }

    private fun argumentsDone() = buildJsonObject {
        put("type", "response.function_call_arguments.done")
        put("item_id", "fc_item_1")
        put("output_index", 0)
        put("arguments", "{\"prompt\":\"电商产品图\"}")
    }

    private fun completed(
        includeOutput: Boolean = false,
        status: String = "completed",
        outputItem: JsonObject? = null,
    ) = buildJsonObject {
        put("type", "response.completed")
        put("response", buildJsonObject {
            put("id", "resp_1")
            put("model", "gpt-5.5")
            put("status", status)
            if (includeOutput || outputItem != null) putJsonArray("output") { add(outputItem ?: functionItem()) }
        })
    }
}
