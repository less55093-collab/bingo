package me.rerere.rikkahub.data.sync.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S3ConfigTest {
    @Test
    fun `normalizes endpoint and builds path style bucket url`() {
        val config = S3Config(
            endpoint = "https://s3.example.com/",
            bucket = "bingoapp",
            pathStyle = true,
        )

        assertEquals("s3.example.com", config.host)
        assertTrue(config.isHttps)
        assertEquals(
            "https://s3.example.com/bingoapp",
            config.bucketUrl(),
        )
    }

    @Test
    fun `builds virtual host bucket url for S3 compatible endpoint`() {
        val config = S3Config(
            endpoint = "https://s3.example.com",
            bucket = "bingoapp",
            pathStyle = false,
        )

        assertEquals(
            "https://bingoapp.s3.example.com",
            config.bucketUrl(),
        )
    }

    @Test
    fun `rejects cleartext HTTP backup endpoint`() {
        val config = S3Config(
            endpoint = "http://127.0.0.1:9000/",
            bucket = "test-bucket",
            pathStyle = true,
        )

        assertEquals("127.0.0.1:9000", config.host)
        assertFalse(config.isHttps)
        assertThrows(IllegalArgumentException::class.java) {
            config.bucketUrl()
        }
    }

    @Test
    fun `scheme-less OSS endpoint defaults to HTTPS`() {
        val config = S3Config(
            endpoint = "oss-cn-shenzhen.aliyuncs.com",
            bucket = "bingoapp",
            pathStyle = false,
        )

        assertEquals("https://oss-cn-shenzhen.aliyuncs.com", config.normalizedEndpoint)
        assertTrue(config.isHttps)
        assertEquals(
            "https://bingoapp.oss-cn-shenzhen.aliyuncs.com",
            config.bucketUrl(),
        )
    }

    @Test
    fun `rejects path style for Alibaba Cloud OSS`() {
        val config = S3Config(
            endpoint = "https://oss-cn-shenzhen.aliyuncs.com",
            bucket = "bingoapp",
            pathStyle = true,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            config.bucketUrl()
        }
        assertEquals(
            "Alibaba Cloud OSS requires virtual-hosted addressing; turn off path style",
            error.message,
        )
    }

    @Test
    fun `normalizes backup prefix and builds backup key`() {
        val config = S3Config(prefix = "/android/")

        assertEquals("android", config.backupPrefix)
        assertEquals("android/backup_test.zip", config.backupKey("/backup_test.zip"))
    }

    @Test
    fun `builds and validates account scoped backup keys`() {
        val config = S3Config(prefix = "/android/")
        val key = config.backupKeyForAccount(accountId = 42, fileName = "backup_test.zip")

        assertEquals("users/42/android/backup_test.zip", key)
        assertTrue(config.isBackupKeyForAccount(accountId = 42, key = key))
        assertFalse(config.isBackupKeyForAccount(accountId = 43, key = key))
        assertFalse(config.isBackupKeyForAccount(accountId = 42, key = "users/42/android/not-a-backup.zip"))
    }
}
