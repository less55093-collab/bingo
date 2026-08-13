package me.rerere.rikkahub.service

import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationLeaseTest {
    @Test
    fun `standalone image lease begins before waiting for predecessor cleanup`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)
        val predecessor = Job()

        val next = async(start = CoroutineStart.UNDISPATCHED) {
            withStandaloneImageGenerationLease(manager, predecessor) { error("must wait") }
        }

        assertEquals(1, controller.starts.size)
        assertFalse(next.isCompleted)

        next.cancel()
        next.join()

        assertEquals(listOf(1L), controller.stops)
        assertFalse(manager.hasActiveLeases())
    }

    @Test
    fun `provisional image lease keeps protection alive while predecessor releases`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)
        val previousLease = async { manager.acquire(GenerationKind.IMAGE) }
        testScheduler.runCurrent()
        controller.active(1)
        val acquiredPreviousLease = previousLease.await()
        val predecessor = Job()
        var enteredBlock = false

        val next = async(start = CoroutineStart.UNDISPATCHED) {
            withStandaloneImageGenerationLease(manager, predecessor) {
                enteredBlock = true
            }
        }

        assertEquals(1, controller.starts.size)
        acquiredPreviousLease.close()
        assertTrue(controller.stops.isEmpty())
        assertFalse(enteredBlock)

        predecessor.complete()
        next.await()

        assertTrue(enteredBlock)
        assertEquals(listOf(1L), controller.stops)
        assertFalse(manager.hasActiveLeases())
    }

    @Test
    fun `standalone image work reuses provisional lease after activation`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)
        var enteredBlock = false

        val generation = async(start = CoroutineStart.UNDISPATCHED) {
            withStandaloneImageGenerationLease(manager, previous = null) {
                enteredBlock = true
            }
        }

        assertEquals(1, controller.starts.size)
        controller.active(1)
        generation.await()

        assertTrue(enteredBlock)
        assertEquals(1, controller.starts.size)
        assertEquals(listOf(1L), controller.stops)
    }

    @Test
    fun `rejected standalone image protection never enters request block`() = runTest {
        val controller = FakeController().apply {
            startError = SecurityException("background start denied")
        }
        val manager = GenerationProtectionManager.forTesting(controller)
        var enteredBlock = false

        val result = runCatching {
            withStandaloneImageGenerationLease(manager, previous = null) {
                enteredBlock = true
            }
        }

        assertTrue(result.exceptionOrNull() is GenerationProtectionException)
        assertFalse(enteredBlock)
        assertTrue(controller.stops.isEmpty())
        assertFalse(manager.hasActiveLeases())
    }

    private class FakeController : GenerationForegroundServiceController {
        private val mutableState = MutableStateFlow<GenerationForegroundServiceState>(
            GenerationForegroundServiceState.Idle,
        )
        override val state: StateFlow<GenerationForegroundServiceState> = mutableState
        val starts = Collections.synchronizedList(mutableListOf<GenerationServiceRequest>())
        val stops = Collections.synchronizedList(mutableListOf<Long>())
        var startError: Throwable? = null

        override fun start(request: GenerationServiceRequest): Result<Unit> {
            starts += request
            mutableState.value = GenerationForegroundServiceState.Starting(request.runToken)
            return startError?.let(Result.Companion::failure) ?: Result.success(Unit)
        }

        override fun stop(runToken: Long) {
            stops += runToken
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
        ) = Unit

        override fun reportFailure(runToken: Long, message: String, cause: Throwable?) {
            mutableState.value = GenerationForegroundServiceState.Failed(runToken, message, cause)
        }

        override fun canActivate(runToken: Long): Boolean =
            mutableState.value is GenerationForegroundServiceState.Starting &&
                mutableState.value.runTokenOrNull() == runToken

        fun active(runToken: Long) {
            mutableState.value = GenerationForegroundServiceState.Active(runToken)
        }
    }
}

private fun GenerationForegroundServiceState.runTokenOrNull(): Long? = when (this) {
    GenerationForegroundServiceState.Idle -> null
    is GenerationForegroundServiceState.Starting -> runToken
    is GenerationForegroundServiceState.Active -> runToken
    is GenerationForegroundServiceState.Failed -> runToken
    is GenerationForegroundServiceState.Stopped -> runToken
}
