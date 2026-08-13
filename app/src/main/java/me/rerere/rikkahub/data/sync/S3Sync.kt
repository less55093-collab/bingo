package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AccountDatabaseManager
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.s3.S3CredentialStore
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "S3Sync"

class S3Sync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val database: AppDatabase,
    private val authTokenStore: AuthTokenStore,
    private val credentialStore: S3CredentialStore,
) {
    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    private suspend fun resolvedSession(config: S3Config): S3Session {
        val accountId = currentAccountId()
        val credentials = credentialStore.load(accountId)
            ?: throw IllegalStateException("S3 credentials are not configured")
        return S3Session(
            accountId = accountId,
            config = config.copy(
                accessKeyId = credentials.accessKeyId,
                secretAccessKey = credentials.secretAccessKey,
            ),
        )
    }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val session = resolvedSession(config)
        val client = getS3Client(session.config)
        val accountPrefix = session.config.backupPrefixForAccount(session.accountId)
        val key = "$accountPrefix/connection_test_${UUID.randomUUID()}.txt"
        val payload = "rikkahub-oss-connection-test".toByteArray(Charsets.UTF_8)

        // Listing alone does not prove that the credentials can actually back up or restore a
        // file. Use a unique object inside the signed-in account namespace and clean it up.
        var uploaded = false
        var primaryFailure: Throwable? = null
        try {
            client.listObjects(prefix = "$accountPrefix/", maxKeys = 1).getOrThrow()
            client.putObject(key, payload, contentType = "text/plain").getOrThrow()
            uploaded = true
            val downloaded = client.getObject(key).getOrThrow()
            check(downloaded.contentEquals(payload)) { "OSS connection test returned different data" }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (uploaded) {
                runCatching { client.deleteObject(key).getOrThrow() }
                    .onFailure { cleanupError ->
                        if (primaryFailure == null) throw cleanupError
                        primaryFailure.addSuppressed(cleanupError)
                        Log.w(TAG, "testS3: Failed to clean up connection test object", cleanupError)
                    }
            }
        }
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val session = resolvedSession(config)
        val file = prepareBackupFile(session.config)
        try {
            val client = getS3Client(session.config)
            val key = session.config.backupKeyForAccount(session.accountId, file.name)

            client.putObject(
                key = key,
                file = file,
                contentType = "application/zip"
            ).getOrThrow()

            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            // Clean up the temporary archive on both success and failure.
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val session = resolvedSession(config)
        val client = getS3Client(session.config)
        val accountPrefix = session.config.backupPrefixForAccount(session.accountId)
        val objects = buildList {
            var continuationToken: String? = null
            do {
                val result = client.listObjects(
                    prefix = "$accountPrefix/",
                    maxKeys = 1000,
                    continuationToken = continuationToken,
                ).getOrThrow()
                addAll(result.objects)
                continuationToken = result.nextContinuationToken
            } while (continuationToken != null)
        }

        objects
            .filter { session.config.isBackupKeyForAccount(session.accountId, it.key) }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem): Boolean = withContext(Dispatchers.IO) {
        val session = resolvedSession(config)
        val client = getS3Client(session.config)
        val key = requireAccountBackupKey(session.config, session.accountId, item.key)
        val backupFile = File.createTempFile("s3_restore_", ".zip", context.cacheDir)

        try {
            // Download backup file directly to file to avoid OOM
            Log.i(TAG, "restoreFromS3: Downloading ${backupFile.name}")
            client.downloadObjectToFile(key, backupFile).getOrThrow()

            Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")

            // Restore from backup file
            restoreFromBackupFile(backupFile, session.config)
        } finally {
            // Clean up temp file
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val session = resolvedSession(config)
        val client = getS3Client(session.config)
        val key = requireAccountBackupKey(session.config, session.accountId, item.key)
        client.deleteObject(key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted $key")
    }

    private suspend fun currentAccountId(): Long {
        return authTokenStore.profileFlow.first()?.id?.takeIf { it > 0 }
            ?: throw IllegalStateException("A signed-in profile is required for S3 backup")
    }

    private fun requireAccountBackupKey(config: S3Config, accountId: Long, key: String): String {
        require(config.isBackupKeyForAccount(accountId, key)) {
            "Backup object is outside the signed-in account namespace"
        }
        return key
    }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")

        if (backupFile.exists()) {
            backupFile.delete()
        }

        // Create zip file and backup data
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                // Keys are re-provisioned from the gateway on launch, so stripping them here keeps
                // spendable credentials out of the user's own cloud storage.
                content = json.encodeToString(
                    settingsStore.settingsFlow.value.forBackup()
                )
            )

            // Backup database files
            if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                check(DatabaseUtil.checkpoint(database)) {
                    "Unable to checkpoint database for backup"
                }
                val dbFile = context.getDatabasePath(AccountDatabaseManager.currentDatabaseName(context))
                check(dbFile.isFile) { "Database file does not exist" }
                addFileToZip(zipOut, dbFile, "rikka_hub.db")
            }

            // Backup app files
            if (config.items.contains(S3Config.BackupItem.FILES)) {
                val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                if (uploadFolder.exists() && uploadFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up files from ${uploadFolder.absolutePath}")
                    uploadFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.UPLOAD}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Upload folder does not exist or is not a directory")
                }

                val skillsFolder = File(context.filesDir, FileFolders.SKILLS)
                if (skillsFolder.exists() && skillsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up skills from ${skillsFolder.absolutePath}")
                    addDirectoryToZip(
                        zipOut = zipOut,
                        rootDir = skillsFolder,
                        currentDir = skillsFolder,
                        entryPrefix = "${FileFolders.SKILLS}/"
                    )
                } else {
                    Log.w(TAG, "prepareBackupFile: Skills folder does not exist or is not a directory")
                }

                val fontsFolder = File(context.filesDir, FileFolders.FONTS)
                if (fontsFolder.exists() && fontsFolder.isDirectory) {
                    Log.i(TAG, "prepareBackupFile: Backing up fonts from ${fontsFolder.absolutePath}")
                    fontsFolder.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            addFileToZip(zipOut, file, "${FileFolders.FONTS}/${file.name}")
                        }
                    }
                } else {
                    Log.w(TAG, "prepareBackupFile: Fonts folder does not exist or is not a directory")
                }
            }
        }

        Log.i(
            TAG,
            "prepareBackupFile: Created backup file ${backupFile.name} (${backupFile.length().fileSizeToString()})"
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")

        var stagedDatabase: File? = null
        try {
            ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                var entry: ZipEntry?
                while (zipIn.nextEntry.also { entry = it } != null) {
                    entry?.let { zipEntry ->
                        Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                        when (zipEntry.name) {
                            "settings.json" -> {
                                val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                try {
                                    val migratedJson = SettingsJsonMigrator.migrate(settingsJson)
                                    val settings = json.decodeFromString<Settings>(migratedJson)
                                    settingsStore.update { current ->
                                        settings.withLocalSyncConfiguration(current)
                                    }
                                    Log.i(TAG, "restoreFromBackupFile: Settings restored successfully")
                                } catch (e: Exception) {
                                    Log.e(TAG, "restoreFromBackupFile: Failed to restore settings", e)
                                    throw Exception("Failed to restore settings: ${e.message}")
                                }
                            }

                            "rikka_hub.db" -> {
                                if (config.items.contains(S3Config.BackupItem.DATABASE)) {
                                    check(stagedDatabase == null) { "Backup contains multiple database files" }
                                    val databaseFile = File.createTempFile("database_restore_", ".db", context.cacheDir)
                                    FileOutputStream(databaseFile).use { outputStream -> zipIn.copyTo(outputStream) }
                                    stagedDatabase = databaseFile
                                }
                            }

                            // A backup is created after checkpointing, so restoring WAL/SHM would replay
                            // stale writes against the staged main database on the next launch.
                            "rikka_hub-wal", "rikka_hub-shm" -> Unit

                            else -> {
                                if (config.items.contains(S3Config.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.UPLOAD}/")
                                ) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.UPLOAD}/")
                                    if (fileName.isNotEmpty()) {
                                        val uploadFolder = File(context.filesDir, FileFolders.UPLOAD)
                                        if (!uploadFolder.exists()) {
                                            uploadFolder.mkdirs()
                                            Log.i(TAG, "restoreFromBackupFile: Created upload directory")
                                        }

                                        val targetFile = uploadFolder.resolve(fileName).canonicalFile
                                        val uploadRoot = uploadFolder.canonicalFile
                                        require(
                                            targetFile.path.startsWith(uploadRoot.path + File.separator)
                                        ) { "Invalid upload file path: ${zipEntry.name}" }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}"
                                        )

                                        try {
                                            FileOutputStream(targetFile).use { outputStream ->
                                                zipIn.copyTo(outputStream)
                                            }
                                            Log.i(
                                                TAG,
                                                "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "restoreFromBackupFile: Failed to restore file ${zipEntry.name}", e)
                                            throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                        }
                                    }
                                } else if (config.items.contains(S3Config.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.SKILLS}/")
                                ) {
                                    restoreSkillEntry(zipIn, zipEntry.name)
                                } else if (config.items.contains(S3Config.BackupItem.FILES) &&
                                    zipEntry.name.startsWith("${FileFolders.FONTS}/")
                                ) {
                                    val fileName = zipEntry.name.substringAfter("${FileFolders.FONTS}/")
                                    if (fileName.isNotEmpty() && !fileName.contains('/')) {
                                        val fontsFolder = File(context.filesDir, FileFolders.FONTS).apply { mkdirs() }
                                        val targetFile = File(fontsFolder, fileName)
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    }
                                } else {
                                    Log.i(TAG, "restoreFromBackupFile: Skipping entry ${zipEntry.name}")
                                }
                            }
                        }

                        zipIn.closeEntry()
                    }
                }
            }

            stagedDatabase?.let { databaseFile ->
                DatabaseRestoreCoordinator.stage(context, databaseFile)
                Log.i(TAG, "restoreFromBackupFile: Staged database for next app start")
            }
            Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
            stagedDatabase != null
        } finally {
            stagedDatabase?.delete()
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            val zipEntry = ZipEntry(entryName)
            zipOut.putNextEntry(zipEntry)
            fis.copyTo(zipOut)
            zipOut.closeEntry()
            Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
        }
    }

    private fun addDirectoryToZip(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ) {
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(
                    zipOut = zipOut,
                    rootDir = rootDir,
                    currentDir = file,
                    entryPrefix = entryPrefix,
                )
            } else if (file.isFile) {
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                addFileToZip(zipOut, file, "$entryPrefix$relativePath")
            }
        }
    }

    private fun restoreSkillEntry(zipIn: ZipInputStream, entryName: String) {
        val relativePath = entryName.substringAfter("${FileFolders.SKILLS}/")
        val skillName = relativePath.substringBefore('/', missingDelimiterValue = "")
        val skillRelativePath = relativePath.substringAfter('/', missingDelimiterValue = "")

        if (skillName.isBlank() || skillRelativePath.isBlank()) {
            Log.w(TAG, "restoreFromBackupFile: Invalid skill entry $entryName")
            return
        }

        val skillsRoot = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            ?: throw Exception("Invalid skill directory: $entryName")
        val targetFile = SkillPaths.resolveSkillFile(skillDir, skillRelativePath)
            ?: throw Exception("Invalid skill file path: $entryName")

        skillDir.mkdirs()
        targetFile.parentFile?.mkdirs()

        try {
            FileOutputStream(targetFile).use { outputStream ->
                zipIn.copyTo(outputStream)
            }
            Log.i(TAG, "restoreFromBackupFile: Restored skill file $entryName (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromBackupFile: Failed to restore skill file $entryName", e)
            throw Exception("Failed to restore skill file $entryName: ${e.message}")
        }
    }

    private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
        val zipEntry = ZipEntry(name)
        zipOut.putNextEntry(zipEntry)
        zipOut.write(content.toByteArray())
        zipOut.closeEntry()
        Log.i(TAG, "addVirtualFileToZip: $name (${content.length} bytes)")
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)

private data class S3Session(
    val accountId: Long,
    val config: S3Config,
)
