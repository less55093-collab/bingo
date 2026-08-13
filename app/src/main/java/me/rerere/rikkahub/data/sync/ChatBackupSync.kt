package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.Intent
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.api.gateway.BingoGatewayAPI
import me.rerere.rikkahub.data.api.gateway.requireData
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.db.AccountDatabaseManager
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.gateway.ChatBackupUploadRequest
import me.rerere.rikkahub.utils.DatabaseUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatBackupSync(
    private val context: Context,
    private val api: BingoGatewayAPI,
    private val tokenStore: AuthTokenStore,
    private val database: AppDatabase,
    private val rawHttpClient: OkHttpClient,
    private val json: Json,
) {
    private val uploadMutex = Mutex()

    suspend fun uploadNow(): Boolean = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            uploadNowLocked()
        }
    }

    private suspend fun uploadNowLocked(): Boolean = withContext(Dispatchers.IO) {
        val accountId = currentAccountId() ?: return@withContext false
        val databaseName = AccountDatabaseManager.currentDatabaseName(context)
        check(AccountDatabaseManager.belongsToAccount(databaseName, accountId)) {
            "Refusing to back up a database outside the signed-in account namespace"
        }
        if (!hasLocalUserData()) return@withContext false
        if (!api.chatBackupStatus().requireData().enabled) return@withContext false
        check(DatabaseUtil.checkpoint(database)) { "Unable to checkpoint chat database" }

        val databaseFile = context.getDatabasePath(databaseName)
        check(databaseFile.isFile) { "Chat database does not exist" }
        val archive = createArchive(accountId, databaseFile, accountFiles())
        try {
            val signed = api.chatBackupUploadURL(ChatBackupUploadRequest(archive.length())).requireData()
            check(signed.method.equals("PUT", ignoreCase = true)) { "Unexpected backup upload method" }
            val body = archive.asRequestBody(ZIP_MEDIA_TYPE)
            val request = Request.Builder().url(signed.url).put(body).apply {
                signed.headers.forEach { (name, value) -> header(name, value) }
            }.build()
            rawHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "OSS upload failed with HTTP ${response.code}" }
            }
            true
        } finally {
            archive.delete()
        }
    }

    suspend fun restoreIfLocalEmpty(): Boolean = withContext(Dispatchers.IO) {
        val accountId = currentAccountId() ?: return@withContext false
        val databaseName = AccountDatabaseManager.currentDatabaseName(context)
        if (!AccountDatabaseManager.belongsToAccount(databaseName, accountId)) return@withContext false
        if (hasLocalUserData()) return@withContext false

        val status = api.chatBackupStatus().requireData()
        if (!status.enabled || !status.exists || status.size <= 0 || status.size > MAX_BACKUP_BYTES) {
            return@withContext false
        }
        val signed = api.chatBackupDownloadURL().requireData()
        check(signed.method.equals("GET", ignoreCase = true)) { "Unexpected backup download method" }
        val archive = File.createTempFile("chat_backup_restore_", ".zip", context.cacheDir)
        try {
            val request = Request.Builder().url(signed.url).get().build()
            rawHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "OSS download failed with HTTP ${response.code}" }
                val body = checkNotNull(response.body)
                val declaredLength = body.contentLength()
                check(declaredLength in 1..MAX_BACKUP_BYTES) { "Invalid chat backup size" }
                body.byteStream().use { input ->
                    FileOutputStream(archive).use { output -> copyLimited(input, output, MAX_BACKUP_BYTES) }
                }
            }
            val restoredDatabase = extractAndValidateArchive(archive, accountId)
            try {
                DatabaseRestoreCoordinator.stage(context, restoredDatabase, databaseName)
            } finally {
                restoredDatabase.delete()
            }
            true
        } finally {
            archive.delete()
        }
    }

    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
        context.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    private suspend fun currentAccountId(): Long? = tokenStore.profileFlow.first()?.id?.takeIf { it > 0 }

    private fun hasLocalUserData(): Boolean {
        val query = """
            SELECT
                (SELECT COUNT(*) FROM conversationentity) +
                (SELECT COUNT(*) FROM message_node) +
                (SELECT COUNT(*) FROM genmediaentity) +
                (SELECT COUNT(*) FROM managed_files) +
                (SELECT COUNT(*) FROM favorites) +
                (SELECT COUNT(*) FROM workspaces) +
                (SELECT COUNT(*) FROM conversation_folder)
        """.trimIndent()
        return database.openHelper.readableDatabase.query(query).use { cursor ->
            cursor.moveToFirst() && cursor.getLong(0) > 0L
        }
    }

    private suspend fun accountFiles(): List<Pair<File, String>> {
        val filesRoot = context.filesDir.canonicalFile
        val managedFiles = database.managedFileDao().listByFolder("upload").first()
            .map { it.relativePath }
        val generatedFiles = database.genMediaDao().getAllMedia().map { entity ->
            File(entity.path).let { file ->
                if (file.isAbsolute) file.canonicalFile.relativeToOrNull(filesRoot)?.invariantSeparatorsPath
                else entity.path
            }
        }
        return (managedFiles + generatedFiles)
            .filterNotNull()
            .distinct()
            .mapNotNull { relativePath ->
                val source = File(filesRoot, relativePath).canonicalFile
                if (!source.path.startsWith(filesRoot.path + File.separator) || !source.isFile) null
                else source to relativePath.replace(File.separatorChar, '/')
            }
    }

    private fun createArchive(
        accountId: Long,
        databaseFile: File,
        accountFiles: List<Pair<File, String>>,
    ): File {
        val archive = File.createTempFile("chat_backup_", ".zip", context.cacheDir)
        val manifest = ChatBackupManifest(
            accountId = accountId,
            createdAt = Instant.now().toString(),
            appVersion = BuildConfig.VERSION_NAME,
            databaseSha256 = sha256(databaseFile),
        )
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
            FileInputStream(databaseFile).use { it.copyTo(zip) }
            zip.closeEntry()
            accountFiles.forEach { (file, relativePath) ->
                require(isSafeRelativePath(relativePath)) { "Invalid account file path" }
                zip.putNextEntry(ZipEntry("$FILES_PREFIX$relativePath"))
                FileInputStream(file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        check(archive.length() in 1..MAX_BACKUP_BYTES) { "Chat backup exceeds size limit" }
        return archive
    }

    private fun extractAndValidateArchive(archive: File, accountId: Long): File {
        var manifest: ChatBackupManifest? = null
        var databaseFile: File? = null
        val stagedFiles = mutableListOf<Pair<File, String>>()
        var extractedBytes = 0L
        val stagedRoot = File.createTempFile("chat_files_restore_", "", context.cacheDir).apply {
            delete()
            mkdirs()
        }
        try {
            ZipInputStream(FileInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    require(!entry.isDirectory) { "Chat backup contains an unexpected directory" }
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            check(manifest == null) { "Duplicate chat backup manifest" }
                            manifest = json.decodeFromString(
                                readLimited(zip, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                            )
                        }
                        DATABASE_ENTRY -> {
                            check(databaseFile == null) { "Duplicate chat database" }
                            databaseFile = File.createTempFile("chat_database_restore_", ".db", context.cacheDir)
                            FileOutputStream(databaseFile).use { output ->
                                extractedBytes += copyLimited(
                                    zip,
                                    output,
                                    MAX_BACKUP_BYTES - extractedBytes,
                                )
                            }
                        }
                        else -> if (entry.name.startsWith(FILES_PREFIX)) {
                            val relativePath = entry.name.removePrefix(FILES_PREFIX)
                            require(isSafeRelativePath(relativePath)) { "Invalid chat backup file path" }
                            val target = File(stagedRoot, relativePath).canonicalFile
                            require(target.path.startsWith(stagedRoot.canonicalPath + File.separator)) {
                                "Chat backup file escapes staged storage"
                            }
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                extractedBytes += copyLimited(
                                    zip,
                                    output,
                                    MAX_BACKUP_BYTES - extractedBytes,
                                )
                            }
                            stagedFiles += target to relativePath
                        } else {
                            error("Unexpected chat backup entry")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val parsedManifest = checkNotNull(manifest) { "Missing chat backup manifest" }
            val restored = checkNotNull(databaseFile) { "Missing chat database" }
            check(parsedManifest.schema == 1) { "Unsupported chat backup schema" }
            check(parsedManifest.accountId == accountId) { "Chat backup belongs to another account" }
            check(sha256(restored) == parsedManifest.databaseSha256) { "Chat database checksum mismatch" }
            val root = context.filesDir.canonicalFile
            stagedFiles.forEach { (source, relativePath) ->
                val target = File(root, relativePath).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) {
                    "Chat backup file escapes app storage"
                }
                target.parentFile?.mkdirs()
                val replacement = File(target.parentFile, "${target.name}.chat-restore")
                source.copyTo(replacement, overwrite = true)
                if (target.exists()) check(target.delete()) { "Unable to replace chat attachment" }
                check(replacement.renameTo(target)) { "Unable to commit restored chat attachment" }
            }
            return restored
        } catch (error: Throwable) {
            databaseFile?.delete()
            throw error
        } finally {
            stagedRoot.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyLimited(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        limit: Long,
    ): Long {
        check(limit >= 0) { "Chat backup exceeds size limit" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            check(total <= limit) { "Chat backup exceeds size limit" }
            output.write(buffer, 0, read)
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyLimited(input, output, limit)
        return output.toByteArray()
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\')) return false
        return path.split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
    }

    companion object {
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val DATABASE_ENTRY = "rikka_hub.db"
        private const val FILES_PREFIX = "files/"
        private const val MAX_BACKUP_BYTES = 256L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private val ZIP_MEDIA_TYPE = "application/zip".toMediaType()
    }
}

@Serializable
data class ChatBackupManifest(
    val schema: Int = 1,
    val accountId: Long,
    val createdAt: String,
    val appVersion: String,
    val databaseSha256: String,
)
