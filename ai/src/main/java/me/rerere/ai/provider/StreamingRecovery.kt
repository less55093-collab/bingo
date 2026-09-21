package me.rerere.ai.provider

import android.util.Log
import java.io.EOFException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "StreamResume"

/**
 * Registers how the host App reports connectivity. The provider layer runs inside Android but owns
 * no `Context`, and a resume must not be attempted on a hand-over or captive network.
 */
object StreamNetworkState {
    @Volatile
    private var probe: (suspend () -> Boolean)? = null

    fun install(probe: suspend () -> Boolean) {
        this.probe = probe
    }

    suspend fun isAvailable(): Boolean = probe?.invoke() ?: true
}

/**
 * Runs one streaming model step with automatic, bounded resume.
 *
 * A chat stream survives the App going to the background by holding an Android foreground service,
 * but the socket itself can still break: a network hand-off, Doze, or a dropped keep-alive. Losing
 * a long reply at its last paragraph is not acceptable, so an interrupted transport is requested
 * again with the already-received text as an assistant prefill, and only genuinely new content is
 * emitted downstream. The caller therefore stores exactly what one uninterrupted stream would have
 * produced.
 *
 * [collectOnce] runs a single attempt: it receives the message history for that attempt and must
 * stream the request to completion, throwing [StreamInterruptedException] if the socket breaks.
 *
 * Reasoning is deliberately not prefilled. Providers do not accept a reasoning continuation, and
 * resending a partial thinking trace mostly makes the model restate it.
 */
