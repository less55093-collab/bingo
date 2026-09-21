package me.rerere.rikkahub.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ImageMessageDeliveryTest {
    private fun pending(id: String) = UIMessagePart.Text("""{"status":"waiting_for_recovery","request_id":"$id"}""")
    private fun message() = UIMessage(role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(toolCallId = "call", toolName = "plan_image_generation",
            input = "{}", output = listOf(pending("first"), pending("second")))),
        annotations = listOf(UIMessageAnnotation.GenerationInterrupted))
    private fun receipt(id: String, url: String) = UIMessageAnnotation.ImageDelivery("call", id, listOf(url))

    @Test fun `partial result keeps other batch item pending and repeated receipt does not duplicate`() {
        val first = receipt("first", "file:///images/one.png")
        val partial = message().withImageDeliveries(listOf(first)).withImageDeliveries(listOf(first))
        val tool = partial.getTools().single()
        assertEquals(1, tool.output.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(tool.output.contains(pending("second")))
        assertFalse(tool.output.contains(pending("first")))
        assertFalse(UIMessageAnnotation.GenerationInterrupted in partial.annotations)
    }

    @Test fun `stale stream snapshot receives both durable results without regenerating`() {
        val completed = message().withImageDeliveries(listOf(receipt("first", "file:///1.png")))
            .withImageDeliveries(listOf(receipt("second", "file:///2.png")))
        val stale = message().withImageDeliveries(completed.annotations.filterIsInstance<UIMessageAnnotation.ImageDelivery>())
        assertEquals(2, stale.getTools().single().output.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue(stale.getTools().single().output.filterIsInstance<UIMessagePart.Text>().all {
            Json.parseToJsonElement(it.text).jsonObject["status"]?.jsonPrimitive?.content == "completed"
        })
    }

    @Test fun `receipt cannot change another tool or message content`() {
        val original = message().copy(parts = listOf(UIMessagePart.Text("keep")) + message().parts)
        val updated = original.withImageDeliveries(listOf(UIMessageAnnotation.ImageDelivery("different", "other", listOf("file:///x"))))
        assertEquals(original.parts, updated.parts)
    }

    @Test fun `recovered success supersedes failure and late failure cannot remove the image`() {
        val failure = UIMessageAnnotation.ImageDelivery("call", "first", emptyList(), "interrupted")
        val success = receipt("first", "file:///images/recovered.png")
        val recovered = message().withImageDeliveries(listOf(failure))
            .withImageDeliveries(listOf(success)).withImageDeliveries(listOf(failure))
        val firstStatus = recovered.getTools().single().output.filterIsInstance<UIMessagePart.Text>()
            .map { Json.parseToJsonElement(it.text).jsonObject }
            .single { it["request_id"]?.jsonPrimitive?.content == "first" }
        assertEquals("completed", firstStatus["status"]?.jsonPrimitive?.content)
        assertEquals(listOf("file:///images/recovered.png"),
            recovered.getTools().single().output.filterIsInstance<UIMessagePart.Image>().map { it.url })
        assertTrue(recovered.getTools().single().output.contains(pending("second")))
    }

    @Test fun `stopped waiting becomes completed when the saved server result arrives`() {
        val cancelled = message().copy(parts = listOf(message().getTools().single().copy(
            approvalState = ToolApprovalState.Denied("Generation cancelled by user"),
            executionStarted = true,
            output = listOf(UIMessagePart.Text("""{"status":"cancelled"}""")),
        )))
        val recovered = cancelled.withImageDeliveries(listOf(receipt("first", "file:///images/one.png")))
        val tool = recovered.getTools().single()
        assertEquals(ToolApprovalState.Approved, tool.approvalState)
        assertTrue(tool.isExecuted)
        assertFalse(tool.canResumeExecution)
        assertEquals("file:///images/one.png", tool.output.filterIsInstance<UIMessagePart.Image>().single().url)
        assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().none { it.text.contains("cancelled") })
    }

    @Test fun `child identities survive restart but explicit new operation creates a new task`() {
        val operation = ImageOperationContext("conversation", "message", "call")
        assertEquals(operation.requestId, operation.copy().requestId)
        assertNotEquals(operation.requestId, operation.copy(itemIndex = 1).requestId)
        assertNotEquals(operation.requestId, operation.copy(messageId = "new-message").requestId)
    }

    @Test fun `delivery acknowledgement survives new store instance and isolates accounts`() {
        val root = Files.createTempDirectory("image-delivery").toFile()
        try {
            ImageDeliveryReceiptStore(root).record(1L, "bingo_task")
            val reopened = ImageDeliveryReceiptStore(root)
            assertTrue(reopened.contains(1L, "bingo_task"))
            assertFalse(reopened.contains(2L, "bingo_task"))
            assertFalse(reopened.contains(1L, "another"))
        } finally { root.deleteRecursively() }
    }
}
