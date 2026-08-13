package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationProtectionProgressTest {
    @Test
    fun `only the current notification conversation can publish progress`() = runTest {
        val controller = ProgressController()
        val manager = GenerationProtectionManager.forTesting(controller)
        val first = manager.begin(GenerationKind.CHAT, "conversation-1")
        val second = manager.begin(GenerationKind.CHAT, "conversation-2")

        manager.publishProgress(first.runToken, "conversation-1")
        manager.publishProgress(second.runToken, "conversation-2")

        assertEquals(listOf(Triple(first.runToken, 2L, "conversation-2")), controller.progressUpdates)

        second.close()
        manager.publishProgress(first.runToken, "conversation-2")
        manager.publishProgress(first.runToken, "conversation-1")

        assertEquals(
            listOf(
                Triple(first.runToken, 2L, "conversation-2"),
                Triple(first.runToken, 3L, "conversation-1"),
            ),
            controller.progressUpdates,
        )
        first.close()
    }

    @Test
    fun `progress from an older run token cannot update a replacement run`() = runTest {
        val controller = ProgressController()
        val manager = GenerationProtectionManager.forTesting(controller)
        val first = manager.begin(GenerationKind.CHAT, "conversation")
        first.close()
        val replacement = manager.begin(GenerationKind.CHAT, "conversation")

        manager.publishProgress(first.runToken, "conversation")
        manager.publishProgress(replacement.runToken, "conversation")

        assertEquals(
            listOf(Triple(replacement.runToken, 1L, "conversation")),
            controller.progressUpdates,
        )
        replacement.close()
    }

    private fun GenerationProtectionManager.publishProgress(runToken: Long, conversationId: String) {
        updateProgress(
            runToken = runToken,
            conversationId = conversationId,
            title = "title",
            status = "status",
            content = "content",
            chipText = "chip",
        )
    }

    private class ProgressController : GenerationForegroundServiceController {
        private val mutableState = MutableStateFlow<GenerationForegroundServiceState>(
            GenerationForegroundServiceState.Idle,
        )
        override val state: StateFlow<GenerationForegroundServiceState> = mutableState
        val progressUpdates = mutableListOf<Triple<Long, Long, String>>()

        override fun start(request: GenerationServiceRequest): Result<Unit> {
            mutableState.value = GenerationForegroundServiceState.Starting(request.runToken)
            return Result.success(Unit)
        }

        override fun stop(runToken: Long) {
            if (mutableState.value.runTokenOrNull() == runToken) {
                mutableState.value = GenerationForegroundServiceState.Stopped(runToken)
            }
        }

        override fun updateConversation(
            runToken: Long,
            conversationRevision: Long,
            conversationId: String?,
        ) = Unit

        override fun updateProgress(
            runToken: Long,
            conversationRevision: Long,
            conversationId: String,
            title: String,
            status: String,
            content: String,
            chipText: String,
        ) {
            progressUpdates += Triple(runToken, conversationRevision, conversationId)
        }

        override fun reportFailure(runToken: Long, message: String, cause: Throwable?) {
            mutableState.value = GenerationForegroundServiceState.Failed(runToken, message, cause)
        }

        override fun canActivate(runToken: Long): Boolean =
            mutableState.value is GenerationForegroundServiceState.Starting &&
                mutableState.value.runTokenOrNull() == runToken
    }
}

private fun GenerationForegroundServiceState.runTokenOrNull(): Long? = when (this) {
    GenerationForegroundServiceState.Idle -> null
    is GenerationForegroundServiceState.Starting -> runToken
    is GenerationForegroundServiceState.Active -> runToken
    is GenerationForegroundServiceState.Failed -> runToken
    is GenerationForegroundServiceState.Stopped -> runToken
}
