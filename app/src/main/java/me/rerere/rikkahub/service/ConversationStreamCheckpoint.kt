package me.rerere.rikkahub.service

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishGeneration
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.markGenerationInterrupted
import me.rerere.common.android.Logging
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

private const val CHECKPOINT_TAG = "StreamCheckpoint"

/**
 * Identifies an existing assistant node being regenerated. The replacement assistant receives a
 * new message ID on its first chunk, so keep both identities: the old one for the baseline and
 * the new one for every subsequent snapshot and terminal operation.
 */
class StreamGenerationTarget(
    val nodeId: Uuid,
    private val previousMessageId: Uuid,
) {
    private var generatedMessageId: Uuid? = null

    fun observe(conversation: Conversation) {
        val candidate = conversation.messageNodes
            .firstOrNull { it.id == nodeId }
            ?.currentMessage
            ?: return
        if (generatedMessageId == null && candidate.role == MessageRole.ASSISTANT && candidate.id != previousMessageId) {
            generatedMessageId = candidate.id
        }
    }

    fun messageIn(conversation: Conversation): UIMessage? {
        val node = conversation.messageNodes.firstOrNull { it.id == nodeId } ?: return null
        val generatedId = generatedMessageId ?: return null
        return node.messages.firstOrNull { it.id == generatedId }
    }
}

/**
 * Coalesces frequent stream updates into bounded Room writes. A caller always supplies the latest
 * snapshot, so a slow disk never applies a stale chunk over a newer one.
 */
