package me.rerere.rikkahub.data.sync.s3

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AwsSignatureV4Test {
    private val config = S3Config(
        endpoint = "https://s3.example.com",
        accessKeyId = "test-access-key",
        secretAccessKey = "test-secret-key",
        bucket = "bingoapp",
        region = "cn-shenzhen",
        pathStyle = true,
    )

    @Test
    fun `signs path style S3-compatible request with payload metadata`() {
        val payload = "hello OSS".toByteArray()
        val signed = AwsSignatureV4.sign(
            config = config,
            method = "PUT",
            path = "/android/test-file.txt",
            payload = payload,
            contentType = "text/plain",
        )

        assertEquals(
            "https://s3.example.com/bingoapp/android/test-file.txt",
            signed.url,
        )
        assertEquals("s3.example.com", signed.headers["host"])
        assertEquals(payload.size.toString(), signed.headers["content-length"])
        assertEquals("text/plain", signed.headers["content-type"])
        assertEquals(sha256Hex(payload), signed.headers["x-amz-content-sha256"])
        assertEquals("AWS4-HMAC-SHA256", signed.headers["authorization"]?.substringBefore(' '))
        assertTrue(signed.headers["authorization"].orEmpty().contains("Credential=test-access-key/"))
        assertTrue(signed.headers["authorization"].orEmpty().contains("SignedHeaders="))
        assertTrue(signed.headers["authorization"].orEmpty().contains("Signature="))
    }

    @Test
    fun `uses explicit file length when signing a streamed payload`() {
        val signed = AwsSignatureV4.sign(
            config = config,
            method = "PUT",
            path = "/android/backup.zip",
            payloadHash = "a".repeat(64),
            contentLength = 123_456L,
            contentType = "application/zip",
        )

        assertEquals("123456", signed.headers["content-length"])
        assertEquals("a".repeat(64), signed.headers["x-amz-content-sha256"])
        assertEquals("application/zip", signed.headers["content-type"])
    }

    @Test
    fun `virtual host endpoint keeps bucket out of request path`() {
        val virtualHostConfig = config.copy(pathStyle = false)
        val signed = AwsSignatureV4.sign(
            config = virtualHostConfig,
            method = "GET",
            path = "/android/backup.zip",
        )

        assertEquals(
            "https://bingoapp.s3.example.com/android/backup.zip",
            signed.url,
        )
        assertEquals("bingoapp.s3.example.com", signed.headers["host"])
        assertFalse(signed.url.contains("/bingoapp/"))
    }

    @Test
    fun `OSS virtual host endpoint keeps bucket out of request path`() {
        val ossConfig = config.copy(
            endpoint = "https://s3.oss-cn-shenzhen.aliyuncs.com",
            pathStyle = false,
        )
        val signed = AwsSignatureV4.sign(
            config = ossConfig,
            method = "GET",
            path = "/android/backup.zip",
        )

        assertEquals(
            "https://bingoapp.s3.oss-cn-shenzhen.aliyuncs.com/android/backup.zip",
            signed.url,
        )
        assertEquals("bingoapp.s3.oss-cn-shenzhen.aliyuncs.com", signed.headers["host"])
        assertFalse(signed.url.contains("/bingoapp/"))
    }

    @Test
    fun `query parameters are sorted and encoded in signed URL`() {
        val signed = AwsSignatureV4.sign(
            config = config,
            method = "GET",
            path = "/",
            queryParams = mapOf(
                "prefix" to "android/2026 08",
                "list-type" to "2",
            ),
        )

        assertEquals(
            "https://s3.example.com/bingoapp/?list-type=2&prefix=android%2F2026%2008",
            signed.url,
        )
        val authorization = signed.headers["authorization"]
        assertNotNull(authorization)
        assertTrue(authorization!!.contains("Signature="))
    }

    @Test
    fun `encodes object path consistently in signed URL`() {
        val signed = AwsSignatureV4.sign(
            config = config,
            method = "GET",
            path = "/android/backup file+1.zip",
        )

        assertEquals(
            "https://s3.example.com/bingoapp/android/backup%20file%2B1.zip",
            signed.url,
        )
    }

    @Test
    fun `refuses to sign cleartext backup requests`() {
        val insecureConfig = config.copy(endpoint = "http://127.0.0.1:9000")

        assertThrows(IllegalArgumentException::class.java) {
            AwsSignatureV4.sign(
                config = insecureConfig,
                method = "PUT",
                path = "/android/backup.zip",
                payload = byteArrayOf(1),
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
