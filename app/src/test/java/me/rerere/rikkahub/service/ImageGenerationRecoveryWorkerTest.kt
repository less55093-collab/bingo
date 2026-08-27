package me.rerere.rikkahub.service

import androidx.work.ExistingWorkPolicy
import me.rerere.ai.provider.ImageGenerationTerminalException
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ImageGenerationRecoveryWorkerTest {
    @Test
    fun `pending records keep the durable wake-up retrying`() {
        assertEquals(
            ImageGenerationRecoveryDecision.RESCHEDULE,
            imageGenerationRecoveryDecision(hasPendingTasks = true),
        )
    }

    @Test
    fun `empty pending records let the wake-up finish`() {
        assertEquals(
            ImageGenerationRecoveryDecision.SUCCESS,
            imageGenerationRecoveryDecision(hasPendingTasks = false),
        )
    }

    @Test
    fun `new wake replaces retrying bridge instead of growing a work chain`() {
        assertEquals(ExistingWorkPolicy.REPLACE, imageGenerationRecoveryWorkPolicy)
    }

    @Test
    fun `only terminal or already-cleared failures finalize recovery`() {
        assertTrue(
            shouldFinalizeImageRecovery(
                ImageGenerationTerminalException("failed"),
                pendingStillExists = true,
            )
        )
        assertTrue(
            shouldFinalizeImageRecovery(
                IllegalStateException("callback already removed pending"),
                pendingStillExists = false,
            )
        )
        assertFalse(
            shouldFinalizeImageRecovery(
                java.io.IOException("offline"),
                pendingStillExists = true,
            )
        )
    }

    @Test
    fun `persisted provider resolves a model overwrite outside the top level list`() {
        val overwriteId = Uuid.random()
        val overwrite = ProviderSetting.OpenAI(id = overwriteId, baseUrl = "https://images.test/v1")
        val model = Model(providerOverwrite = overwrite)

        val resolved = resolvePersistedImageProvider(
            model = model,
            providers = emptyList(),
            persistedProviderId = overwriteId.toString(),
        )

        assertEquals(overwrite, resolved)
    }
}
