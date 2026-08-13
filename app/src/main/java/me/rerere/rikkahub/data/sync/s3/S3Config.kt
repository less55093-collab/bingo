package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    /**
     * OSS buckets normally require virtual-hosted requests, for example
     * `https://bucket.oss-cn-shenzhen.aliyuncs.com/key`.
     *
     * Keep path-style available for S3-compatible servers such as MinIO.
     */
    val pathStyle: Boolean = false,
    /** Object prefix used for backup archives, without leading/trailing slashes. */
    val prefix: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    /**
     * Normalized endpoint used for requests. OSS console examples often omit the scheme, but
     * Android blocks cleartext HTTP by default and backups must not send credentials in cleartext.
     */
    val normalizedEndpoint: String
        get() = endpoint.trim().let { value ->
            val scheme = when {
                value.startsWith("https://", ignoreCase = true) -> "https"
                value.startsWith("http://", ignoreCase = true) -> "http"
                else -> "https"
            }
            "$scheme://${value.substringAfter("://", value)}"
        }.trimEnd('/')

    val host: String
        get() = normalizedEndpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = normalizedEndpoint.startsWith("https://", ignoreCase = true)

    /**
     * Validates the endpoint before any credentials are sent.
     *
     * The app may use cleartext HTTP for unrelated local features, but backup credentials must
     * never be sent to an HTTP endpoint. Keep this check at the S3 boundary so every operation
     * gets the same protection.
     */
    fun requireSecureEndpoint() {
        require(endpoint.isNotBlank()) { "OSS endpoint is required" }
        require(bucket.isNotBlank()) { "OSS bucket is required" }
        require(host.isNotBlank()) { "OSS endpoint host is required" }
        require(isHttps) { "OSS backup endpoint must use HTTPS" }
        require(!isAliyunOssEndpoint || !pathStyle) {
            "Alibaba Cloud OSS requires virtual-hosted addressing; turn off path style"
        }
    }

    /** OSS's S3-compatible API accepts virtual-hosted bucket URLs only. */
    private val isAliyunOssEndpoint: Boolean
        get() = host.substringBefore(':').equals("aliyuncs.com", ignoreCase = true) ||
            host.substringBefore(':').endsWith(".aliyuncs.com", ignoreCase = true)

    val backupPrefix: String
        get() = prefix.trim('/').ifBlank { "rikkahub_backups" }

    fun backupKey(fileName: String): String {
        return "$backupPrefix/${fileName.trimStart('/')}"
    }

    /**
     * Keeps each signed-in application's archives in a distinct object namespace.
     *
     * The bucket policy or STS policy must still enforce this boundary server-side. This helper
     * prevents the app itself from listing or operating on another account's backup objects.
     */
    fun backupPrefixForAccount(accountId: Long): String {
        require(accountId > 0) { "Account ID must be positive" }
        return "users/$accountId/$backupPrefix"
    }

    fun backupKeyForAccount(accountId: Long, fileName: String): String {
        return "${backupPrefixForAccount(accountId)}/${fileName.trimStart('/')}"
    }

    fun isBackupKeyForAccount(accountId: Long, key: String): Boolean {
        val prefix = "${backupPrefixForAccount(accountId)}/"
        val fileName = key.substringAfterLast('/')
        return key.startsWith(prefix) && fileName.startsWith("backup_") && fileName.endsWith(".zip")
    }

    fun bucketUrl(): String {
        requireSecureEndpoint()
        return if (pathStyle) {
            "$normalizedEndpoint/$bucket"
        } else {
            "https://$bucket.$host"
        }
    }

    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}
