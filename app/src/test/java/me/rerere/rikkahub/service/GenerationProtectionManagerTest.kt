package me.rerere.rikkahub.service

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationProtectionManagerTest {
    @Test
    fun `begin starts protection before the first suspension`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val provisional = manager.begin(GenerationKind.CHAT, "conversation")

        assertEquals(1, controller.starts.size)
        assertEquals(1L, provisional.runToken)

        val lease = async { manager.awaitActive(provisional) }
        testScheduler.advanceTimeBy(30_000)
        assertTrue(!lease.isCompleted)

        controller.active(1)
        assertEquals(1L, lease.await().runToken)
    }

    @Test
    fun `nested chat and image leases share one foreground service run`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val chat = async { manager.acquire(GenerationKind.CHAT, "conversation") }
        testScheduler.runCurrent()
        controller.active(1)
        testScheduler.runCurrent()
        val chatLease = chat.await()
        val imageLease = manager.acquire(GenerationKind.IMAGE)

        assertEquals(1, controller.starts.size)
        chatLease.close()
        assertTrue(controller.stops.isEmpty())
        imageLease.close()
        imageLease.close()
        assertEquals(listOf(1L), controller.stops)
        assertTrue(!manager.hasActiveLeases())
    }

    @Test
    fun `chat nested inside image run updates notification conversation without restarting`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val image = async { manager.acquire(GenerationKind.IMAGE) }
        testScheduler.runCurrent()
        controller.active(1)
        val imageLease = image.await()
        val chatLease = manager.acquire(GenerationKind.CHAT, "conversation-42")

        assertEquals(1, controller.starts.size)
        assertEquals(
            listOf(Triple(1L, 1L, "conversation-42")),
            controller.conversationUpdates,
        )

        chatLease.close()
        imageLease.close()
    }

    @Test
    fun `notification follows the newest active chat lease and falls back when it closes`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val first = async { manager.acquire(GenerationKind.CHAT, "conversation-1") }
        testScheduler.runCurrent()
        controller.active(1)
        val firstLease = first.await()
        val imageLease = manager.acquire(GenerationKind.IMAGE)
        val secondLease = manager.acquire(GenerationKind.CHAT, "conversation-2")

        assertEquals(
            listOf(Triple(1L, 2L, "conversation-2")),
            controller.conversationUpdates,
        )

        secondLease.close()
        assertEquals(
            listOf(
                Triple(1L, 2L, "conversation-2"),
                Triple(1L, 3L, "conversation-1"),
            ),
            controller.conversationUpdates,
        )

        firstLease.close()
        assertEquals(
            listOf(
                Triple(1L, 2L, "conversation-2"),
                Triple(1L, 3L, "conversation-1"),
                Triple(1L, 4L, null),
            ),
            controller.conversationUpdates,
        )

        imageLease.close()
    }

    @Test
    fun `chat added while image start is in progress updates notification after start`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)
        lateinit var chatLease: GenerationLease
        controller.onStart = {
            chatLease = manager.begin(GenerationKind.CHAT, "conversation-during-start")
        }

        val imageLease = manager.begin(GenerationKind.IMAGE)

        assertEquals(1, controller.starts.size)
        assertEquals(
            listOf(Triple(1L, 1L, "conversation-during-start")),
            controller.conversationUpdates,
        )

        chatLease.close()
        imageLease.close()
    }

    @Test
    fun `rejected foreground service start fails before work can begin`() = runTest {
        val controller = FakeController().apply {
            startError = SecurityException("background start denied")
        }
        val manager = GenerationProtectionManager.forTesting(controller)

        val result = runCatching { manager.acquire(GenerationKind.CHAT) }

        assertTrue(result.exceptionOrNull() is GenerationProtectionException)
        assertTrue(controller.stops.isEmpty())
    }

    @Test
    fun `late callback from older run does not interrupt newer run`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val first = async { manager.acquire(GenerationKind.CHAT) }
        testScheduler.runCurrent()
        controller.active(1)
        testScheduler.runCurrent()
        first.await().close()

        val second = async { manager.acquire(GenerationKind.CHAT) }
        testScheduler.runCurrent()
        assertEquals(2L, controller.starts.last().runToken)
        controller.failed(1)
        assertTrue(!second.isCompleted)
        controller.active(2)
        assertEquals(2L, second.await().runToken)
    }

    @Test
    fun `retry after terminal service loss starts a fresh run before old lease closes`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val first = async { manager.acquire(GenerationKind.CHAT) }
        testScheduler.runCurrent()
        controller.active(1)
        val firstLease = first.await()
        controller.stopped(1)

        val secondLease = manager.begin(GenerationKind.CHAT)

        assertEquals(listOf(1L, 2L), controller.starts.map { it.runToken })
        assertEquals(2L, secondLease.runToken)

        firstLease.close()
        assertTrue(controller.stops.isEmpty())
        secondLease.close()
        assertEquals(listOf(2L), controller.stops)
    }

    @Test
    fun `old loss monitor observes retained terminal state after replacement starts`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val generation = supervisorScope {
            val running = async {
                runCatching {
                    manager.withProtection(GenerationKind.CHAT) {
                        delay(Long.MAX_VALUE)
                    }
                }
            }
            testScheduler.runCurrent()
            controller.active(1)
            testScheduler.runCurrent()

            controller.stopped(1)
            val replacement = manager.begin(GenerationKind.CHAT)
            assertEquals(2L, replacement.runToken)
            assertTrue(controller.state.value is GenerationForegroundServiceState.Starting)

            testScheduler.runCurrent()
            replacement.close()
            running
        }

        assertTrue(generation.await().exceptionOrNull() is GenerationProtectionException)
    }

    @Test
    fun `concurrent leases start and stop exactly one service run`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val leases = supervisorScope {
            List(24) {
                async(Dispatchers.Default) { manager.begin(GenerationKind.CHAT) }
            }.awaitAll()
        }

        assertEquals(1, controller.starts.size)
        controller.active(1)

        supervisorScope {
            leases.map { lease ->
                async(Dispatchers.Default) {
                    manager.awaitActive(lease).close()
                }
            }.awaitAll()
        }

        assertEquals(listOf(1L), controller.stops)
        assertTrue(!manager.hasActiveLeases())
    }

    @Test
    fun `service destruction interrupts an active generation and releases its lease`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val error = supervisorScope {
            val generation = async {
                runCatching {
                    manager.withProtection(GenerationKind.CHAT) {
                        delay(Long.MAX_VALUE)
                    }
                }
            }
            testScheduler.runCurrent()
            controller.active(1)
            testScheduler.runCurrent()
            controller.stopped(1)
            testScheduler.runCurrent()
            generation.await().exceptionOrNull()
        }

        assertTrue(error is GenerationProtectionException)
        assertEquals(listOf(1L), controller.stops)
        assertTrue(!manager.hasActiveLeases())
    }

    @Test
    fun `service destruction wins when it races a normally finishing block`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val generation = async {
            runCatching {
                manager.withProtection(GenerationKind.CHAT) {
                    controller.stopped(1)
                }
            }
        }
        testScheduler.runCurrent()
        controller.active(1)
        testScheduler.runCurrent()

        assertTrue(generation.await().exceptionOrNull() is GenerationProtectionException)
        assertEquals(listOf(1L), controller.stops)
        assertTrue(!manager.hasActiveLeases())
    }

    @Test
    fun `only the matching terminal service state reports protection loss`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)
        val lease = manager.begin(GenerationKind.CHAT)

        controller.active(lease.runToken)
        assertFalse(manager.isProtectionLost(lease.runToken))
        assertFalse(manager.isProtectionLost(lease.runToken + 1))

        controller.stopped(lease.runToken)
        assertTrue(manager.isProtectionLost(lease.runToken))
        lease.close()
    }

    @Test
    fun `delayed older start is rejected after controller has moved to a newer run`() {
        // The real service consults this state before assigning activeRunToken. This is the exact
        // ordering which prevents ACTION_START(run 1) arriving after STARTING(run 2).
        val state = GenerationForegroundServiceState.Starting(2)
        assertTrue(!state.canActivate(1))
        assertTrue(state.canActivate(2))
    }

    @Test
    fun `ownerless stale actions cannot stop a replacement starting or active run`() {
        assertFalse(GenerationForegroundServiceState.Starting(2).canCleanUpOwnerlessService())
        assertFalse(GenerationForegroundServiceState.Active(2).canCleanUpOwnerlessService())
    }

    @Test
    fun `ownerless service cleans up when no viable run remains`() {
        assertTrue(GenerationForegroundServiceState.Idle.canCleanUpOwnerlessService())
        assertTrue(GenerationForegroundServiceState.Stopped(1).canCleanUpOwnerlessService())
        assertTrue(
            GenerationForegroundServiceState.Failed(1, "start denied").canCleanUpOwnerlessService(),
        )
        assertTrue(GenerationForegroundServiceState.Stopped(2).canCleanUpOwnerlessService())
        assertFalse(GenerationForegroundServiceState.Starting(1).canCleanUpOwnerlessService())
        assertFalse(GenerationForegroundServiceState.Active(1).canCleanUpOwnerlessService())
    }

    @Test
    fun `terminal cancellation cannot be resurrected as active`() {
        val state = MutableStateFlow<GenerationForegroundServiceState>(
            GenerationForegroundServiceState.Stopped(1),
        )

        assertFalse(state.tryActivate(1))
        assertEquals(GenerationForegroundServiceState.Stopped(1), state.value)
    }

    @Test
    fun `only the matching starting run can transition to active`() {
        val state = MutableStateFlow<GenerationForegroundServiceState>(
            GenerationForegroundServiceState.Starting(2),
        )

        assertFalse(state.tryActivate(1))
        assertTrue(state.tryActivate(2))
        assertEquals(GenerationForegroundServiceState.Active(2), state.value)
    }

    @Test
    fun `same-run notification updates only accept a newer revision`() {
        assertTrue(
            canApplyConversationUpdate(
                activeRunToken = 7,
                activeConversationRevision = 3,
                updateRunToken = 7,
                updateConversationRevision = 4,
            ),
        )
        assertFalse(
            canApplyConversationUpdate(
                activeRunToken = 7,
                activeConversationRevision = 4,
                updateRunToken = 7,
                updateConversationRevision = 3,
            ),
        )
        assertFalse(
            canApplyConversationUpdate(
                activeRunToken = 8,
                activeConversationRevision = 1,
                updateRunToken = 7,
                updateConversationRevision = 99,
            ),
        )
    }

    @Test
    fun `progress only applies to the exact current conversation revision`() {
        assertTrue(
            isCurrentConversationTarget(
                activeRunToken = 7,
                activeConversationRevision = 4,
                updateRunToken = 7,
                updateConversationRevision = 4,
            ),
        )
        assertFalse(
            isCurrentConversationTarget(
                activeRunToken = 7,
                activeConversationRevision = 4,
                updateRunToken = 7,
                updateConversationRevision = 3,
            ),
        )
    }

    @Test
    fun `terminal cleanup cannot stop a replacement run`() {
        assertTrue(
            canStopOwnedTerminalRun(
                activeRunToken = 1,
                controllerState = GenerationForegroundServiceState.Failed(1, "start denied"),
                terminalRunToken = 1,
            ),
        )
        assertFalse(
            canStopOwnedTerminalRun(
                activeRunToken = 1,
                controllerState = GenerationForegroundServiceState.Starting(2),
                terminalRunToken = 1,
            ),
        )
        assertTrue(
            canStopOwnedTerminalRun(
                activeRunToken = 1,
                controllerState = GenerationForegroundServiceState.Failed(2, "replacement rejected"),
                terminalRunToken = 1,
            ),
        )
        assertFalse(
            canStopOwnedTerminalRun(
                activeRunToken = 2,
                controllerState = GenerationForegroundServiceState.Failed(1, "start denied"),
                terminalRunToken = 1,
            ),
        )
        assertFalse(
            canStopOwnedTerminalRun(
                activeRunToken = 1,
                controllerState = GenerationForegroundServiceState.Active(1),
                terminalRunToken = 1,
            ),
        )
    }

    @Test
    fun `closing provisional lease invalidates its queued start`() = runTest {
        val controller = FakeController()
        val manager = GenerationProtectionManager.forTesting(controller)

        val lease = manager.begin(GenerationKind.CHAT)
        lease.close()

        assertEquals(listOf(1L), controller.stops)
        assertTrue(!controller.canActivate(1))
        assertTrue(!manager.hasActiveLeases())
    }

    private class FakeController : GenerationForegroundServiceController {
        private val mutableState = MutableStateFlow<GenerationForegroundServiceState>(
            GenerationForegroundServiceState.Idle,
        )
        override val state: StateFlow<GenerationForegroundServiceState> = mutableState
        private val mutableTerminalStates =
            MutableStateFlow<Map<Long, GenerationForegroundServiceState>>(emptyMap())
        override val terminalStates: StateFlow<Map<Long, GenerationForegroundServiceState>> =
            mutableTerminalStates
        val starts = Collections.synchronizedList(mutableListOf<GenerationServiceRequest>())
        val stops = Collections.synchronizedList(mutableListOf<Long>())
        val conversationUpdates =
            Collections.synchronizedList(mutableListOf<Triple<Long, Long, String?>>())
        var startError: Throwable? = null
        var onStart: ((GenerationServiceRequest) -> Unit)? = null

        override fun start(request: GenerationServiceRequest): Result<Unit> {
            starts += request
            mutableState.value = GenerationForegroundServiceState.Starting(request.runToken)
            onStart?.invoke(request)
            return startError?.let(Result.Companion::failure) ?: Result.success(Unit)
        }

        override fun stop(runToken: Long) {
            stops += runToken
            retainTerminal(GenerationForegroundServiceState.Stopped(runToken))
            if (mutableState.value.runTokenOrNullForTest() == runToken) {
                mutableState.value = GenerationForegroundServiceState.Stopped(runToken)
            }
        }

        override fun updateConversation(
            runToken: Long,
            conversationRevision: Long,
            conversationId: String?,
        ) {
            conversationUpdates += Triple(runToken, conversationRevision, conversationId)
        }

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
            val failure = GenerationForegroundServiceState.Failed(runToken, message, cause)
            retainTerminal(failure)
            if (mutableState.value.runTokenOrNullForTest() == runToken) {
                mutableState.value = failure
            }
        }

        override fun canActivate(runToken: Long): Boolean {
            val state = mutableState.value
            return state is GenerationForegroundServiceState.Starting && state.runToken == runToken
        }

        fun active(runToken: Long) {
            mutableState.value = GenerationForegroundServiceState.Active(runToken)
        }

        fun failed(runToken: Long) {
            val failure = GenerationForegroundServiceState.Failed(runToken, "failed")
            retainTerminal(failure)
            if (mutableState.value.runTokenOrNullForTest() == runToken) {
                mutableState.value = failure
            }
        }

        fun stopped(runToken: Long) {
            val stopped = GenerationForegroundServiceState.Stopped(runToken)
            retainTerminal(stopped)
            if (mutableState.value.runTokenOrNullForTest() == runToken) {
                mutableState.value = stopped
            }
        }

        private fun retainTerminal(state: GenerationForegroundServiceState) {
            val runToken = state.runTokenOrNullForTest() ?: return
            mutableTerminalStates.value = mutableTerminalStates.value + (runToken to state)
        }
    }
}

private fun GenerationForegroundServiceState.runTokenOrNullForTest(): Long? = when (this) {
    GenerationForegroundServiceState.Idle -> null
    is GenerationForegroundServiceState.Starting -> runToken
    is GenerationForegroundServiceState.Active -> runToken
    is GenerationForegroundServiceState.Failed -> runToken
    is GenerationForegroundServiceState.Stopped -> runToken
}
