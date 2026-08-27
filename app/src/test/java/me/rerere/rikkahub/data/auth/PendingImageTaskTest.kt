package me.rerere.rikkahub.data.auth

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingImageTaskTest {
    @Test
    fun `legacy task uses server task id as stable recovery request id`() {
        val legacy = JsonInstant.decodeFromString<PendingImageTask>(
            """{"taskId":"imgtask_legacy","prompt":"cat","modelName":"image","origin":"standalone_generate"}""",
        )

        val firstRead = legacy.normalized()
        val secondRead = legacy.normalized()

        assertEquals("imgtask_legacy", firstRead.requestId)
        assertEquals(firstRead.requestId, secondRead.requestId)
        assertEquals("imgtask_legacy", firstRead.taskId)
    }

    @Test
    fun `pre-submit task round trip preserves idempotency key without task id`() {
        val pending = PendingImageTask(
            requestId = "request-before-submit",
            taskId = null,
            prompt = "cat",
            modelName = "image",
            origin = "standalone_generate",
            modelId = "model-id",
            providerId = "provider-id",
            providerBaseUrl = "https://images.example.test/v1",
            size = "1024x1024",
            numOfImages = 2,
            apiKeyFingerprint = "fingerprint",
        )

        val decoded = JsonInstant.decodeFromString<PendingImageTask>(
            JsonInstant.encodeToString(pending),
        ).normalized()

        assertEquals("request-before-submit", decoded.requestId)
        assertNull(decoded.taskId)
        assertEquals(2, decoded.numOfImages)
        assertEquals("1024x1024", decoded.size)
        assertEquals("fingerprint", decoded.apiKeyFingerprint)
        assertEquals("https://images.example.test/v1", decoded.providerBaseUrl)
    }

    @Test
    fun `pre-submit task with empty fingerprint may choose a key during recovery`() {
        val pending = PendingImageTask(requestId = "request-before-key-selection")

        assertNull(pending.recoveryApiKeyFingerprint())
        assertEquals(
            "fingerprint",
            pending.copy(apiKeyFingerprint = "fingerprint").recoveryApiKeyFingerprint(),
        )
    }
}
