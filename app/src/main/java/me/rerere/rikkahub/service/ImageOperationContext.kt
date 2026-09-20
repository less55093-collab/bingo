package me.rerere.rikkahub.service

import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Identity belongs to the saved user operation, never to a network attempt. */
data class ImageOperationContext(
    val conversationId: String,
    val messageId: String,
    val toolCallId: String,
    val itemIndex: Int = 0,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ImageOperationContext>

    val operationId: String get() = stableId("$conversationId:$messageId")
    val itemId: String get() = "$toolCallId:$itemIndex"
    val requestId: String get() = "bingo_" + stableId("$operationId:$itemId")

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
}
