package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import kotlin.system.exitProcess

/**
 * Tiny activity in a separate process so the main process can die without also killing the
 * activity that is supposed to bring the app back. The white flash after login was caused by
 * starting RouteActivity in the same process and then immediately killing that process.
 */
class AppRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            val component = launchIntent.component
            if (component != null) {
                startActivity(Intent.makeRestartActivityTask(component))
            } else {
                startActivity(
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    )
                )
            }
        }
        finish()
        exitProcess(0)
    }
}

internal fun Context.isAppRestartProcess(): Boolean {
    val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        val pid = Process.myPid()
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName.orEmpty()
    }
    return processName.endsWith(APP_RESTART_PROCESS_SUFFIX)
}

internal fun restartApplication(context: Context) {
    val appContext = context.applicationContext
    val start = {
        appContext.startActivity(
            Intent(appContext, AppRestartActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
        )
        exitProcess(0)
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        start()
    } else {
        Handler(Looper.getMainLooper()).post(start)
    }
}

internal const val APP_RESTART_PROCESS_SUFFIX = ":restart"
