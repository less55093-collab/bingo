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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap
import me.rerere.rikkahub.IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val TAG = "GenerationFg"
private const val NO_RUN_TOKEN = -1L
private const val NO_CONVERSATION_REVISION = -1L

/** A request sent by [GenerationProtectionManager] to the Android foreground service. */
internal data class GenerationServiceRequest(
    val runToken: Long,
    val kind: GenerationKind,
    val conversationId: String?,
    val conversationRevision: Long,
)

/**
 * The service reports its lifecycle through this bridge instead of retaining a manager instance.
 *
 * Android may deliver callbacks from an old service instance after a newer run has started. Every
 * transition therefore carries [runToken], and the manager only acts on its currently owned token.
 */
internal sealed interface GenerationForegroundServiceState {
    data object Idle : GenerationForegroundServiceState

    data class Starting(val runToken: Long) : GenerationForegroundServiceState

    data class Active(val runToken: Long) : GenerationForegroundServiceState

    data class Failed(
        val runToken: Long,
        val message: String,
        val cause: Throwable? = null,
    ) : GenerationForegroundServiceState

    data class Stopped(val runToken: Long) : GenerationForegroundServiceState
}

/**
 * Small boundary around Android service calls. Keeping it injectable makes lease state transitions
 * testable without a device or a running Android [Service].
 */
internal interface GenerationForegroundServiceController {
    val state: StateFlow<GenerationForegroundServiceState>

    /** Terminal callbacks retained by run token so an old monitor cannot miss a stale callback. */
    val terminalStates: StateFlow<Map<Long, GenerationForegroundServiceState>>
        get() = EMPTY_TERMINAL_STATES

    /** Starts the service synchronously from the caller's user-action path. */
    fun start(request: GenerationServiceRequest): Result<Unit>

    /** Stops only the run identified by [runToken]. */
    fun stop(runToken: Long)

    /** Updates the active run's notification target without starting another service run. */
    fun updateConversation(
        runToken: Long,
        conversationRevision: Long,
        conversationId: String?,
    )

    /** Updates the active run's single ongoing notification with optional live progress. */
    fun updateProgress(
        runToken: Long,
        conversationRevision: Long,
        conversationId: String,
        title: String,
        status: String,
        content: String,
        chipText: String,
    )

    fun reportFailure(runToken: Long, message: String, cause: Throwable? = null)

    /** True only while Android should still honour a delayed ACTION_START for [runToken]. */
    fun canActivate(runToken: Long): Boolean
}

internal object AndroidGenerationForegroundServiceController : GenerationForegroundServiceController {
    private val mutableState = MutableStateFlow<GenerationForegroundServiceState>(
        GenerationForegroundServiceState.Idle,
    )

    override val state: StateFlow<GenerationForegroundServiceState> = mutableState.asStateFlow()

    private val terminalLock = Any()
    private val mutableTerminalStates = MutableStateFlow<Map<Long, GenerationForegroundServiceState>>(emptyMap())
    override val terminalStates: StateFlow<Map<Long, GenerationForegroundServiceState>> =
        mutableTerminalStates.asStateFlow()

    override fun start(request: GenerationServiceRequest): Result<Unit> {
        mutableState.value = GenerationForegroundServiceState.Starting(request.runToken)
        return runCatching {
            val context = GenerationForegroundService.appContext
                ?: error("Generation foreground service has no application context")
            ContextCompat.startForegroundService(
                context,
                GenerationForegroundService.startIntent(context, request),
            )
        }.onFailure { error ->
            reportFailure(
                request.runToken,
                "Unable to start background generation protection",
                error,
            )
            Log.e(TAG, "Foreground service start rejected for run=${request.runToken}", error)
        }
    }

