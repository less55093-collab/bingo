package me.rerere.rikkahub.data.repository

import java.time.Instant
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationRecoveryTest {
    private val recoveredAt = Instant.parse("2026-08-12T00:00:00Z")

    @Test
    fun `unfinished selected assistant is marked interrupted and keeps its identity and content`() {
        val message = assistant("partial")
        val conversation = conversation(message.toMessageNode())

        val recovered = conversation.recoverSelectedGeneration(recoveredAt)

        assertNotNull(recovered)
        val recoveredMessage = recovered!!.currentMessages.single()
        assertEquals(message.id, recoveredMessage.id)
        assertEquals("partial", recoveredMessage.toText())
        assertNotNull(recoveredMessage.finishedAt)
        assertTrue(recoveredMessage.annotations.contains(UIMessageAnnotation.GenerationInterrupted))
        assertEquals(recoveredAt, recovered.updateAt)
    }

    @Test
    fun `pending approval is not marked interrupted`() {
        val message = assistant(
            parts = listOf(tool(ToolApprovalState.Pending)),
        )

        assertNull(conversation(message.toMessageNode()).recoverSelectedGeneration(recoveredAt))
    }

    @Test
    fun `completed assistant is not marked interrupted`() {
        val message = assistant("done").copy(finishedAt = LocalDateTime(2026, 8, 12, 8, 0))

        assertNull(conversation(message.toMessageNode()).recoverSelectedGeneration(recoveredAt))
    }

    @Test
    fun `user-stopped terminal assistant is not marked interrupted`() {
        val message = assistant("stopped").copy(finishedAt = LocalDateTime(2026, 8, 12, 8, 0))

        val recovered = conversation(message.toMessageNode()).recoverSelectedGeneration(recoveredAt)

        assertNull(recovered)
        assertTrue(message.annotations.none { it is UIMessageAnnotation.GenerationInterrupted })
    }

    @Test
    fun `unselected unfinished branch is preserved when selected branch completed`() {
        val unfinished = assistant("old partial")
        val selected = assistant("selected done").copy(finishedAt = LocalDateTime(2026, 8, 12, 8, 0))
        val node = MessageNode(messages = listOf(unfinished, selected), selectIndex = 1)
        val conversation = conversation(node)

        val recovered = conversation.recoverSelectedGeneration(recoveredAt)

        assertNull(recovered)
        assertSame(unfinished, conversation.messageNodes.single().messages.first())
        assertTrue(unfinished.annotations.none { it is UIMessageAnnotation.GenerationInterrupted })
    }

    @Test
    fun `non-pending unexecuted tool is marked interrupted even when message has finished timestamp`() {
        val message = assistant(
            parts = listOf(tool(ToolApprovalState.Approved)),
        ).copy(finishedAt = LocalDateTime(2026, 8, 12, 8, 0))

        val recovered = conversation(message.toMessageNode()).recoverSelectedGeneration(recoveredAt)

        assertNotNull(recovered)
        val recoveredMessage = recovered!!.currentMessages.single()
        assertTrue(recoveredMessage.annotations.contains(UIMessageAnnotation.GenerationInterrupted))
        assertEquals(message.parts, recoveredMessage.parts)
    }

    private fun conversation(vararg nodes: MessageNode) = Conversation.ofId(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messages = nodes.toList(),
    )

    private fun assistant(text: String) = assistant(parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(parts: List<UIMessagePart>) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts,
    )

    private fun tool(approvalState: ToolApprovalState) = UIMessagePart.Tool(
        toolCallId = "tool-1",
        toolName = "test_tool",
        input = "{}",
        output = emptyList(),
        approvalState = approvalState,
    )
}
