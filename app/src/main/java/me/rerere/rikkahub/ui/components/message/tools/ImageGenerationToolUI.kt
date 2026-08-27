package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_PLAN_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.local.ImageGenerationVariant
import me.rerere.rikkahub.data.ai.tools.local.parseImageGenerationVariants
import me.rerere.rikkahub.ui.components.ai.ImageGenerationLoading
import me.rerere.rikkahub.ui.components.ai.rememberImageGenerationProgress
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.isInsufficientBalanceError

internal enum class ImageGenerationToolPhase {
    PLAN_PREPARING,
    PLAN_APPROVAL,
    PLAN_CONFIRMED,
    GENERATING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

internal fun resolveImageGenerationToolPhase(
    toolName: String,
    input: String,
    approvalState: ToolApprovalState,
    hasOutput: Boolean,
    hasImages: Boolean,
): ImageGenerationToolPhase {
    if (approvalState is ToolApprovalState.Denied) return ImageGenerationToolPhase.CANCELLED
    if (hasImages) return ImageGenerationToolPhase.COMPLETED
    if (hasOutput) return ImageGenerationToolPhase.FAILED
    if (toolName == IMAGE_GENERATION_TOOL_NAME) return ImageGenerationToolPhase.GENERATING
    if (approvalState is ToolApprovalState.Pending) return ImageGenerationToolPhase.PLAN_APPROVAL
    if (approvalState == ToolApprovalState.Approved) return ImageGenerationToolPhase.PLAN_CONFIRMED

    val variantCount = runCatching {
        parseImageGenerationVariants(Json.parseToJsonElement(input.ifBlank { "{}" })).size
    }.getOrNull()
    return if (variantCount == 1) {
        ImageGenerationToolPhase.GENERATING
    } else {
        ImageGenerationToolPhase.PLAN_PREPARING
    }
}

internal fun initialImageGenerationSelection(variantCount: Int): Set<Int> =
    if (variantCount > 0) setOf(0) else emptySet()

internal fun updateImageGenerationSelection(
    selectedIndices: Set<Int>,
    index: Int,
    selected: Boolean,
): Set<Int> = if (selected) selectedIndices + index else selectedIndices - index

/**
 * 生图工具的专属渲染: 不显示工具调用卡片, 而是像 ChatGPT 那样直接在对话流里给出一个
 * 占位画布 + 流光动效, 出图后原地替换成图片
 */
@Composable
fun ImageGenerationToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onApprove: ((inputOverride: String) -> Unit)? = null,
    onDeny: ((reason: String) -> Unit)? = null,
) {
    val images = remember(tool.output) { tool.output.filterIsInstance<UIMessagePart.Image>() }
    val aspectRatio = remember(tool.input) { tool.imageAspectRatio() }
    val variants = remember(tool.input) {
        runCatching { parseImageGenerationVariants(tool.inputAsJson()) }.getOrElse { emptyList() }
    }
    val insufficientBalance = remember(tool.output) {
        tool.output.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isInsufficientBalanceError() }
    }
    val phase = remember(tool.input, tool.output, tool.approvalState, images) {
        resolveImageGenerationToolPhase(
            toolName = tool.toolName,
            input = tool.input,
            approvalState = tool.approvalState,
            hasOutput = tool.isExecuted,
            hasImages = images.isNotEmpty(),
        )
    }
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (insufficientBalance) {
            ImageGenerationBalancePrompt(
                onTopUp = { navController.navigate(Screen.Redeem) },
            )
        } else {
            when (phase) {
                ImageGenerationToolPhase.PLAN_PREPARING -> ImageGenerationPlanPreparing()
                ImageGenerationToolPhase.PLAN_APPROVAL -> {
                    if (variants.size > 1 && onApprove != null && onDeny != null) {
                        ImageGenerationPlanCard(
                            variants = variants,
                            onApprove = { selected ->
                                onApprove(buildSelectedImageGenerationInput(tool.inputAsJson().jsonObject, selected))
                            },
                            onDeny = onDeny,
                        )
                    } else {
                        ImageGenerationPlanPreparing()
                    }
                }
                ImageGenerationToolPhase.PLAN_CONFIRMED -> {
                    ImageGenerationPlanSummary(variants = variants)
                    ImageGenerationProgress(
                        tool = tool,
                        aspectRatio = aspectRatio,
                        loading = loading,
                    )
                }
                ImageGenerationToolPhase.CANCELLED -> ImageGenerationCancelled()
                ImageGenerationToolPhase.GENERATING -> ImageGenerationProgress(
                    tool = tool,
                    aspectRatio = aspectRatio,
                    loading = loading,
                )
                ImageGenerationToolPhase.FAILED -> {
                    if (tool.toolName == IMAGE_GENERATION_PLAN_TOOL_NAME) {
                        ImageGenerationPlanSummary(variants = variants)
                    }
                    ImageGenerationProgress(
                        tool = tool,
                        aspectRatio = aspectRatio,
                        loading = false,
                    )
                }
                ImageGenerationToolPhase.COMPLETED -> {
                    if (tool.toolName == IMAGE_GENERATION_PLAN_TOOL_NAME) {
                        ImageGenerationPlanSummary(variants = variants)
                    }
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
    }
}

@Composable
private fun ImageGenerationProgress(
    tool: UIMessagePart.Tool,
    aspectRatio: Float,
    loading: Boolean,
) {
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
}

@Composable
private fun ImageGenerationPlanPreparing() {
    Text(
        text = "正在生成方案…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun ImageGenerationPlanSummary(variants: List<ImageGenerationVariant>) {
    val summary = variants.joinToString("、") { it.label }
    Text(
        text = if (summary.isBlank()) "已确认方案，正在生成图片" else "已确认：$summary",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ImageGenerationCancelled() {
    Text(
        text = "已取消生成",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun ImageGenerationBalancePrompt(onTopUp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.imggen_error_insufficient_balance_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = stringResource(R.string.imggen_error_insufficient_balance_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Button(onClick = onTopUp) {
            Text(stringResource(R.string.imggen_error_insufficient_balance_action))
        }
    }
}

@Composable
private fun ImageGenerationPlanCard(
    variants: List<ImageGenerationVariant>,
    onApprove: (List<ImageGenerationVariant>) -> Unit,
    onDeny: (reason: String) -> Unit,
) {
    var editedVariants by remember(variants) { mutableStateOf(variants) }
    var selectedIndices by remember(variants) {
        mutableStateOf(initialImageGenerationSelection(variants.size))
    }
    var editingIndex by remember(variants) { mutableStateOf<Int?>(null) }

    editingIndex?.let { index ->
        ImageGenerationPlanEditor(
            variant = editedVariants[index],
            onBack = { editingIndex = null },
            onSave = { prompt, size ->
                editedVariants = editedVariants.mapIndexed { variantIndex, current ->
                    if (variantIndex == index) current.copy(prompt = prompt, size = size) else current
                }
                editingIndex = null
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "为你准备了 ${variants.size} 个画面方向",
            style = MaterialTheme.typography.titleSmall,
        )
        editedVariants.forEachIndexed { index, variant ->
            val checked = index in selectedIndices
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Checkbox,
                        onValueChange = { selected ->
                            selectedIndices = updateImageGenerationSelection(
                                selectedIndices = selectedIndices,
                                index = index,
                                selected = selected,
                            )
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = variant.label, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = variant.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = variant.size.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { editingIndex = index }) {
                    Icon(
                        imageVector = HugeIcons.PencilEdit01,
                        contentDescription = "编辑 ${variant.label}",
                    )
                }
            }
        }
        Text(
            text = "确认生成后才会消耗额度",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onDeny("User cancelled image generation") }) { Text("取消") }
            Button(
                onClick = {
                    onApprove(
                        editedVariants.filterIndexed { index, _ -> index in selectedIndices }
                    )
                },
                enabled = selectedIndices.isNotEmpty(),
            ) { Text("生成已选 ${selectedIndices.size} 张") }
        }
    }
}

@Composable
private fun ImageGenerationPlanEditor(
    variant: ImageGenerationVariant,
    onBack: () -> Unit,
    onSave: (prompt: String, size: String) -> Unit,
) {
    var prompt by remember(variant) { mutableStateOf(variant.prompt) }
    var size by remember(variant) { mutableStateOf(variant.size) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "编辑画面", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("画面描述") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
        )
        Text(text = "画面比例", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ImageGenSize.SQUARE_1024.value to "方图",
                ImageGenSize.LANDSCAPE_1536.value to "横图",
                ImageGenSize.PORTRAIT_1536.value to "竖图",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = size == value,
                    onClick = { size = value },
                    label = { Text(label) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Button(
                onClick = { onSave(prompt.trim(), size) },
                enabled = prompt.isNotBlank(),
            ) { Text("保存") }
        }
    }
}

internal fun buildSelectedImageGenerationInput(
    original: JsonObject,
    variants: List<ImageGenerationVariant>,
): String {
    return buildJsonObject {
        original.forEach { (key, value) ->
            if (key != "variants") put(key, value)
        }
        put(
            "variants",
            JsonArray(
                variants.map { variant ->
                    buildJsonObject {
                        put("label", JsonPrimitive(variant.label))
                        put("prompt", JsonPrimitive(variant.prompt))
                        put("size", JsonPrimitive(variant.size))
                    }
                }
            )
        )
    }.toString()
}

private fun String.displayName(): String = when (this) {
    ImageGenSize.SQUARE_1024.value -> "方图"
    ImageGenSize.LANDSCAPE_1536.value -> "横图"
    ImageGenSize.PORTRAIT_1536.value -> "竖图"
    else -> "自动比例"
}

/**
 * 从工具入参的 size 推断占位画布比例, 让占位框和最终图片尺寸一致, 避免出图时跳动
 */
private fun UIMessagePart.Tool.imageAspectRatio(): Float {
    val args = inputAsJson().jsonObject
    val size = args.getStringContent("size")
        ?: runCatching {
            parseImageGenerationVariants(args).firstOrNull()?.size
        }.getOrNull()
        ?: return 1f
    val (width, height) = size.split("x").takeIf { it.size == 2 } ?: return 1f
    val w = width.trim().toFloatOrNull() ?: return 1f
    val h = height.trim().toFloatOrNull() ?: return 1f
    return if (w > 0f && h > 0f) w / h else 1f
}