class ConversationStreamCheckpoint private constructor(
    private val saveSnapshot: suspend (Conversation) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val trace: (String) -> Unit = {},
) {
    constructor(repository: ConversationRepository) : this(
        saveSnapshot = repository::saveStreamingSnapshot,
        trace = { Logging.log(CHECKPOINT_TAG, it) },
    )

    internal constructor(
        nowMillis: () -> Long,
        saveSnapshot: suspend (Conversation) -> Unit,
    ) : this(saveSnapshot, nowMillis)

    private data class State(
        val generationToken: Long,
        var latest: Conversation? = null,
        val initialMarker: StreamPayloadMarker?,
        val target: StreamGenerationTarget? = null,
        var lastSavedAt: Long = 0,
        var lastSavedPayloadBytes: Int = 0,
        var hasSaved: Boolean = false,
        var hasReceivedStreamPayload: Boolean = false,
    )

    /**
     * Serializes every checkpoint and terminal conversation write for a session. A newer generation
     * receives a new token; stale chunks from an old token are ignored instead of overwriting it.
     */
    private val mutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val states = ConcurrentHashMap<Uuid, State>()
    private val activeTokens = ConcurrentHashMap<Uuid, Long>()

    /** Makes [generationToken] the only stream allowed to persist snapshots for this conversation. */
    suspend fun start(
        conversationId: Uuid,
        generationToken: Long,
        initialConversation: Conversation,
        target: StreamGenerationTarget? = null,
    ) {
        val mutex = mutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            activeTokens[conversationId] = generationToken
            states[conversationId] = State(
                generationToken = generationToken,
                latest = initialConversation,
                initialMarker = initialConversation.streamPayloadMarker(target),
                target = target,
            )
            trace(
                "checkpoint_start generation=$generationToken target=" +
                    if (target == null) "tail" else "historical",
            )
        }
    }

    suspend fun isCurrent(conversationId: Uuid, generationToken: Long): Boolean {
        val mutex = mutexes[conversationId] ?: return false
        return mutex.withLock { activeTokens[conversationId] == generationToken }
    }

    suspend fun offer(conversationId: Uuid, generationToken: Long, conversation: Conversation) {
        val mutex = mutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            if (activeTokens[conversationId] != generationToken) return
            val state = states.getOrPut(conversationId) {
                State(
                    generationToken = generationToken,
                    initialMarker = conversation.streamPayloadMarker(),
                )
            }
            state.latest = conversation
            val payloadBytes = conversation.streamPayloadBytes(state.target)
            if (conversation.streamPayloadMarker(state.target)?.let { it.isMeaningful && it != state.initialMarker } == true) {
                state.hasReceivedStreamPayload = true
            }
            // Provider usage events and empty assistant shells reach this method too. The user
            // message has already been saved before the request starts, so do not consume the
            // immediate-checkpoint slot until actual stream payload has appeared.
            val reason = when {
                !state.hasReceivedStreamPayload -> null
                !state.hasSaved -> "first_payload"
                nowMillis() - state.lastSavedAt >= CHECKPOINT_INTERVAL_MILLIS -> "interval"
                payloadBytes - state.lastSavedPayloadBytes >= CHECKPOINT_PAYLOAD_DELTA_BYTES -> "payload_delta"
                else -> null
            }
            if (reason != null) saveLocked(state, reason)
        }
    }

    suspend fun flush(
        conversationId: Uuid,
        generationToken: Long? = null,
        conversation: Conversation? = null,
    ) {
        val mutex = mutexes[conversationId] ?: return
        mutex.withLock {
            if (generationToken != null && activeTokens[conversationId] != generationToken) return
            val state = states[conversationId]
            if (conversation != null && state != null) state.latest = conversation
            state?.let { saveLocked(it, "forced_flush") }
        }
    }

    /** Runs a final full persistence write under the same session lock as its stream snapshots. */
    suspend fun saveFinal(
        conversationId: Uuid,
        generationToken: Long,
        conversation: Conversation,
        save: suspend (Conversation) -> Unit,
    ): Boolean {
        val mutex = mutexes.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock {
            if (activeTokens[conversationId] != generationToken) return@withLock false
            val state = states.getOrPut(conversationId) {
                State(
                    generationToken = generationToken,
                    initialMarker = conversation.streamPayloadMarker(),
                )
            }
            state.latest = conversation
            saveLocked(state, "terminal_flush")
            val finalSaveStartedAt = System.nanoTime()
            save(conversation)
            trace(
                "final_save generation=$generationToken duration_ms=" +
                    elapsedMillis(finalSaveStartedAt),
            )
            states.remove(conversationId)
            activeTokens.remove(conversationId)
            true
        }
    }

    /** Drops only this generation's bookkeeping; an older job must never clear a newer token. */
    suspend fun discard(conversationId: Uuid, generationToken: Long? = null) {
        val mutex = mutexes[conversationId] ?: return
        mutex.withLock {
            if (generationToken != null && activeTokens[conversationId] != generationToken) return
            val discarded = states.remove(conversationId)
            activeTokens.remove(conversationId)
            discarded?.let { trace("checkpoint_discard generation=${it.generationToken}") }
        }
    }

    private suspend fun saveLocked(state: State, reason: String) {
        val savedAt = nowMillis()
        val snapshot = state.latest?.copy(
            updateAt = java.time.Instant.ofEpochMilli(savedAt),
        ) ?: return
        state.latest = snapshot
        val payloadBytes = snapshot.streamPayloadBytes(state.target)
        val saveStartedAt = System.nanoTime()
        runCatching { saveSnapshot(snapshot) }
            .onSuccess {
                state.hasSaved = true
                state.lastSavedAt = savedAt
                state.lastSavedPayloadBytes = payloadBytes
                trace(
                    "checkpoint_saved generation=${state.generationToken} reason=$reason " +
                        "payload_bytes=$payloadBytes duration_ms=${elapsedMillis(saveStartedAt)}",
                )
            }
            // Checkpoint writes are best effort. Terminal persistence is intentionally handled by
            // saveFinal(), where the caller can surface a retryable failure without logging content.
            .onFailure { error ->
                trace(
                    "checkpoint_save_failed generation=${state.generationToken} reason=$reason " +
                        "error=${error.javaClass.simpleName}",
                )
            }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000L

    private companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 1_000L
        const val CHECKPOINT_PAYLOAD_DELTA_BYTES = 4 * 1024
    }
}

private data class StreamPayloadMarker(
    val messageId: Uuid,
    val payloadHash: Int,
    val isMeaningful: Boolean,
)

