package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AccountDatabaseManager
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.sync.ChatBackupScheduler
import me.rerere.rikkahub.data.sync.ChatBackupSync
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.DatabaseRestoreCoordinator
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.GenerationProtectionManager
import me.rerere.rikkahub.service.GenerationRecoveryGate
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID = "image_generation"
const val UPDATE_NOTIFICATION_CHANNEL_ID = "app_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AccountDatabaseManager.prepare(this)
        DatabaseRestoreCoordinator.applyPendingRestore(this)
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        recoverInterruptedGenerations()

        // The local web server has no UI in this build; ensure a stale
        // enabled flag from an older install cannot start it.
        stopWebServer()

        // 退到后台时把 WAL 回写主库, 保证系统自动备份能拿到完整聊天记录
        registerDatabaseCheckpoint()
        registerAutomaticChatBackup()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun registerDatabaseCheckpoint() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // An image request can be active without a ChatService session. Truncating WAL in
                // either case risks contending with a streaming snapshot write.
                if (get<GenerationProtectionManager>().hasActiveLeases()) return
                get<AppScope>().launch(Dispatchers.IO) {
                    DatabaseUtil.checkpoint(get<AppDatabase>())
                }
            }
        })
    }

    private fun registerAutomaticChatBackup() {
        val scheduler = get<ChatBackupScheduler>()
        scheduler.start()
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching { get<ChatBackupSync>().restoreIfLocalEmpty() }
                .onSuccess { restored -> if (restored) get<ChatBackupSync>().restartApp() }
                .onFailure { error -> Log.w(TAG, "automatic chat restore failed", error) }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                scheduler.enqueue(immediate = true)
            }
        })
    }

    private fun recoverInterruptedGenerations() {
        get<AppScope>().launch(Dispatchers.IO) {
            try {
                runCatching { get<ConversationRepository>().recoverInterruptedGenerations() }
                    .onSuccess { count ->
                        if (count > 0) Log.i(TAG, "recovered $count interrupted generation(s)")
                    }
                    .onFailure { error -> Log.w(TAG, "interrupted generation recovery failed", error) }
            } finally {
                get<GenerationRecoveryGate>().complete()
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        // The physical upload directory predates account namespaces. Scanning it into an
        // authenticated database would register files left by another account on this device.
        if (get<AuthTokenStore>().profileBlocking()?.id?.let { it > 0 } == true) return
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun stopWebServer() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val settings = store.settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    store.update(settings.copy(webServerEnabled = false))
                }
                stopService(Intent(this@RikkaHubApp, WebServerService::class.java))
            }.onFailure {
                Log.e(TAG, "stopWebServer failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val imageGenerationChannel = NotificationChannelCompat
            .Builder(IMAGE_GENERATION_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_image_generation))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(imageGenerationChannel)

        val updateChannel = NotificationChannelCompat
            .Builder(UPDATE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_app_update))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(updateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
