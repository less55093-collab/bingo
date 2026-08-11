package me.rerere.ai.provider.providers

import me.rerere.ai.ui.UIMessagePart

private const val IMAGE_GENERATION_TOOL_NAME = "generate_image"
internal const val IMAGE_GENERATION_TOOL_SUCCESS_MESSAGE =
    "Image generated successfully and displayed to the user."

/**
 * Returns the output that should be sent back to the model for the next tool round.
 *
 * Generated images remain attached to [UIMessagePart.Tool.output] for the UI and persistence,
 * but re-uploading their local files as Base64 makes the follow-up model request unnecessarily
 * large. The model already has the tool call's prompt, so a compact completion marker is enough.
 */
internal fun UIMessagePart.Tool.outputForModel(): List<UIMessagePart> =
    if (toolName == IMAGE_GENERATION_TOOL_NAME && output.any { it is UIMessagePart.Image }) {
        listOf(UIMessagePart.Text(IMAGE_GENERATION_TOOL_SUCCESS_MESSAGE))
    } else {
        output
    }

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}
