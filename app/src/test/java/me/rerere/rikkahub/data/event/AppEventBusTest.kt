package me.rerere.rikkahub.data.event

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AppEventBusTest {
    @Test
    fun `terminal emit waits for a full buffer and is delivered when subscriber resumes`() = runTest {
        val bus = AppEventBus()
        val subscriberReady = CompletableDeferred<Unit>()
        val releaseSubscriber = CompletableDeferred<Unit>()
        val received = mutableListOf<AppEvent>()
        val subscriber = backgroundScope.launch {
            bus.events.collect { event ->
                subscriberReady.complete(Unit)
                releaseSubscriber.await()
                received += event
            }
        }
        testScheduler.runCurrent()

        val firstEvent = AppEvent.Speak("first")
        assertTrue(bus.tryEmit(firstEvent))
        subscriberReady.await()

        repeat(16) { index ->
            assertTrue(bus.tryEmit(AppEvent.Speak("buffered-$index")))
        }
        assertFalse(bus.tryEmit(AppEvent.Speak("dropped")))

        val terminalEvent = AppEvent.ChatGenerationEnded(
            conversationId = Uuid.random(),
            senderName = "assistant",
            result = AppEvent.ChatGenerationResult.INTERRUPTED,
        )
        val terminalSend = async { bus.emit(terminalEvent) }
        testScheduler.runCurrent()
        assertFalse(terminalSend.isCompleted)

        releaseSubscriber.complete(Unit)
        terminalSend.await()
        testScheduler.runCurrent()

        assertEquals(firstEvent, received.first())
        assertEquals(terminalEvent, received.last())
        subscriber.cancelAndJoin()
    }
}
