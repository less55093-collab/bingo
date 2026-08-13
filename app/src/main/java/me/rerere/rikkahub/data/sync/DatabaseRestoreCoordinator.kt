package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import me.rerere.rikkahub.data.db.AccountDatabaseManager

/**
 * Stages a downloaded database until the next cold app start.
 *
 * Room and its DAO singletons hold the current database open for the lifetime of this process.
 * Replacing its files in place can make the already-open connection observe a corrupt mixture of
 * old and new state, so this coordinator only swaps the database before Koin creates Room.
 */
internal object DatabaseRestoreCoordinator {
    private const val TAG = "DatabaseRestore"
    private const val DIRECTORY = "pending_database_restore"
    private const val STAGED_DATABASE = "rikka_hub.db"
    private const val MARKER = "pending"
    private const val TARGET_DATABASE = "target_database"

    fun stage(
        context: Context,
        source: File,
        targetDatabaseName: String = AccountDatabaseManager.currentDatabaseName(context),
    ) {
        require(source.isFile && source.length() > 0L) { "The backup database is empty" }
        require(isSQLiteDatabase(source)) { "The backup is not a SQLite database" }
        require(targetDatabaseName == "rikka_hub" || targetDatabaseName.matches(Regex("rikka_hub_user_[1-9][0-9]*"))) {
            "Invalid restore database target"
        }

        val directory = directory(context).apply { mkdirs() }
        val stagedFile = File(directory, STAGED_DATABASE)
        val temporaryFile = File(directory, "$STAGED_DATABASE.new")
        temporaryFile.delete()
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(temporaryFile.length() > 0L) { "The staged database is empty" }
            check(isSQLiteDatabase(temporaryFile)) { "The staged file is not a SQLite database" }
            replace(temporaryFile, stagedFile)
            File(directory, TARGET_DATABASE).writeText(targetDatabaseName, Charsets.US_ASCII)
            writeMarker(File(directory, MARKER))
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    /** Applies a complete staged restore before any Room database is opened. */
    fun applyPendingRestore(context: Context) {
        val directory = directory(context)
        val marker = File(directory, MARKER)
        if (!marker.exists()) return

        val stagedFile = File(directory, STAGED_DATABASE)
        if (!stagedFile.isFile || stagedFile.length() == 0L) {
            Log.w(TAG, "Discarding incomplete pending database restore")
            clear(directory)
            return
        }
        if (!isSQLiteDatabase(stagedFile)) {
            Log.w(TAG, "Discarding invalid pending database restore")
            clear(directory)
            return
        }

        val databaseName = File(directory, TARGET_DATABASE).takeIf { it.isFile }
            ?.readText(Charsets.US_ASCII)
            ?.takeIf { it == "rikka_hub" || it.matches(Regex("rikka_hub_user_[1-9][0-9]*")) }
        if (databaseName == null) {
            Log.w(TAG, "Discarding pending restore with invalid target")
            clear(directory)
            return
        }
        val target = context.getDatabasePath(databaseName)
        val targetDirectory = requireNotNull(target.parentFile)
        check(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "Unable to create database directory"
        }

        val replacement = File(targetDirectory, "$databaseName.restore")
        replacement.delete()
        try {
            FileInputStream(stagedFile).use { input ->
                FileOutputStream(replacement).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(replacement.length() > 0L) { "The restored database is empty" }
            check(isSQLiteDatabase(replacement)) { "The restored file is not a SQLite database" }

            // The archive is made after a WAL checkpoint. Never replay pre-restore WAL state.
            // Remove sidecars before making the restored main database visible: a crash after a
            // main-file replacement but before these deletes could otherwise replay old writes.
            File(targetDirectory, "$databaseName-wal").delete()
            File(targetDirectory, "$databaseName-shm").delete()
            replace(replacement, target)
            clear(directory)
            Log.i(TAG, "Applied pending database restore")
        } catch (error: Throwable) {
            replacement.delete()
            throw error
        }
    }

    private fun directory(context: Context): File {
        return File(context.applicationContext.noBackupFilesDir, DIRECTORY)
    }

    private fun writeMarker(marker: File) {
        val atomicFile = AtomicFile(marker)
        val output = atomicFile.startWrite()
        try {
            output.write(byteArrayOf(1))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun replace(source: File, destination: File) {
        // Both files live in the same database directory, so rename is the only operation that
        // preserves the old database if the replacement cannot be committed.
        check(source.renameTo(destination)) {
            "Unable to atomically replace ${destination.name}"
        }
    }

    private fun isSQLiteDatabase(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return FileInputStream(file).use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            var offset = 0
            while (offset < header.size) {
                val bytesRead = input.read(header, offset, header.size - offset)
                if (bytesRead < 0) return@use false
                offset += bytesRead
            }
            header.contentEquals(SQLITE_HEADER)
        }
    }

    private fun clear(directory: File) {
        directory.listFiles()?.forEach { it.delete() }
        directory.delete()
    }

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
}
