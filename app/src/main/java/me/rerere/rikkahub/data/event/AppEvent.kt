package me.rerere.rikkahub.data.event

import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data object OpenUsageAccessSettings : AppEvent()

    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()

    /** 聊天生成过程中的流式更新，由 ChatNotificationManager 消费用于 Live Update 通知。 */
    data class ChatGenerationUpdate(
        val conversationId: Uuid,
        val lastMessage: UIMessage,
        val senderName: String,
        val runToken: Long,
    ) : AppEvent()

    /** The terminal result of one chat generation attempt. */
    enum class ChatGenerationResult {
        /** The provider sent its protocol-level completion event. */
        COMPLETED,

        /** The stream or local foreground protection ended unexpectedly. */
        INTERRUPTED,

        /** The user explicitly stopped the generation. */
        CANCELLED,
    }

    /**
     * 聊天生成结束。只有 [ChatGenerationResult.COMPLETED] 可产生完成通知或后续生成。
     * 中断和取消仍会清理 Live Update 通知，但不得被当作模型成功回复。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val result: ChatGenerationResult,
        val contentPreview: String? = null,
    ) : AppEvent()

    /**
     * 生图结束。生图跑在 AppScope 上，用户离开页面后仍在继续，
     * 因此结束时需要通知用户。[error] 非空表示失败。
     */
    data class ImageGenerationEnded(
        val prompt: String,
        val imageCount: Int,
        val error: String?,
    ) : AppEvent()
}
