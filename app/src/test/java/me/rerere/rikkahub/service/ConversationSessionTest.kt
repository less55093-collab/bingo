package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `conversation initialization is serialized and runs once`() = runTest {
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(
                id = Uuid.random(),
                assistantId = Uuid.random(),
            ),
            scope = this,
            onIdle = {},
        )
        var initializationCount = 0

        List(16) {
            async {
                session.initializeOnce {
                    initializationCount += 1
                }
            }
        }.awaitAll()

        assertEquals(1, initializationCount)
        session.cleanup()
    }

    @Test
    fun `predecessor completion cannot clear replacement generation job`() {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val session = ConversationSession(
            id = Uuid.random(),
            initial = Conversation.ofId(
                id = Uuid.random(),
                assistantId = Uuid.random(),
            ),
            scope = scope,
            onIdle = {},
        )
        val predecessor = Job()
        val replacement = Job()

        session.setJob(predecessor)
        session.setJob(replacement)
        predecessor.complete()

        assertSame(replacement, session.getJob())
        session.cleanup()
        scopeJob.cancel()
    }
}
