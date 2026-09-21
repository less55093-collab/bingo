package me.rerere.ai.provider

import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamResumeTest {

    @Test
    fun `replayed prefix of a resumed attempt is not emitted twice`() = runBlocking {
        val first = "第一段已经写好的内容到此结束"
        val attempts = listOf(
            // The resumed model restates the tail it already produced before continuing.
            partial(first),
            completed(first + "，接着把剩下的写完。"),
        )

        val chunks = resumableStream(
            messages = listOf(UIMessage.user("hi")),
            networkAvailable = { true },
            collectOnce = { _, attempt -> attempts[attempt - 1] },
        ).toList()

        assertEquals("$first，接着把剩下的写完。", chunks.text())
    }

    @Test
    fun `a resumed request carries the received text back as an assistant prefill`() = runBlocking {
        val seen = mutableListOf<List<UIMessage>>()
        val first = "已经收到的第一段内容"
        val attempts = listOf(partial(first), completed(first + "第二段内容"))

        resumableStream(
            messages = listOf(UIMessage.user("hi")),
            networkAvailable = { true },
            collectOnce = { messages, attempt ->
                seen += messages
                attempts[attempt - 1]
            },
        ).toList()

        assertEquals(1, seen[0].size)
        assertEquals(2, seen[1].size)
        val prefill = seen[1].last()
        assertEquals(MessageRole.ASSISTANT, prefill.role)
        assertEquals(first, prefill.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `attempts stop at the configured budget`() = runBlocking {
        var calls = 0
        val failure = runCatching {
            resumableStream(
                messages = listOf(UIMessage.user("hi")),
                networkAvailable = { true },
                maxAttempts = 2,
                collectOnce = { _, _ ->
                    calls++
                    flow { throw StreamInterruptedException("socket reset", EOFException()) }
                },
            ).toList()
        }.exceptionOrNull()

        assertEquals(2, calls)
        assertTrue(failure is StreamInterruptedException)
    }

    @Test
    fun `a provider error response is never replayed`() = runBlocking {
        var calls = 0
        val failure = runCatching {
            resumableStream(
                messages = listOf(UIMessage.user("hi")),
                networkAvailable = { true },
                collectOnce = { _, _ ->
                    calls++
                    flow { throw StreamInterruptedException("malformed chunk", IllegalStateException("bad json")) }
                },
            ).toList()
        }.exceptionOrNull()

        assertEquals(1, calls)
        assertTrue(failure is StreamInterruptedException)
    }

    @Test
    fun `a completed tool call blocks resume`() = runBlocking {
        var calls = 0
        val failure = runCatching {
            resumableStream(
                messages = listOf(UIMessage.user("hi")),
                networkAvailable = { true },
                collectOnce = { _, _ ->
                    calls++
                    flow {
                        emit(
                            chunk(
                                UIMessagePart.Tool(
                                    toolCallId = "call-1",
                                    toolName = "search",
                                    input = "{\"q\":\"七",
                                    output = emptyList(),
                                )
                            )
                        )
                        throw StreamInterruptedException("socket reset", EOFException())
                    }
                },
            ).toList()
        }.exceptionOrNull()

        assertEquals(1, calls)
        assertTrue(failure is StreamInterruptedException)
    }

    @Test
    fun `reasoning is streamed through without a prefill`() = runBlocking {
        val attempts = listOf(
            flow<MessageChunk> {
                emit(chunk(UIMessagePart.Reasoning(reasoning = "思考中")))
                throw StreamInterruptedException("socket reset", EOFException())
            },
            flow<MessageChunk> { emit(chunk(UIMessagePart.Text("答案"))) },
        )

        val chunks = resumableStream(
            messages = listOf(UIMessage.user("hi")),
            networkAvailable = { true },
            collectOnce = { _, attempt -> attempts[attempt - 1] },
        ).toList()

        assertEquals("答案", chunks.text())
        assertEquals("思考中", chunks.reasoning())
    }

    @Test
    fun `idle timeout is a replayable transport failure`() = runBlocking {
        var calls = 0
        val chunks = resumableStream(
            messages = listOf(UIMessage.user("hi")),
            networkAvailable = { true },
            idleTimeoutMillis = 50,
            maxAttempts = 3,
            collectOnce = { _, attempt ->
                calls++
                when (attempt) {
                    // Never completes: the watchdog must treat silence as a broken connection.
                    1 -> flow {
                        emit(chunk(UIMessagePart.Text("已经写出的半句")))
                        kotlinx.coroutines.delay(10_000)
                    }

                    else -> completed("已经写出的半句完成")
                }
            },
        ).toList()

        assertEquals(2, calls)
        assertEquals("已经写出的半句完成", chunks.text())
    }

    private fun partial(vararg texts: String): Flow<MessageChunk> = flow {
        texts.forEach { emit(chunk(UIMessagePart.Text(it))) }
        throw StreamInterruptedException("socket reset", EOFException())
    }

    private fun completed(vararg texts: String): Flow<MessageChunk> = flow {
        texts.forEach { emit(chunk(UIMessagePart.Text(it))) }
    }

    private fun chunk(vararg parts: UIMessagePart) = MessageChunk(
        id = "chunk",
        model = "test",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            )
        ),
    )

    private fun List<MessageChunk>.text(): String = flatMap { it.choices }
        .flatMap { (it.delta?.parts ?: it.message?.parts).orEmpty() }
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

    private fun List<MessageChunk>.reasoning(): String = flatMap { it.choices }
        .flatMap { (it.delta?.parts ?: it.message?.parts).orEmpty() }
        .filterIsInstance<UIMessagePart.Reasoning>()
        .joinToString("") { it.reasoning }

    @Test
    fun `only torn-down sockets are replayable`() {
        assertTrue(StreamInterruptedException("reset", EOFException()).isReplayableTransportFailure())
        assertTrue(SocketTimeoutException("read timed out").isReplayableTransportFailure())
        assertTrue(ModelRequestException("m", EOFException()).isReplayableTransportFailure())

        assertFalse(
            StreamInterruptedException("bad event", IllegalStateException("bad json"))
                .isReplayableTransportFailure(),
        )
        assertFalse(ModelRequestException("m", IllegalStateException("bad body")).isReplayableTransportFailure())
    }
}
