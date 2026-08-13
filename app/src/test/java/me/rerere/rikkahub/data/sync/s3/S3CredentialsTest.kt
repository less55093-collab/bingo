package me.rerere.rikkahub.data.sync.s3

import org.junit.Assert.assertEquals
import org.junit.Test

class S3CredentialsTest {
    @Test
    fun `credential payload round trips characters used by access keys`() {
        val credentials = S3Credentials(
            accessKeyId = "LTAI:example/key",
            secretAccessKey = "secret:with/slashes+and=padding",
        )

        assertEquals(credentials, S3Credentials.fromPayload(credentials.toPayload()))
    }
}
