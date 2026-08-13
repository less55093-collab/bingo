package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ChatBackupWorker(
    appContext: Context,
    params: WorkerParameters,
    private val sync: ChatBackupSync,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching { sync.uploadNow() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
