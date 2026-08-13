package me.rerere.rikkahub.service

import me.rerere.ai.provider.StreamInterruptedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundGenerationInterruptionNoticeTest {
    private val interrupted = StreamInterruptedException("stream closed before completion")

    @Test
    fun `shows only for an unhandled stream interruption while app is backgrounded`() {
        assertTrue(
            shouldShowBackgroundGenerationNotice(
                appInForeground = false,
                error = interrupted,
                protectionLost = false,
                alreadyHandled = false,
                alreadyPending = false,
            )
        )
    }

    @Test
    fun `does not show for foreground failures, protection failures, ordinary errors, or repeats`() {
        assertFalse(shouldShowBackgroundGenerationNotice(true, interrupted, false, false, false))
        assertFalse(shouldShowBackgroundGenerationNotice(false, interrupted, true, false, false))
        assertFalse(shouldShowBackgroundGenerationNotice(false, IllegalStateException(), false, false, false))
        assertFalse(shouldShowBackgroundGenerationNotice(false, interrupted, false, true, false))
        assertFalse(shouldShowBackgroundGenerationNotice(false, interrupted, false, false, true))
    }

    @Test
    fun `first message notice appears once when no notice has been handled or queued`() {
        assertTrue(
            shouldShowFirstBackgroundGenerationNotice(
                alreadyHandled = false,
                alreadyPrompted = false,
                alreadyPending = false,
            )
        )
        assertFalse(shouldShowFirstBackgroundGenerationNotice(true, false, false))
        assertFalse(shouldShowFirstBackgroundGenerationNotice(false, true, false))
        assertFalse(shouldShowFirstBackgroundGenerationNotice(false, false, true))
    }
}
