package me.rerere.rikkahub.data.datastore.migration

import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceStoreV5MigrationTest {
    @Test
    fun `extracts only complete legacy OSS credentials`() {
        val raw = JsonInstant.encodeToString(
            S3Config(
                endpoint = "https://oss-cn-shenzhen.aliyuncs.com",
                accessKeyId = "access-key",
                secretAccessKey = "secret-key",
                bucket = "bingoapp",
            )
        )

        val credentials = legacyS3Credentials(raw)

        assertEquals("access-key", credentials?.accessKeyId)
        assertEquals("secret-key", credentials?.secretAccessKey)
    }

    @Test
    fun `does not extract partial or malformed legacy credentials`() {
        val partial = JsonInstant.encodeToString(S3Config(accessKeyId = "access-key"))

        assertNull(legacyS3Credentials(partial))
        assertNull(legacyS3Credentials("not-json"))
        assertNull(legacyS3Credentials(null))
    }
}
