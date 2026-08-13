package me.rerere.rikkahub.data.db

import android.content.Context
import me.rerere.rikkahub.data.auth.AuthTokenStore
import java.io.File

object AccountDatabaseManager {
    private const val LEGACY_DATABASE = "rikka_hub"
    private const val NAMESPACE_MARKER = "account_database_namespace_v1"

    fun prepare(context: Context) {
        val marker = File(context.noBackupFilesDir, NAMESPACE_MARKER)
        if (marker.exists()) return
        val accountId = AuthTokenStore(context.applicationContext).profileBlocking()?.id?.takeIf { it > 0 }
        if (accountId != null) {
            val targetName = databaseName(accountId)
            val legacy = context.getDatabasePath(LEGACY_DATABASE)
            val target = context.getDatabasePath(targetName)
            if (!target.exists() && legacy.exists()) {
                requireNotNull(target.parentFile).mkdirs()
                moveIfPresent(legacy, target)
                moveIfPresent(File(legacy.path + "-wal"), File(target.path + "-wal"))
                moveIfPresent(File(legacy.path + "-shm"), File(target.path + "-shm"))
            }
        }

        marker.parentFile?.mkdirs()
        marker.writeText("1", Charsets.US_ASCII)
    }

    fun currentDatabaseName(context: Context): String {
        val accountId = AuthTokenStore(context.applicationContext).profileBlocking()?.id?.takeIf { it > 0 }
        return databaseName(accountId)
    }

    fun databaseName(accountId: Long?): String = if (accountId != null && accountId > 0) {
        "rikka_hub_user_$accountId"
    } else {
        LEGACY_DATABASE
    }

    fun belongsToAccount(databaseName: String, accountId: Long): Boolean {
        return databaseName == databaseName(accountId)
    }

    private fun moveIfPresent(source: File, target: File) {
        if (!source.exists()) return
        check(source.renameTo(target)) { "Unable to assign existing chat database to signed-in account" }
    }
}
