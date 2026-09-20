package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import java.util.UUID
import kotlin.uuid.Uuid

/** A saved explicit action becomes one stable tool call without consulting the chat provider. */
internal fun prepareDirectImageToolMessage(messages: List<UIMessage>, model: Model): UIMessage? {
    val request = messages.lastOrNull()?.takeIf {
        it.role == MessageRole.USER && UIMessageAnnotation.DirectImageRequest in it.annotations
    } ?: return null
    val validationError = directImageInputError(request.parts)
    require(validationError == null) { validationError.orEmpty() }
    val prompt = request.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }.trim()
    val responseId = Uuid.parse(UUID.nameUUIDFromBytes("direct-image:${request.id}".toByteArray()).toString())
    return UIMessage(
        id = responseId,
        role = MessageRole.ASSISTANT,
        modelId = model.id,
        parts = listOf(UIMessagePart.Tool(
            toolCallId = "direct_image_${request.id}",
            toolName = IMAGE_GENERATION_TOOL_NAME,
            input = buildJsonObject {
                put("prompt", prompt)
                put("size", ImageGenSize.AUTO.value)
            }.toString(),
            // The user has explicitly selected and sent a single image request.
            approvalState = ToolApprovalState.Approved,
        )),
    )
}

internal fun directImageInputError(parts: List<UIMessagePart>): String? = when {
    parts.filterIsInstance<UIMessagePart.Text>().none { it.text.isNotBlank() } -> "请先描述要生成的图片"
    parts.any { it !is UIMessagePart.Text && it !is UIMessagePart.Image } ->
        "直接生图支持文字和参考图片，请移除其他附件后发送"
    else -> null
}
