package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R

private const val TAG = "ImageGenerationFg"

/** Keeps an in-flight image request alive when the app moves to the background. */
class ImageGenerationForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            acquireWakeLock()
            protectionState.value = ProtectionState.ACTIVE
            Log.i(TAG, "Background protection is active")
        } catch (e: Exception) {
            protectionState.value = ProtectionState.FAILED
            Log.e(TAG, "Unable to protect image generation in the background", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        protectionState.value = ProtectionState.STOPPED
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Background protection stopped")
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:image-generation",
        ).apply {
            // Safety cap for a lost stop signal. Normal completion releases this immediately.
            acquire(MAX_WAKE_LOCK_MILLIS)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(
        this,
        IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_image_generation_in_progress))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                packageManager.getLaunchIntentForPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .build()

    companion object {
        private const val NOTIFICATION_ID = 2002
        private const val MAX_WAKE_LOCK_MILLIS = 10 * 60 * 1000L
        private const val START_TIMEOUT_MILLIS = 3_000L
        private val protectionState = MutableStateFlow(ProtectionState.STOPPED)

        suspend fun startAndAwait(context: Context): Boolean {
            if (protectionState.value == ProtectionState.ACTIVE) return true
            if (!requestStart(context)) return false
            return withTimeoutOrNull(START_TIMEOUT_MILLIS) {
                protectionState.first { it != ProtectionState.STARTING }
            } == ProtectionState.ACTIVE
        }

        private fun requestStart(context: Context): Boolean = try {
            if (protectionState.value == ProtectionState.STARTING) return true
            protectionState.value = ProtectionState.STARTING
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, ImageGenerationForegroundService::class.java),
            )
            true
        } catch (e: Exception) {
            protectionState.value = ProtectionState.FAILED
            // Starting from the background can be rejected on recent Android versions. The request
            // must wait for the app to return instead of running without protection.
            Log.e(TAG, "Failed to start image generation foreground service", e)
            false
        }

        fun stop(context: Context) {
            protectionState.value = ProtectionState.STOPPED
            context.applicationContext.stopService(
                Intent(context.applicationContext, ImageGenerationForegroundService::class.java)
            )
        }
    }

    private enum class ProtectionState {
        STOPPED,
        STARTING,
        ACTIVE,
        FAILED,
    }
}
