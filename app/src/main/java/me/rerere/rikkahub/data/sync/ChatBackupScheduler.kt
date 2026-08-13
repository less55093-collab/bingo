package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.AppDatabase
import java.util.concurrent.TimeUnit

class ChatBackupScheduler(
    private val context: Context,
    private val database: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun start() {
        scope.launch {
            database.invalidationTracker.createFlow(
                "conversationentity",
                "message_node",
                "managed_files",
                "favorites",
                "conversation_folder",
                "genmediaentity",
                "workspaces",
            ).drop(1).collect { enqueue(immediate = false) }
        }
        enqueue(immediate = true)
        val periodic = PeriodicWorkRequestBuilder<ChatBackupWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
    }

    fun enqueue(immediate: Boolean) {
        val request = OneTimeWorkRequestBuilder<ChatBackupWorker>()
            .setConstraints(constraints)
            .setInitialDelay(if (immediate) 0 else 45, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UPLOAD_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        private const val UPLOAD_WORK = "automatic-chat-backup-upload"
        private const val PERIODIC_WORK = "automatic-chat-backup-periodic"
    }
}
