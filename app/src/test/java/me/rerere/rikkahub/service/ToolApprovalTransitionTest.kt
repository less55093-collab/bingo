package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class ToolApprovalTransitionTest {
    private fun tool(id: String = "call") = UIMessagePart.Tool(
        toolCallId = id, toolName = "plan_image_generation", input = "{}",
        approvalState = ToolApprovalState.Pending,
    )
    private fun assistant(vararg tools: UIMessagePart.Tool) = UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList())
    private fun conversation(vararg messages: UIMessage) = Conversation.ofId(
        Uuid.random(), messages = messages.map { it.toMessageNode() },
    )

    @Test fun `one approval consumes pending state before duplicate callback can act`() {
        val original = conversation(UIMessage.user("生成"), assistant(tool()))
        val accepted = requireNotNull(original.transitionPendingTailTool("call", ToolApprovalState.Approved, "{\"variants\":[]}"))
        assertEquals("{\"variants\":[]}", accepted.replacement.input)
        assertTrue(accepted.canContinue(accepted.conversation))
        assertNull(accepted.conversation.transitionPendingTailTool("call", ToolApprovalState.Approved))
        assertNull(accepted.conversation.transitionPendingTailTool("call", ToolApprovalState.Denied("late")))
    }

    @Test fun `simultaneous approval callbacks allow exactly one transition`() {
        val lock = Any()
        var current = conversation(UIMessage.user("生成"), assistant(tool()))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val callbacks = (0..1).map {
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(2, TimeUnit.SECONDS))
                    synchronized(lock) {
                        current.transitionPendingTailTool("call", ToolApprovalState.Approved)?.also { transition ->
                            current = transition.conversation
                        } != null
                    }
                })
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, callbacks.count { it.get(2, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }

    @Test fun `historical selected and unselected branch tools cannot be approved`() {
        val old = assistant(tool())
        assertNull(conversation(old, UIMessage.user("next")).transitionPendingTailTool("call", ToolApprovalState.Approved))
        val selected = assistant(tool("current"))
        val history = conversation().copy(messageNodes = listOf(MessageNode(messages = listOf(old, selected), selectIndex = 1)))
        assertNull(history.transitionPendingTailTool("call", ToolApprovalState.Approved))
        val changed = requireNotNull(history.transitionPendingTailTool("current", ToolApprovalState.Approved))
        assertEquals(old, changed.conversation.messageNodes.single().messages.first())
    }

    @Test fun `started executed auto and duplicate id tools cannot consume approval`() {
        listOf(
            tool().copy(executionStarted = true),
            tool().copy(output = listOf(UIMessagePart.Text("done"))),
            tool().copy(approvalState = ToolApprovalState.Auto),
        ).forEach { assertNull(conversation(assistant(it)).transitionPendingTailTool("call", ToolApprovalState.Approved)) }
        assertNull(conversation(assistant(tool(), tool())).transitionPendingTailTool("call", ToolApprovalState.Approved))
    }

    @Test fun `approving a second tool preserves the first accepted choice`() {
        val first = requireNotNull(conversation(assistant(tool("a"), tool("b")))
            .transitionPendingTailTool("a", ToolApprovalState.Approved))
        val second = requireNotNull(first.conversation.transitionPendingTailTool("b", ToolApprovalState.Denied("no")))
        assertTrue(first.canContinue(second.conversation))
        assertTrue(second.canContinue(second.conversation))
        assertTrue(second.conversation.currentMessages.last().getTools().none { it.isPending })
    }

    @Test fun `delayed pending stream snapshot cannot reopen consumed approval`() {
        val planned = assistant(tool("a"), tool("b"))
        val transition = requireNotNull(conversation(planned).transitionPendingTailTool("a", ToolApprovalState.Approved, "{\"x\":1}"))
        val repaired = planned.withAcceptedToolApprovals(transition.conversation.currentMessages.last())
        assertEquals(transition.replacement, repaired.getTools().first())
        assertTrue(repaired.getTools()[1].isPending)
        assertNull(conversation(repaired).transitionPendingTailTool("a", ToolApprovalState.Approved))
        assertEquals(planned.parts, planned.copy(id = Uuid.random()).withAcceptedToolApprovals(repaired).parts)
    }

    @Test fun `continuation stops when message branch changes or tool finishes`() {
        val transition = requireNotNull(conversation(assistant(tool())).transitionPendingTailTool("call", ToolApprovalState.Approved))
        assertFalse(transition.canContinue(conversation(UIMessage.user("new"))))
        val finished = transition.conversation.currentMessages.last().copy(parts = listOf(
            transition.replacement.copy(output = listOf(UIMessagePart.Image("file:///completed.png"))),
        ))
        assertFalse(transition.canContinue(conversation(finished)))
    }

    @Test fun `message identity rejects an old callback even when upstream reuses the call id`() {
        val old = assistant(tool())
        val current = assistant(tool())
        val history = conversation(old, UIMessage.user("new image"), current)
        assertNull(history.transitionPendingTailTool("call", ToolApprovalState.Approved,
            expectedMessageId = old.id))
        assertNull(conversation(current).transitionPendingTailTool("call", ToolApprovalState.Approved,
            expectedMessageId = old.id))
        assertNotNull(history.transitionPendingTailTool("call", ToolApprovalState.Approved,
            expectedMessageId = current.id))
        assertNull(history.transitionPendingTailTool("call", ToolApprovalState.Approved))
    }
}
