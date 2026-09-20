package me.rerere.rikkahub.ui.activity

import android.app.Activity
import android.os.Bundle
import me.rerere.rikkahub.utils.handlePackageInstallResult

/**
 * Receives PackageInstaller callbacks in a visible activity so the system confirmation UI can be
 * started on Android 10+ (background activity starts from a BroadcastReceiver are blocked).
 */
class PackageInstallTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePackageInstallResult(this, intent)
        finish()
    }
}
