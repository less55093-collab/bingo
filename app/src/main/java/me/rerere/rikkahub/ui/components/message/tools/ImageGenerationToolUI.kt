package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ai.ImageGenerationLoading
import me.rerere.rikkahub.ui.components.ai.rememberImageGenerationProgress
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage

/** 生图工具名, 与 buildImageGenerationTool 保持一致 */
const val IMAGE_GENERATION_TOOL_NAME = "generate_image"

/**
 * 生图工具的专属渲染: 不显示工具调用卡片, 而是像 ChatGPT 那样直接在对话流里给出一个
 * 占位画布 + 流光动效, 出图后原地替换成图片
 */
@Composable
fun ImageGenerationToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
) {
    val images = remember(tool.output) { tool.output.filterIsInstance<UIMessagePart.Image>() }
    val aspectRatio = remember(tool.input) { tool.imageAspectRatio() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (images.isEmpty()) {
            val timerKey = tool.toolCallId.ifBlank { "${tool.toolName}:${tool.input.hashCode()}" }
            val progress = rememberImageGenerationProgress(
                toolCallId = timerKey,
                running = loading,
            )
            ImageGenerationLoading(
                progress = progress.value,
                showSlowHint = progress.showSlowHint,
                loading = loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            images.fastForEach { image ->
                ZoomableAsyncImage(
                    model = image.url,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}

/**
 * 从工具入参的 size 推断占位画布比例, 让占位框和最终图片尺寸一致, 避免出图时跳动
 */
private fun UIMessagePart.Tool.imageAspectRatio(): Float {
    val size = inputAsJson().getStringContent("size") ?: return 1f
    val (width, height) = size.split("x").takeIf { it.size == 2 } ?: return 1f
    val w = width.trim().toFloatOrNull() ?: return 1f
    val h = height.trim().toFloatOrNull() ?: return 1f
    return if (w > 0f && h > 0f) w / h else 1f
}
