package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `download progress uses response content length`() {
        assertEquals(0, calculateDownloadProgress(downloadedBytes = 0, totalBytes = 1_000))
        assertEquals(49, calculateDownloadProgress(downloadedBytes = 499, totalBytes = 1_000))
        assertEquals(100, calculateDownloadProgress(downloadedBytes = 1_000, totalBytes = 1_000))
        assertEquals(100, calculateDownloadProgress(downloadedBytes = 1_500, totalBytes = 1_000))
    }

    @Test
    fun `download progress is indeterminate without content length`() {
        assertNull(calculateDownloadProgress(downloadedBytes = 100, totalBytes = -1))
        assertNull(calculateDownloadProgress(downloadedBytes = 100, totalBytes = 0))
    }
}