/**
 * The tail assistant message is the only selected node a generation can change. Comparing it
 * avoids treating a usage-only event as content while also handling regeneration of a long branch
 * with a shorter replacement: its payload can shrink but its message identity/content changes.
 */
private fun Conversation.streamPayloadMarker(target: StreamGenerationTarget? = null): StreamPayloadMarker? {
    val message = target?.messageIn(this)
        ?: if (target == null) currentMessages.lastOrNull() else null
    if (message?.role != MessageRole.ASSISTANT) return null
    return StreamPayloadMarker(
        messageId = message.id,
        payloadHash = message.streamPayloadHash(),
        isMeaningful = message.hasMeaningfulStreamPayload(),
    )
}

private fun UIMessage.streamPayloadHash(): Int = buildString {
    append(id)
    parts.forEach { append(it.streamPayloadFingerprint()) }
    annotations.forEach(::append)
}.hashCode()

private fun UIMessage.hasMeaningfulStreamPayload(): Boolean =
    parts.any(UIMessagePart::hasMeaningfulStreamPayload) || annotations.isNotEmpty()

@Suppress("DEPRECATION")
private fun UIMessagePart.hasMeaningfulStreamPayload(): Boolean = when (this) {
    is UIMessagePart.Text -> text.isNotEmpty() || metadata != null
    is UIMessagePart.Reasoning -> reasoning.isNotEmpty() || metadata != null
    is UIMessagePart.Tool -> {
        toolCallId.isNotEmpty() || toolName.isNotEmpty() || input.isNotEmpty() || metadata != null ||
            output.any(UIMessagePart::hasMeaningfulStreamPayload)
    }
    is UIMessagePart.Image -> url.isNotEmpty() || metadata != null
    is UIMessagePart.Video -> url.isNotEmpty() || metadata != null
    is UIMessagePart.Audio -> url.isNotEmpty() || metadata != null
    is UIMessagePart.Document -> url.isNotEmpty() || fileName.isNotEmpty() || metadata != null
    is UIMessagePart.ToolCall -> {
        toolCallId.isNotEmpty() || toolName.isNotEmpty() || arguments.isNotEmpty() || metadata != null
    }
    is UIMessagePart.ToolResult -> {
        toolCallId.isNotEmpty() || toolName.isNotEmpty() || content.toString().isNotEmpty() ||
            arguments.toString().isNotEmpty() || metadata != null
    }
    UIMessagePart.Search -> false
}

private fun Conversation.streamPayloadBytes(target: StreamGenerationTarget? = null): Int {
    val messages = target?.messageIn(this)?.let(::listOf) ?: if (target == null) currentMessages else emptyList()
    return messages.sumOf { message ->
        message.parts.sumOf(UIMessagePart::streamPayloadBytes) +
            message.annotations.sumOf { it.toString().utf8Bytes() }
    }
}

@Suppress("DEPRECATION")
private fun UIMessagePart.streamPayloadBytes(): Int = when (this) {
    is UIMessagePart.Text -> text.utf8Bytes() + metadata.utf8Bytes()
    is UIMessagePart.Reasoning -> reasoning.utf8Bytes() + metadata.utf8Bytes()
    is UIMessagePart.Tool -> {
        toolCallId.utf8Bytes() + toolName.utf8Bytes() + input.utf8Bytes() +
            approvalState.toString().utf8Bytes() + metadata.utf8Bytes() +
            output.sumOf(UIMessagePart::streamPayloadBytes)
    }
    is UIMessagePart.Image -> url.utf8Bytes() + metadata.utf8Bytes()
    is UIMessagePart.Video -> url.utf8Bytes() + metadata.utf8Bytes()
    is UIMessagePart.Audio -> url.utf8Bytes() + metadata.utf8Bytes()
    is UIMessagePart.Document -> url.utf8Bytes() + fileName.utf8Bytes() + mime.utf8Bytes() + metadata.utf8Bytes()
    is UIMessagePart.ToolCall -> {
        toolCallId.utf8Bytes() + toolName.utf8Bytes() + arguments.utf8Bytes() +
            approvalState.toString().utf8Bytes() + metadata.utf8Bytes()
    }
    is UIMessagePart.ToolResult -> {
        toolCallId.utf8Bytes() + toolName.utf8Bytes() + content.toString().utf8Bytes() +
            arguments.toString().utf8Bytes() + metadata.utf8Bytes()
    }
    UIMessagePart.Search -> 0
}