    override fun stop(runToken: Long) {
        val context = GenerationForegroundService.appContext ?: return
        // Invalidate the token before Android delivers the stop action. Otherwise a queued
        // ACTION_START can arrive after its last lease was cancelled and activate an ownerless
        // foreground service.
        reportStopped(runToken)
        runCatching {
            // An explicit stop action avoids stopService() racing a newly started run.
            context.startService(GenerationForegroundService.stopIntent(context, runToken))
        }.onFailure { error ->
            Log.w(TAG, "Unable to request foreground service stop for run=$runToken", error)
        }
    }

    override fun updateConversation(
        runToken: Long,
        conversationRevision: Long,
        conversationId: String?,
    ) {
        val context = GenerationForegroundService.appContext ?: return
        runCatching {
            context.startService(
                GenerationForegroundService.updateConversationIntent(
                    context = context,
                    runToken = runToken,
                    conversationRevision = conversationRevision,
                    conversationId = conversationId,
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to update foreground notification for run=$runToken", error)
        }
    }

    override fun updateProgress(
        runToken: Long,
        conversationRevision: Long,
        conversationId: String,
        title: String,
        status: String,
        content: String,
        chipText: String,
    ) {
        val context = GenerationForegroundService.appContext ?: return
        runCatching {
            context.startService(
                GenerationForegroundService.updateProgressIntent(
                    context = context,
                    runToken = runToken,
                    conversationRevision = conversationRevision,
                    conversationId = conversationId,
                    title = title,
                    status = status,
                    content = content,
                    chipText = chipText,
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to update generation progress for run=$runToken", error)
        }
    }

    override fun reportFailure(runToken: Long, message: String, cause: Throwable?) {
        publishForCurrentRun(
            runToken,
            GenerationForegroundServiceState.Failed(runToken, message, cause),
        )
    }

    override fun canActivate(runToken: Long): Boolean {
        return mutableState.value.canActivate(runToken)
    }

    /**
     * Publishes ACTIVE only if this exact run is still STARTING.
     *
     * A lease can be cancelled after [GenerationForegroundService.startRun] checks [canActivate]
     * but before `startForeground()` returns. Allowing a same-token STOPPED state to transition back
     * to ACTIVE would resurrect an ownerless service and make its queued stop command a no-op.
     */
    internal fun reportActive(runToken: Long): Boolean = mutableState.tryActivate(runToken)

    internal fun reportStopped(runToken: Long) {
        val current = mutableState.value
        // Preserve a useful failure reason, and never let an old service instance overwrite a
        // newer STARTING/ACTIVE run.
        if (current is GenerationForegroundServiceState.Failed && current.runToken == runToken) return
        publishForCurrentRun(runToken, GenerationForegroundServiceState.Stopped(runToken))
    }

    private fun publishForCurrentRun(runToken: Long, next: GenerationForegroundServiceState) {
        if (next is GenerationForegroundServiceState.Failed || next is GenerationForegroundServiceState.Stopped) {
            synchronized(terminalLock) {
                // Preserve a useful failure if a later destroy callback reports only Stopped.
                val previous = mutableTerminalStates.value[runToken]
                if (previous !is GenerationForegroundServiceState.Failed || next is GenerationForegroundServiceState.Failed) {
                    val updated = LinkedHashMap(mutableTerminalStates.value)
                    updated[runToken] = next
                    while (updated.size > MAX_RETAINED_TERMINAL_STATES) {
                        updated.remove(updated.keys.first())
                    }
                    mutableTerminalStates.value = updated
                }
            }
        }
        while (true) {
            val current = mutableState.value
            if (current.runTokenOrNull() != runToken) {
                Log.d(TAG, "Ignoring stale foreground service callback for run=$runToken")
                return
            }
            if (mutableState.compareAndSet(current, next)) return
        }
    }
}

private val EMPTY_TERMINAL_STATES: StateFlow<Map<Long, GenerationForegroundServiceState>> =
    MutableStateFlow(emptyMap<Long, GenerationForegroundServiceState>()).asStateFlow()

private const val MAX_RETAINED_TERMINAL_STATES = 64

private fun GenerationForegroundServiceState.runTokenOrNull(): Long? = when (this) {
    GenerationForegroundServiceState.Idle -> null
    is GenerationForegroundServiceState.Starting -> runToken
    is GenerationForegroundServiceState.Active -> runToken
    is GenerationForegroundServiceState.Failed -> runToken
    is GenerationForegroundServiceState.Stopped -> runToken
}

/** A queued ACTION_START is valid only for the run the controller is still starting. */
internal fun GenerationForegroundServiceState.canActivate(runToken: Long): Boolean =
    this is GenerationForegroundServiceState.Starting && this.runToken == runToken

/**
 * An ownerless service instance can dispose of a queued command only when no viable run remains.
 *
 * `stopSelf(startId)` uses service-wide latest-start semantics. Calling it while the controller is
 * STARTING or ACTIVE can therefore terminate a replacement before its queued ACTION_START is
 * delivered. A terminal state means there is no replacement left to protect, regardless of which
 * token produced that terminal transition.
 */
internal fun GenerationForegroundServiceState.canCleanUpOwnerlessService(): Boolean =
    when (this) {
        GenerationForegroundServiceState.Idle,
        is GenerationForegroundServiceState.Failed,
        is GenerationForegroundServiceState.Stopped,
        -> true

        else -> false
    }

/** Atomically prevents a terminal or replacement state from being resurrected as ACTIVE. */
internal fun MutableStateFlow<GenerationForegroundServiceState>.tryActivate(runToken: Long): Boolean {
    while (true) {
        val current = value
        if (!current.canActivate(runToken)) return false
        if (compareAndSet(current, GenerationForegroundServiceState.Active(runToken))) return true
    }
}

/**
 * A failed `startForeground()` may race a replacement ACTION_START queued on the same service.
 * `stopSelf(startId)` is safe only while the service instance still owns the terminal command and
 * the controller has no viable STARTING/ACTIVE replacement.
 */
internal fun canStopOwnedTerminalRun(
    activeRunToken: Long,
    controllerState: GenerationForegroundServiceState,
    terminalRunToken: Long,
): Boolean = activeRunToken == terminalRunToken &&
    controllerState.canCleanUpOwnerlessService()

/** A delayed command from the same run may only move the notification target forward. */
internal fun canApplyConversationUpdate(
    activeRunToken: Long,
    activeConversationRevision: Long,
    updateRunToken: Long,
    updateConversationRevision: Long,
): Boolean = activeRunToken == updateRunToken &&
    updateConversationRevision > activeConversationRevision

/** Progress belongs to the exact target snapshot which was current when it was published. */
internal fun isCurrentConversationTarget(
    activeRunToken: Long,
    activeConversationRevision: Long,
    updateRunToken: Long,
    updateConversationRevision: Long,
): Boolean = activeRunToken == updateRunToken &&
    activeConversationRevision == updateConversationRevision

/**
 * Keeps either a chat stream or image request alive while the app is backgrounded.
 *
 * There is deliberately no arbitrary startup timeout: a foreground-service start is either
 * explicitly rejected or eventually reports ACTIVE/FAILED. The owner waits for that state before
 * opening a billable model request.
 */
class GenerationForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeRunToken = NO_RUN_TOKEN
    private var activeConversationId: String? = null
    private var activeConversationRevision = NO_CONVERSATION_REVISION

    override fun onCreate() {
        super.onCreate()
        appContext = this.applicationContext
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun(intent, startId)
            ACTION_STOP -> stopRun(intent, startId)
            ACTION_UPDATE_CONVERSATION -> updateConversation(intent)
            ACTION_UPDATE_PROGRESS -> updateProgress(intent)
            else -> Log.w(TAG, "Ignoring foreground service action=${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        handleTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout(startId)
    }

    override fun onDestroy() {
        releaseWakeLock()
        val runToken = activeRunToken
        activeRunToken = NO_RUN_TOKEN
        activeConversationId = null
        activeConversationRevision = NO_CONVERSATION_REVISION
        if (runToken != NO_RUN_TOKEN) {
            AndroidGenerationForegroundServiceController.reportStopped(runToken)
            Log.i(TAG, "Background protection stopped for run=$runToken")
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun startRun(intent: Intent, startId: Int) {
        val runToken = intent.getLongExtra(EXTRA_RUN_TOKEN, NO_RUN_TOKEN)
        if (runToken == NO_RUN_TOKEN) {
            Log.e(TAG, "Ignoring generation foreground service start without a run token")
            // This malformed command has no token with which to prove ownership. In particular,
            // it must not stop an already queued replacement run while this instance is ownerless.
            return
        }
        // ACTION_START is queued by Android. A cancelled older run can arrive after the manager
        // has already started a newer run, and must not replace its service ownership or make the
        // newer token's stop request a no-op.
        if (!AndroidGenerationForegroundServiceController.canActivate(runToken)) {
            Log.d(TAG, "Ignoring stale generation foreground service start for run=$runToken")
            // A replacement ACTION_START may already be queued behind this stale command. Calling
            // stopSelf(startId) here would use Android's latest-start semantics and can destroy
            // that replacement before it gets a chance to enter the foreground.
            val state = AndroidGenerationForegroundServiceController.state.value
            if (activeRunToken == NO_RUN_TOKEN && state.canCleanUpOwnerlessService()) {
                stopSelf(startId)
            }
            return
        }
        activeRunToken = runToken
        activeConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        activeConversationRevision = intent.getLongExtra(
            EXTRA_CONVERSATION_REVISION,
            NO_CONVERSATION_REVISION,
        )
        try {
            val notification = buildNotification(activeConversationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
            if (!AndroidGenerationForegroundServiceController.reportActive(runToken)) {
                // Cancellation or a replacement won the race while startForeground() was running.
                // Do not publish ACTIVE again. If there is no viable replacement run, dispose of
                // this service instance; otherwise its queued ACTION_START will take ownership.
                releaseWakeLock()
                val state = AndroidGenerationForegroundServiceController.state.value
                if (canStopOwnedTerminalRun(activeRunToken, state, runToken)) {
                    stopSelf(startId)
                } else {
                    Log.d(TAG, "Foreground activation superseded for run=$runToken")
                }
                return
            }
            Log.i(TAG, "Background protection active for run=$runToken")
        } catch (error: Exception) {
            AndroidGenerationForegroundServiceController.reportFailure(
                runToken,
                "Unable to activate background generation protection",
                error,
            )
            Log.e(TAG, "Unable to activate foreground service for run=$runToken", error)
            // Do not let a failing older ACTION_START stop a newer start request that Android
            // has already queued for this same service instance. The state transition above is
            // token-scoped, so a replacement leaves the controller in STARTING/ACTIVE(newToken).
            if (
                canStopOwnedTerminalRun(
                    activeRunToken = activeRunToken,
                    controllerState = AndroidGenerationForegroundServiceController.state.value,
                    terminalRunToken = runToken,
                )
            ) {
                stopSelf(startId)
            } else {
                Log.d(TAG, "Skipping stale failed foreground-service cleanup for run=$runToken")
            }
        }
    }

    private fun stopRun(intent: Intent, startId: Int) {
        val runToken = intent.getLongExtra(EXTRA_RUN_TOKEN, NO_RUN_TOKEN)
        if (activeRunToken == NO_RUN_TOKEN) {
            // A cancellation can race a queued start. It is safe to clean up only if the
            // controller has no viable STARTING or ACTIVE replacement. A terminal state from any
            // token means there is no replacement left to preserve.
            val state = AndroidGenerationForegroundServiceController.state.value
            if (state.canCleanUpOwnerlessService()) {
                Log.d(TAG, "Stopping terminal generation service run=$runToken before activation")
                stopSelf(startId)
            } else {
                Log.d(TAG, "Ignoring ownerless stale foreground service stop for run=$runToken")
            }
            return
        }
        if (runToken != activeRunToken) {
            Log.d(TAG, "Ignoring stale foreground service stop for run=$runToken")
            return
        }
        val state = AndroidGenerationForegroundServiceController.state.value
        if (canStopOwnedTerminalRun(activeRunToken, state, runToken)) {
            stopSelf(startId)
        } else {
            Log.d(TAG, "Ignoring superseded foreground service stop for run=$runToken")
        }
    }

    private fun updateConversation(intent: Intent) {
        val runToken = intent.getLongExtra(EXTRA_RUN_TOKEN, NO_RUN_TOKEN)
        val conversationRevision = intent.getLongExtra(
            EXTRA_CONVERSATION_REVISION,
            NO_CONVERSATION_REVISION,
        )
        if (
            !canApplyConversationUpdate(
                activeRunToken = activeRunToken,
                activeConversationRevision = activeConversationRevision,
                updateRunToken = runToken,
                updateConversationRevision = conversationRevision,
            )
        ) {
            Log.d(
                TAG,
                "Ignoring stale foreground notification update for run=$runToken " +
                    "revision=$conversationRevision",
            )
            return
        }
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        activeConversationId = conversationId
        activeConversationRevision = conversationRevision
        runCatching {
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification(conversationId))
        }.onFailure { error ->
            // A blocked notification must not tear down otherwise active generation protection.
            Log.w(TAG, "Unable to update generation notification for run=$runToken", error)
        }
    }

    private fun updateProgress(intent: Intent) {
        val runToken = intent.getLongExtra(EXTRA_RUN_TOKEN, NO_RUN_TOKEN)
        val conversationRevision = intent.getLongExtra(
            EXTRA_CONVERSATION_REVISION,
            NO_CONVERSATION_REVISION,
        )
        if (
            !isCurrentConversationTarget(
                activeRunToken = activeRunToken,
                activeConversationRevision = activeConversationRevision,
                updateRunToken = runToken,
                updateConversationRevision = conversationRevision,
            )
        ) {
            Log.d(
                TAG,
                "Ignoring stale foreground progress update for run=$runToken " +
                    "revision=$conversationRevision",
            )
            return
        }
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        if (conversationId != activeConversationId) {
            Log.d(TAG, "Ignoring progress for non-current conversation in run=$runToken")
            return
        }
        runCatching {
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    conversationId = conversationId,
                    title = intent.getStringExtra(EXTRA_PROGRESS_TITLE),
                    status = intent.getStringExtra(EXTRA_PROGRESS_STATUS),
                    content = intent.getStringExtra(EXTRA_PROGRESS_CONTENT),
                    chipText = intent.getStringExtra(EXTRA_PROGRESS_CHIP),
                ),
            )
        }.onFailure { error ->
            // A blocked notification must not tear down otherwise active generation protection.
            Log.w(TAG, "Unable to update generation progress notification for run=$runToken", error)
        }
    }

    private fun handleTimeout(startId: Int) {
        val runToken = activeRunToken
        if (runToken != NO_RUN_TOKEN) {
            AndroidGenerationForegroundServiceController.reportFailure(
                runToken,
                "Background generation protection timed out",
            )
            Log.w(TAG, "Foreground service timed out for run=$runToken")
        }
        val state = AndroidGenerationForegroundServiceController.state.value
        if (canStopOwnedTerminalRun(activeRunToken, state, runToken)) {
            stopSelf(startId)
        } else {
            Log.d(TAG, "Ignoring stale foreground service timeout for run=$runToken")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:generation",
        ).apply {
            // Safety cap for a lost stop signal. Normal completion releases this immediately.
            acquire(MAX_WAKE_LOCK_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun buildNotification(
        conversationId: String?,
        title: String? = null,
        status: String? = null,
        content: String? = null,
        chipText: String? = null,
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(this, IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(title ?: getString(R.string.notification_image_generation_in_progress))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, RouteActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        conversationId?.let { putExtra(EXTRA_CONVERSATION_ID, it) }
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        status?.let(builder::setSubText)
        content?.takeIf(String::isNotBlank)?.let { text ->
            builder.setContentText(text)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            builder.setRequestPromotedOngoing(true)
        }
        if (Build.VERSION.SDK_INT >= 36 && !chipText.isNullOrBlank()) {
            builder.setShortCriticalText(chipText)
        }
        return builder.build()
    }

    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.START_GENERATION_PROTECTION"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.STOP_GENERATION_PROTECTION"
        private const val ACTION_UPDATE_CONVERSATION =
            "me.rerere.rikkahub.action.UPDATE_GENERATION_CONVERSATION"
        private const val ACTION_UPDATE_PROGRESS =
            "me.rerere.rikkahub.action.UPDATE_GENERATION_PROGRESS"
        private const val EXTRA_RUN_TOKEN = "generationRunToken"
        private const val EXTRA_CONVERSATION_ID = "conversationId"
        private const val EXTRA_CONVERSATION_REVISION = "generationConversationRevision"
        private const val EXTRA_PROGRESS_TITLE = "generationProgressTitle"
        private const val EXTRA_PROGRESS_STATUS = "generationProgressStatus"
        private const val EXTRA_PROGRESS_CONTENT = "generationProgressContent"
        private const val EXTRA_PROGRESS_CHIP = "generationProgressChip"
        private const val NOTIFICATION_ID = 2002
        private const val MAX_WAKE_LOCK_MILLIS = 60 * 60 * 1000L

        @Volatile
        internal var appContext: Context? = null
            private set

        internal fun startIntent(context: Context, request: GenerationServiceRequest) = Intent(
            context,
            GenerationForegroundService::class.java,
        ).apply {
            action = ACTION_START
            putExtra(EXTRA_RUN_TOKEN, request.runToken)
            putExtra(EXTRA_CONVERSATION_ID, request.conversationId)
            putExtra(EXTRA_CONVERSATION_REVISION, request.conversationRevision)
        }

        internal fun stopIntent(context: Context, runToken: Long) = Intent(
            context,
            GenerationForegroundService::class.java,
        ).apply {
            action = ACTION_STOP
            putExtra(EXTRA_RUN_TOKEN, runToken)
        }

        internal fun updateConversationIntent(
            context: Context,
            runToken: Long,
            conversationRevision: Long,
            conversationId: String?,
        ) = Intent(context, GenerationForegroundService::class.java).apply {
            action = ACTION_UPDATE_CONVERSATION
            putExtra(EXTRA_RUN_TOKEN, runToken)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CONVERSATION_REVISION, conversationRevision)
        }

        internal fun updateProgressIntent(
            context: Context,
            runToken: Long,
            conversationRevision: Long,
            conversationId: String,
            title: String,
            status: String,
            content: String,
            chipText: String,
        ) = Intent(context, GenerationForegroundService::class.java).apply {
            action = ACTION_UPDATE_PROGRESS
            putExtra(EXTRA_RUN_TOKEN, runToken)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CONVERSATION_REVISION, conversationRevision)
            putExtra(EXTRA_PROGRESS_TITLE, title)
            putExtra(EXTRA_PROGRESS_STATUS, status)
            putExtra(EXTRA_PROGRESS_CONTENT, content)
            putExtra(EXTRA_PROGRESS_CHIP, chipText)
        }

        /** Called by DI before the service has been created for the first time. */
        internal fun initialize(context: Context) {
            appContext = context.applicationContext
        }
    }
}
