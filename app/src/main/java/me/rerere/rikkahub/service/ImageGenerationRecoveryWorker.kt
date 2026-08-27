package me.rerere.rikkahub.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.AccountRepository
import java.util.concurrent.TimeUnit

/** Durable wake-up for image-task reconciliation after process death, reboot, or a lost FGS. */
internal enum class ImageGenerationRecoveryDecision {
    RESCHEDULE,
    SUCCESS,
}

internal fun imageGenerationRecoveryDecision(hasPendingTasks: Boolean): ImageGenerationRecoveryDecision =
    if (hasPendingTasks) ImageGenerationRecoveryDecision.RESCHEDULE else ImageGenerationRecoveryDecision.SUCCESS

internal val imageGenerationRecoveryWorkPolicy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

internal object ImageGenerationRecoveryScheduler {
    private const val WORK_NAME = "image-generation-recovery"
    private const val RECOVERY_HEARTBEAT_SECONDS = 30L

    fun enqueue(context: Context, delayedHeartbeat: Boolean = false) {
        val requestBuilder = OneTimeWorkRequestBuilder<ImageGenerationRecoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            // This work is a durable heartbeat, not a business timeout. A fixed interval keeps a
            // task from sleeping for hours after process death while still letting the foreground
            // generation job do the actual long-running request.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
        if (delayedHeartbeat) {
            requestBuilder.setInitialDelay(RECOVERY_HEARTBEAT_SECONDS, TimeUnit.SECONDS)
        }
        val request = requestBuilder.build()
        // Replacing this short bridge closes the check/enqueue race without appending a new node
        // behind a worker that may already be retrying. The actual request runs in AppScope, so
        // replacing the bridge cannot cancel image generation.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            imageGenerationRecoveryWorkPolicy,
            request,
        )
    }
}

class ImageGenerationRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
    private val manager: ImageGenerationManager,
    private val accountRepository: AccountRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            // This foreground worker is only a short bridge which lets a background process wake
            // and request the dedicated generation FGS. ImageGenerationManager starts recovery in
            // AppScope before returning; it never ties image completion to this Worker's lifetime.
            try {
                setForeground(getForegroundInfo())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The dedicated generation service is the actual owner. A notification/bridge
                // promotion failure is recoverable through the durable retry below.
            }
            if (!manager.hasPendingTasks()) return Result.success()
            // This worker can start before RouteActivity. Reconcile locked provider settings and
            // rotated image keys here so a stale key cannot turn a recoverable task into a false
            // terminal auth failure. A provisioning error retains the pending task for the next
            // fixed-backoff wake.
            if (!accountRepository.ensureKeysProvisioned()) return reschedule()
            manager.recoverPendingTasks()
            // Keep a durable WorkManager wake-up pending while a record remains. The in-process
            // generation job is de-duplicated by request id, so heartbeats only matter after
            // process death or service loss and do not start a second upstream request in a healthy
            // process.
            when (imageGenerationRecoveryDecision(manager.hasPendingTasks())) {
                ImageGenerationRecoveryDecision.RESCHEDULE -> {
                    // A retry result uses WorkManager's increasing backoff and can eventually sleep
                    // for hours. Replace this bridge with a fresh fixed-delay heartbeat instead;
                    // replacing the worker does not touch the AppScope generation job.
                    reschedule()
                }
                ImageGenerationRecoveryDecision.SUCCESS -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Pending records are deliberately retained for a fixed-delay wake-up.
            reschedule()
        }
    }

    private fun reschedule(): Result {
        ImageGenerationRecoveryScheduler.enqueue(
            applicationContext,
            delayedHeartbeat = true,
        )
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext,
            IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(applicationContext.getString(R.string.notification_image_generation_in_progress))
            .setContentText(applicationContext.getString(R.string.notification_image_generation_in_progress))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 2003
    }
}
