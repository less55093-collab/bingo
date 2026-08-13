package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import java.time.Instant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationStreamCheckpointTest {
    @Test
    fun `first chunk saves immediately then throttles until text delta threshold`() = runBlocking {
        var now = 0L
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { now },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )

        checkpoint.offer(conversationId, 1, conversation(conversationId, "a"))
        checkpoint.offer(conversationId, 1, conversation(conversationId, "ab"))
        assertEquals(1, saved.size)

        checkpoint.offer(conversationId, 1, conversation(conversationId, "x".repeat(4 * 1024 + 1)))
        assertEquals(2, saved.size)

        now = 1_000L
        checkpoint.offer(conversationId, 1, conversation(conversationId, "x".repeat(4 * 1024 + 2)))
        assertEquals(3, saved.size)
    }

    @Test
    fun `text delta threshold counts utf8 bytes`() = runBlocking {
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )

        checkpoint.offer(conversationId, 1, conversation(conversationId, "a"))
        checkpoint.offer(conversationId, 1, conversation(conversationId, "中".repeat(1366)))

        assertEquals(2, saved.size)
    }

    @Test
    fun `reasoning delta also triggers payload checkpoint`() = runBlocking {
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )

        checkpoint.offer(conversationId, 1, conversation(conversationId, ""))
        checkpoint.offer(
            conversationId,
            1,
            Conversation.ofId(
                id = conversationId,
                assistantId = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Reasoning("r".repeat(4 * 1024 + 1))),
                    ).toMessageNode(),
                ),
            ),
        )

        assertEquals(1, saved.size)
    }

    @Test
    fun `flush persists the newest snapshot even inside throttle window`() = runBlocking {
        var now = 0L
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { now },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )
        checkpoint.offer(conversationId, 1, conversation(conversationId, "first"))
        checkpoint.offer(conversationId, 1, conversation(conversationId, "newest"))

        checkpoint.flush(conversationId, generationToken = 1)

        assertEquals(2, saved.size)
        assertEquals("newest", saved.last().currentMessages.single().toText())
    }

    @Test
    fun `checkpoint refreshes the persisted conversation update time`() = runBlocking {
        val checkpointTime = 1_234L
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { checkpointTime },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, "").copy(updateAt = Instant.EPOCH),
        )

        checkpoint.offer(
            conversationId,
            generationToken = 1,
            conversation = conversation(conversationId, "partial").copy(updateAt = Instant.EPOCH),
        )

        assertEquals(Instant.ofEpochMilli(checkpointTime), saved.single().updateAt)
    }

    @Test
    fun `old generation cannot overwrite newer snapshot or final save`() = runBlocking {
        val snapshots = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { snapshots += it.currentMessages.single().toText() },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )
        checkpoint.offer(conversationId, 1, conversation(conversationId, "old"))
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 2,
            initialConversation = conversation(conversationId, ""),
        )
        checkpoint.offer(conversationId, 1, conversation(conversationId, "stale"))
        checkpoint.offer(conversationId, 2, conversation(conversationId, "new"))

        val oldPersisted = checkpoint.saveFinal(
            conversationId = conversationId,
            generationToken = 1,
            conversation = conversation(conversationId, "old-final"),
        ) { finals += it.currentMessages.single().toText() }
        val newPersisted = checkpoint.saveFinal(
            conversationId = conversationId,
            generationToken = 2,
            conversation = conversation(conversationId, "new-final"),
        ) { finals += it.currentMessages.single().toText() }

        assertFalse(oldPersisted)
        assertTrue(newPersisted)
        assertEquals(listOf("old", "new", "new-final"), snapshots)
        assertEquals(listOf("new-final"), finals)
    }

    @Test
    fun `empty stream updates do not consume the first payload checkpoint`() = runBlocking {
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )

        checkpoint.offer(conversationId, 1, conversation(conversationId, ""))
        assertTrue(saved.isEmpty())

        checkpoint.offer(conversationId, 1, conversation(conversationId, "first payload"))
        assertEquals(1, saved.size)
    }

    @Test
    fun `tool header and metadata count as first stream payload`() = runBlocking {
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = conversation(conversationId, ""),
        )

        val toolConversation = Conversation.ofId(
            id = conversationId,
            assistantId = Uuid.random(),
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call_1",
                            toolName = "search",
                            input = "",
                        ),
                    ),
                ).toMessageNode(),
            ),
        )

        checkpoint.offer(conversationId, 1, toolConversation)
        assertEquals(1, saved.size)
    }

    @Test
    fun `short replacement reply checkpoints even when it shrinks the prior branch payload`() = runBlocking {
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        val oldAssistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("old response ".repeat(512))),
        )
        val initial = Conversation.ofId(
            id = conversationId,
            assistantId = Uuid.random(),
            messages = listOf(oldAssistant.toMessageNode()),
        )
        checkpoint.start(
            conversationId = conversationId,
            generationToken = 1,
            initialConversation = initial,
        )

        val replacement = initial.updateCurrentMessages(
            listOf(
                oldAssistant,
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("new")),
                ),
            ),
        )
        checkpoint.offer(conversationId, 1, replacement)

        assertEquals(1, saved.size)
        assertEquals("new", saved.single().currentMessages.last().toText())
    }

    @Test
    fun `historical assistant regeneration checkpoints only the replacement node`() = runBlocking {
        val saved = mutableListOf<Conversation>()
        val checkpoint = ConversationStreamCheckpoint(
            nowMillis = { 0L },
            saveSnapshot = { saved += it },
        )
        val conversationId = Uuid.random()
        val oldReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old")))
        val historicalNode = oldReply.toMessageNode()
        val tailReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("unrelated tail")))
        val initial = Conversation.ofId(
            id = conversationId,
            assistantId = Uuid.random(),
            messages = listOf(historicalNode, tailReply.toMessageNode()),
        )
        val target = StreamGenerationTarget(historicalNode.id, oldReply.id)
        checkpoint.start(conversationId, 1, initial, target)

        val replacement = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("replacement")))
        val regenerated = initial.copy(
            messageNodes = listOf(
                historicalNode.copy(messages = listOf(oldReply, replacement), selectIndex = 1),
                tailReply.toMessageNode(),
            ),
        )
        target.observe(regenerated)
        checkpoint.offer(conversationId, 1, regenerated)

        assertEquals(1, saved.size)
        assertEquals("replacement", saved.single().generationMessage(target)?.toText())
        assertEquals("unrelated tail", saved.single().currentMessages.last().toText())
    }

    @Test
    fun `historical assistant interruption and stop leave unrelated tail untouched`() {
        val oldReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("old")))
        val historicalNode = oldReply.toMessageNode()
        val replacement = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("partial")))
        val tailReply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("unrelated tail")))
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(
                historicalNode.copy(messages = listOf(oldReply, replacement), selectIndex = 1),
                tailReply.toMessageNode(),
            ),
        )
        val target = StreamGenerationTarget(historicalNode.id, oldReply.id).also { it.observe(conversation) }

        val interrupted = conversation.markGenerationInterrupted(target, Instant.EPOCH)
        assertEquals(oldReply, interrupted.messageNodes.first().messages.first())
        assertTrue(
            interrupted.generationMessage(target)?.annotations
                ?.contains(UIMessageAnnotation.GenerationInterrupted) == true,
        )
        assertTrue(interrupted.currentMessages.last().finishedAt == null)
        assertTrue(
            interrupted.currentMessages.last().annotations
                .none { it is UIMessageAnnotation.GenerationInterrupted },
        )

        val stopped = conversation.finishGenerationByUser(
            target = target,
            cancelTool = { it },
            updateAt = Instant.EPOCH,
        )
        assertTrue(stopped.generationMessage(target)?.finishedAt != null)
        assertTrue(stopped.currentMessages.last().finishedAt == null)
        assertTrue(
            stopped.currentMessages.last().annotations
                .none { it is UIMessageAnnotation.GenerationInterrupted },
        )
    }

    @Test
    fun `successful stream finalization marks its assistant terminal for process recovery`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("done")),
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(message.toMessageNode()),
        )

        val finalized = conversation.finishGenerationReasoning(target = null, updateAt = Instant.EPOCH)

        assertTrue(finalized.currentMessages.single().finishedAt != null)
        assertTrue(finalized.currentMessages.single().annotations.isEmpty())
    }

    @Test
    fun `successful stream finalization leaves earlier assistant history untouched`() {
        val earlier = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("earlier")),
        )
        val current = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("current")),
        )
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(earlier.toMessageNode(), current.toMessageNode()),
        )

        val finalized = conversation.finishGenerationReasoning(target = null, updateAt = Instant.EPOCH)

        assertTrue(finalized.messageNodes.first().currentMessage.finishedAt == null)
        assertTrue(finalized.currentMessages.last().finishedAt != null)
    }

    private fun conversation(id: Uuid, text: String): Conversation = Conversation.ofId(
        id = id,
        assistantId = Uuid.random(),
        messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(text)),
            ).toMessageNode(),
        ),
    )
}
