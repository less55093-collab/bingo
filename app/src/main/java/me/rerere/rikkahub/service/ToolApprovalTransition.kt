package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/** One optimistic approval transition, bound to the selected tail message rather than a global call ID. */
internal data class ToolApprovalTransition(
    val conversation: Conversation,
    val messageId: Uuid,
    val replacement: UIMessagePart.Tool,
) {
    fun canContinue(current: Conversation): Boolean {
        val node = current.messageNodes.lastOrNull() ?: return false
        val message = node.messages.getOrNull(node.selectIndex) ?: return false
        if (message.id != messageId || message.role != MessageRole.ASSISTANT) return false
        val tool = message.getTools().singleOrNull { it.toolCallId == replacement.toolCallId } ?: return false
        return !tool.isExecuted && !tool.executionStarted &&
            tool.approvalState == replacement.approvalState && tool.input == replacement.input
    }
}

/** A delayed planning snapshot cannot make a consumed approval actionable again. */
internal fun UIMessage.withAcceptedToolApprovals(latest: UIMessage?): UIMessage {
    if (latest?.id != id) return this
    val accepted = latest.getTools().filter { tool ->
        tool.approvalState is ToolApprovalState.Approved || tool.approvalState is ToolApprovalState.Denied ||
            tool.approvalState is ToolApprovalState.Answered
    }.associateBy { it.toolCallId }
    if (accepted.isEmpty()) return this
    return copy(parts = parts.map { part ->
        if (part is UIMessagePart.Tool && part.isPending && !part.isExecuted && !part.executionStarted) {
            accepted[part.toolCallId]?.takeIf { it.toolName == part.toolName } ?: part
        } else part
    })
}

/** The caller applies the returned snapshot while holding the conversation session lock. */
internal fun Conversation.transitionPendingTailTool(
    toolCallId: String,
    approvalState: ToolApprovalState,
    inputOverride: String? = null,
    expectedMessageId: Uuid? = null,
): ToolApprovalTransition? {
    if (approvalState is ToolApprovalState.Pending || approvalState is ToolApprovalState.Auto) return null
    val node = messageNodes.lastOrNull() ?: return null
    val message = node.messages.getOrNull(node.selectIndex) ?: return null
    if (message.role != MessageRole.ASSISTANT) return null
    if (expectedMessageId != null && message.id != expectedMessageId) return null
    // Legacy clients have no message identity. Do not guess when an upstream reused a call ID.
    if (expectedMessageId == null && messageNodes.any { candidate ->
            candidate.messages.any { it.id != message.id && it.getTools().any { tool -> tool.toolCallId == toolCallId } }
        }) return null
    val tool = message.getTools().singleOrNull { it.toolCallId == toolCallId } ?: return null
    if (!tool.isPending || tool.isExecuted || tool.executionStarted) return null
    val replacement = tool.copy(input = inputOverride ?: tool.input, approvalState = approvalState)
    val updated = message.copy(parts = message.parts.map { if (it === tool) replacement else it })
    val updatedNode = node.copy(messages = node.messages.mapIndexed { index, value ->
        if (index == node.selectIndex) updated else value
    })
    return ToolApprovalTransition(
        conversation = copy(messageNodes = messageNodes.dropLast(1) + updatedNode),
        messageId = message.id,
        replacement = replacement,
    )
}
