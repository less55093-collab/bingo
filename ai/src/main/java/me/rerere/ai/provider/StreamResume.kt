package me.rerere.ai.provider

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Transport tuning for long-lived SSE chat streams.
 *
 * A mobile client must keep its stream across a background switch, a wifi/cellular hand-off and
 * Doze. OkHttp treats a ping it cannot answer inside [CHAT_HTTP2_PING_INTERVAL_SECONDS] as a fatal
 * transport error and cancels the call, which surfaced as a hard [StreamInterruptedException] the
 * moment the OS paused the process. The interval is therefore generous rather than aggressive:
 * liveness is enforced by [CHAT_STREAM_IDLE_TIMEOUT_MILLIS] plus a bounded, resumable retry
 * instead of one missed pong.
 */
const val CHAT_HTTP2_PING_INTERVAL_SECONDS = 30L

/**
 * Upper bound on silence between two stream chunks. Must stay well above the longest legitimate
 * server think pause so a slow reasoning model is not mistaken for a dead socket.
 */
const val CHAT_STREAM_IDLE_TIMEOUT_MILLIS = 120_000L

/** Total stream attempts for one model step, including the first one. */
const val CHAT_STREAM_MAX_ATTEMPTS = 3

/** Total request attempts for one non-streamed model step, including the first one. */
const val CHAT_REQUEST_MAX_ATTEMPTS = 3

/**
 * Overlap below this length is ambiguous: it is the same text as often as it is ordinary shared
 * wording. Ten characters is longer than any Chinese function word and short enough to catch a
 * restated sentence.
 */
const val STREAM_REPLAY_MIN_OVERLAP = 10

/**
 * The short list of I/O failures that are actually worth replaying.
 *
 * A socket the OS or the network tore down mid-body ([EOFException], reset, TLS failure, keep-alive
 * timeout) says nothing about the request's validity, so replaying it cannot change the outcome.
 * Anything else is treated as terminal: an interrupted HTTP response that carried a body is an
 * upstream rejection or a redirect we deliberately refuse to replay, and an invalid-event failure
 * means the peer never spoke the protocol. Retrying either would hide a real failure or duplicate a
 * billable request.
 */
internal fun Throwable.isReplayableTransportFailure(): Boolean = when (this) {
    is EOFException, is SocketException, is SocketTimeoutException, is SSLException -> true
    is StreamInterruptedException -> cause?.isReplayableTransportFailure() == true
    else -> cause?.isReplayableTransportFailure() == true
}

/**
 * Backoff before [attempt] (1-based). A transport failure caused by a background transition
 * resolves in seconds; an offline device waits longer so the attempt budget is not spent while the
 * network is down.
 */
fun streamResumeDelayMillis(attempt: Int, networkAvailable: Boolean): Long {
    val base = if (networkAvailable) {
        STREAM_RESUME_BASE_DELAY_MILLIS * attempt
    } else {
        STREAM_RESUME_OFFLINE_DELAY_MILLIS
    }
    return base.coerceAtMost(STREAM_RESUME_MAX_DELAY_MILLIS)
}

private const val STREAM_RESUME_BASE_DELAY_MILLIS = 600L
private const val STREAM_RESUME_OFFLINE_DELAY_MILLIS = 3_000L
private const val STREAM_RESUME_MAX_DELAY_MILLIS = 5_000L
private const val STREAM_RESUME_NETWORK_WAIT_MILLIS = 12_000L
private const val STREAM_RESUME_NETWORK_POLL_MILLIS = 400L

/**
 * Waits out [attempt]'s backoff before the next try.
 *
 * When [networkAvailable] is supplied and reports no connectivity, this waits for the network to
 * come back first: coming back from the background the OS may briefly expose a captive or
 * mid-handover network, and a request started then fails without proving anything about the
 * generation itself.
 */
suspend fun awaitStreamResumeDelay(
    attempt: Int,
    networkAvailable: (suspend () -> Boolean)? = null,
    sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    if (networkAvailable != null && !networkAvailable()) {
        val restored = withTimeoutOrNull(STREAM_RESUME_NETWORK_WAIT_MILLIS) {
            while (!networkAvailable()) {
                sleeper(STREAM_RESUME_NETWORK_POLL_MILLIS)
            }
            true
        }
        if (restored == null) return
    }
    sleeper(streamResumeDelayMillis(attempt, networkAvailable?.invoke() ?: true))
}

/**
 * Content already produced for one step.
 *
 * A resumed request carries the partial reply back as an assistant prefill so the model continues
 * from where it stopped. [text] is the assistant text that is both stored and reused as prefill;
 * the resumed stream replays part of it, and [StreamResumeTracker] keeps that replay out of the
 * stored message.
 */
data class StreamContent(
    val text: String = "",
    val reasoning: String = "",
    val hasToolCalls: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank() && reasoning.isBlank() && !hasToolCalls
}

/**
 * True while another attempt is worth making for a stream that broke with [error].
 *
 * Only actual transport failures are retried: schema errors, billing rejections and upstream error
 * responses are terminal. A completed tool call is terminal too, because its argument JSON cannot
 * be reconstructed from a partial stream.
 */
fun shouldResumeStream(
    attempt: Int,
    content: StreamContent,
    error: Throwable,
    maxAttempts: Int = CHAT_STREAM_MAX_ATTEMPTS,
): Boolean {
    if (attempt >= maxAttempts) return false
    if (content.hasToolCalls) return false
    return error.isReplayableTransportFailure()
}

/**
 * Appends already-shown text back into the request so the model can continue instead of restarting.
 *
 * The prefill is appended as a new trailing assistant message rather than mutating the last one: a
 * turn can legitimately end on a tool result, and rewriting that message would corrupt history.
 */
fun List<UIMessage>.withStreamPrefill(text: String): List<UIMessage> {
    if (text.isBlank()) return this
    return this + UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )
}
