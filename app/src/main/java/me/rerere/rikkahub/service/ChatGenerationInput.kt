package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation

/** Model selection, tool selection and generation must describe the same selected history. */
internal data class ChatGenerationInput(val messages: List<UIMessage>) {
    val directImageGeneration: Boolean = messages.lastOrNull { it.role == MessageRole.USER }
        ?.annotations?.contains(UIMessageAnnotation.DirectImageRequest) == true
}

internal fun selectChatGenerationInput(
    messages: List<UIMessage>,
    messageRange: ClosedRange<Int>?,
): ChatGenerationInput {
    if (messageRange == null) return ChatGenerationInput(messages)
    val start = messageRange.start
    val end = messageRange.endInclusive
    require(start >= 0 && start <= messages.size && end >= start - 1 && end < messages.size) {
        "要重新生成的消息范围已变化，请重新选择消息"
    }
    return ChatGenerationInput(messages.subList(start, end + 1))
}