@Suppress("DEPRECATION")
private fun UIMessagePart.streamPayloadFingerprint(): String = when (this) {
    is UIMessagePart.Text -> "text:$text:$metadata"
    is UIMessagePart.Reasoning -> "reasoning:$reasoning:$metadata"
    is UIMessagePart.Tool -> "tool:$toolCallId:$toolName:$input:$approvalState:$metadata:${output.joinToString { it.streamPayloadFingerprint() }}"
    is UIMessagePart.Image -> "image:$url:$metadata"
    is UIMessagePart.Video -> "video:$url:$metadata"
    is UIMessagePart.Audio -> "audio:$url:$metadata"
    is UIMessagePart.Document -> "document:$url:$fileName:$mime:$metadata"
    is UIMessagePart.ToolCall -> "tool_call:$toolCallId:$toolName:$arguments:$approvalState:$metadata"
    is UIMessagePart.ToolResult -> "tool_result:$toolCallId:$toolName:$content:$arguments:$metadata"
    UIMessagePart.Search -> "search"
}

private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

private fun Any?.utf8Bytes(): Int = this?.toString()?.utf8Bytes() ?: 0

/** Returns the assistant reply owned by this generation without falling back to unrelated nodes. */
internal fun Conversation.generationMessage(target: StreamGenerationTarget?): UIMessage? = when (target) {
    null -> currentMessages.lastOrNull()
    else -> target.messageIn(this)
}

internal fun Conversation.generationNodeIndex(target: StreamGenerationTarget?): Int = when (target) {
    null -> messageNodes.lastIndex
    else -> messageNodes.indexOfFirst { it.id == target.nodeId }
}

internal fun Conversation.finishGenerationReasoning(
    target: StreamGenerationTarget?,
    updateAt: java.time.Instant,
): Conversation {
    val targetIndex = generationNodeIndex(target)
    val targetMessage = generationMessage(target) ?: return this
    if (targetIndex < 0 || targetMessage.role != MessageRole.ASSISTANT) return this
    return copy(
        messageNodes = messageNodes.toMutableList().also { nodes ->
            val node = nodes[targetIndex]
            nodes[targetIndex] = node.copy(messages = node.messages.map { message ->
                if (message.id == targetMessage.id) message.finishGeneration() else message
            })
        },
        updateAt = updateAt,
    )
}

internal fun Conversation.finishGenerationByUser(
    target: StreamGenerationTarget?,
    cancelTool: (UIMessagePart.Tool) -> UIMessagePart.Tool,
    updateAt: java.time.Instant,
): Conversation {
    val targetIndex = generationNodeIndex(target)
    val current = generationMessage(target) ?: return this
    if (targetIndex < 0 || current.role != MessageRole.ASSISTANT) return this
    val finished = current.finishPendingTools(cancelTool).finishGeneration()
    if (finished == current) return this
    return copy(
        messageNodes = messageNodes.toMutableList().also { nodes ->
            val node = nodes[targetIndex]
            nodes[targetIndex] = node.copy(messages = node.messages.map { message ->
                if (message.id == current.id) finished else message
            })
        },
        updateAt = updateAt,
    )
}

internal fun Conversation.markGenerationInterrupted(
    target: StreamGenerationTarget?,
    updateAt: java.time.Instant,
): Conversation {
    val targetIndex = generationNodeIndex(target)
    val current = generationMessage(target) ?: return this
    if (targetIndex < 0 || current.role != MessageRole.ASSISTANT) return this
    val updated = current.markGenerationInterrupted()
    return copy(
        messageNodes = messageNodes.toMutableList().also { nodes ->
            val node = nodes[targetIndex]
            nodes[targetIndex] = node.copy(messages = node.messages.map { message ->
                if (message.id == current.id) updated else message
            })
        },
        updateAt = updateAt,
    )
}