fun resumableStream(
    messages: List<UIMessage>,
    networkAvailable: (suspend () -> Boolean)? = StreamNetworkState::isAvailable,
    idleTimeoutMillis: Long = CHAT_STREAM_IDLE_TIMEOUT_MILLIS,
    maxAttempts: Int = CHAT_STREAM_MAX_ATTEMPTS,
    resumed: (StreamResume) -> Unit = {},
    collectOnce: (messages: List<UIMessage>, attempt: Int) -> Flow<MessageChunk>,
): Flow<MessageChunk> = channelFlow {
    val tracker = StreamResumeTracker()
    // The caller can stop collecting at any moment (a `first()` probe, a user stop, or a cancelled
    // conversation). OkHttp then reports its own cancellation as a stream failure, which must not
    // be mistaken for a resumable transport break.
    val collectorCanceled = AtomicBoolean(false)
    coroutineContext.job.invokeOnCompletion { cause ->
        if (cause != null) collectorCanceled.set(true)
    }
    var attempt = 1
    while (true) {
        var failure: Throwable? = null
        try {
            val completed = withTimeoutOrNull(idleTimeoutMillis) {
                collectOnce(messages.withStreamPrefill(tracker.requestPrefill()), attempt).collect { chunk ->
                    tracker.accept(chunk)?.let { filtered -> send(filtered) }
                }
                true
            }
            if (completed == null) {
                // A stream that has said nothing for this long is treated as a dead socket: the
                // conversation was still live, so the only useful outcome is to continue it.
                failure = StreamInterruptedException(
                    "模型在 ${idleTimeoutMillis / 1000} 秒内没有返回任何内容，连接已中断",
                    EOFException("stream idle timeout after ${idleTimeoutMillis}ms"),
                )
                Log.w(TAG, "stream_idle_timeout attempt=$attempt millis=$idleTimeoutMillis")
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            failure = error
        }
        if (failure == null) {
            // The attempt finished cleanly: release anything still buffered for overlap detection.
            tracker.flushPending()?.let { tail -> send(tail) }
            return@channelFlow
        }

        // A cancelled collector is not a transport failure: OkHttp reports the cancellation we
        // caused as a stream failure. The cancel signal may still be in flight while the failure is
        // being caught, so yield once before reading it — a cancelled scope fails this suspension
        // and the caller sees cancellation, not a fake "connection interrupted" error.
        yield()
        val collectorGone = withContext(NonCancellable) {
            collectorCanceled.get() || coroutineContext.job.isCancelled
        }
        if (collectorGone) throw CancellationException("stream collector was cancelled")
        if (!shouldResumeStream(attempt, tracker.content, failure, maxAttempts)) {
            Log.w(
                TAG,
                "stream_giveup attempt=$attempt max=$maxAttempts " +
                    "cause=${failure.javaClass.simpleName} " +
                    "root=${generateSequence(failure) { it.cause }.last().javaClass.simpleName} " +
                    "replayable=${failure.isReplayableTransportFailure()} " +
                    "text_chars=${tracker.content.text.length}",
            )
            throw failure
        }

        Log.w(
            TAG,
            "stream_resume attempt=$attempt cause=${failure.javaClass.simpleName} " +
                "text_chars=${tracker.content.text.length}",
        )
        resumed(StreamResume(attempt = attempt, content = tracker.content, cause = failure))
        awaitStreamResumeDelay(attempt = attempt, networkAvailable = networkAvailable)
        attempt++
    }
}

/**
 * Rebuilds a resumed attempt into the text the caller should store.
 *
 * The model sees the partial reply as an assistant prefill, so what arrives first is usually a
 * restatement of prose the user has already read, followed by the actual continuation. Those
 * characters cannot be emitted the moment they arrive, because the same text may already be stored.
 * Each attempt therefore holds its raw stream in [pending] until it is provably past the prefill:
 *
 * - [content] is always exactly what has been emitted downstream;
 * - [requestPrefill] is exactly what must be sent back to continue the reply;
 * - nothing is emitted twice, and nothing is dropped.
 */
internal class StreamResumeTracker {
    /** Everything produced for this step so far, replay included. */
    var content: StreamContent = StreamContent()
        private set

    /**
     * Raw text of the current attempt that has not been classified yet.
     *
     * While the attempt is still reproducing the prefill, releasing any of this would duplicate
     * text the user has already read, so release happens only once the attempt is longer than the
     * matching run.
     */
    private var pending = ""

    /** Offset in [content].text where the current attempt's [pending] starts matching. */
    private var alignment = 0

    /** True once the current attempt provably continued past the prefill. */
    private var replaySettled = false

    fun accept(chunk: MessageChunk): MessageChunk? {
        val choice = chunk.choices.firstOrNull() ?: return chunk
        val message = choice.delta ?: choice.message ?: return chunk
        if (message.role != MessageRole.ASSISTANT) return chunk

        val filteredParts = buildList {
            message.parts.forEach { part -> acceptPart(part)?.let(::add) }
        }
        // A chunk whose text was entirely dropped as replay still has to reach the caller when it
        // also carries a terminal signal, otherwise the step would never be considered finished.
        if (filteredParts.isEmpty() && message.parts.isNotEmpty()) {
            val carriesSignal = chunk.usage != null || choice.finishReason != null
            if (!carriesSignal) return null
        }
        return chunk.copy(
            choices = listOf(choice.copy(delta = message.copy(parts = filteredParts))),
        )
    }

    private fun acceptPart(part: UIMessagePart): UIMessagePart? = when (part) {
        is UIMessagePart.Text -> acceptText(part)

        is UIMessagePart.Reasoning -> {
            // Reasoning is not prefilled, so it never needs overlap removal; it only has to be
            // tracked so a later attempt can keep going while the earlier trace stays visible.
            if (part.reasoning.isEmpty() && part.metadata == null) return null
            content = content.copy(reasoning = content.reasoning + part.reasoning)
            part
        }

        is UIMessagePart.Tool, is UIMessagePart.ToolCall -> {
            content = content.copy(hasToolCalls = true)
            part
        }

        else -> part
    }

    private fun acceptText(part: UIMessagePart.Text): UIMessagePart? {
        if (part.text.isEmpty()) return null
        // The first attempt has nothing to align against: every character is new.
        if (content.text.isEmpty()) {
            content = content.copy(text = part.text)
            return part
        }
        if (replaySettled) {
            content = content.copy(text = content.text + part.text)
            return part
        }

        pending += part.text
        val shown = content.text
        // Characters that still match the stored reply, starting at the alignment point, are a
        // replay. Only what extends beyond that point can be new text.
        var matched = 0
        while (
            alignment + matched < shown.length &&
            matched < pending.length &&
            shown[alignment + matched] == pending[matched]
        ) {
            matched++
        }

        val buffered = alignment + matched
        if (buffered < shown.length) {
            // The attempt is still inside the stored reply, or it diverged too early to tell a
            // replay from ordinary repeated wording. Hold the buffer and let the next chunk decide.
            return null
        }
        if (matched == 0 && pending.length < STREAM_REPLAY_MIN_OVERLAP) {
            // Diverged exactly at the end of the prefill, which is what a continuation does, but
            // the evidence is still thin: wait for enough characters before releasing.
            return null
        }

        replaySettled = true
        val released = pending.substring(matched)
        pending = ""
        content = content.copy(text = content.text + released)
        return UIMessagePart.Text(released)
    }

    /** Text to send back so the model continues instead of restarting. */
    fun requestPrefill(): String = content.text

    /**
     * Releases a buffer left aligned on a prefill boundary when the attempt ends cleanly.
     *
     * A stream can stop exactly where the replayed prefix ends. Those characters were never proven
     * new, but nothing else will arrive to prove anything, so they belong to this reply.
     */
    fun flushPending(): MessageChunk? {
        if (pending.isEmpty()) return null
        val released = pending
        pending = ""
        replaySettled = true
        content = content.copy(text = content.text + released)
        return MessageChunk(
            id = "",
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text(released)),
                    ),
                    message = null,
                    finishReason = null,
                )
            ),
        )
    }
}

/** An attempt that failed mid-stream, with the content that is worth continuing from. */
data class StreamResume(
    val attempt: Int,
    val content: StreamContent,
    val cause: Throwable,
)
