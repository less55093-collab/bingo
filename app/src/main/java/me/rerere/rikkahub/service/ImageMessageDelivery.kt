package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState

/** Replaying a receipt is idempotent, including after a stale stream or Room write retry. */
internal fun UIMessage.withImageDeliveries(receipts: List<UIMessageAnnotation.ImageDelivery>): UIMessage {
    val deliveries = (annotations.filterIsInstance<UIMessageAnnotation.ImageDelivery>() + receipts)
        .groupBy { it.requestId }.values.map { versions ->
            versions.lastOrNull { it.error == null && it.imageUrls.isNotEmpty() } ?: versions.last()
        }
    if (deliveries.isEmpty()) return this
    return copy(
        annotations = annotations.filterNot {
            it is UIMessageAnnotation.ImageDelivery || it is UIMessageAnnotation.GenerationFailure ||
                it is UIMessageAnnotation.GenerationInterrupted
        } + deliveries,
        parts = parts.map { part ->
            if (part !is UIMessagePart.Tool) return@map part
            val matching = deliveries.filter { it.toolCallId == part.toolCallId }
            if (matching.isEmpty()) return@map part
            val requestIds = matching.map { it.requestId }.toSet()
            val images = (part.output.filterIsInstance<UIMessagePart.Image>() +
                matching.flatMap { it.imageUrls }.map { UIMessagePart.Image(it) }).distinctBy { it.url }
            val remainingStatuses = part.output.filterIsInstance<UIMessagePart.Text>().filter { text ->
                val result = runCatching { Json.parseToJsonElement(text.text) as? JsonObject }.getOrNull()
                val id = (result?.get("request_id") as? JsonPrimitive)?.contentOrNull
                id != null && id !in requestIds
            }
            val statuses = matching.map { receipt ->
                UIMessagePart.Text(buildJsonObject {
                    put("request_id", receipt.requestId)
                    put("status", if (receipt.error == null) "completed" else "failed")
                    put("message", receipt.error ?: "图片已保存")
                }.toString())
            }
            part.copy(
                executionStarted = true,
                // Stopping local waiting cannot override the server's confirmed delivery.
                approvalState = ToolApprovalState.Approved,
                output = images + remainingStatuses + statuses,
            )
        },
    )
}
