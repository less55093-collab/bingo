package me.rerere.rikkahub.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import me.rerere.rikkahub.ui.activity.PackageInstallTrampolineActivity
import okhttp3.CacheControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AccountDatabaseManager
import me.rerere.rikkahub.data.sync.forBackup
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 更新检查地址, 由 app/build.gradle.kts 的 buildConfigField("UPDATE_URL") 配置.
 * 为空表示未配置更新源, 此时不发起任何网络请求.
 */
private val API_URL: String = BuildConfig.UPDATE_URL

class UpdateChecker(
    private val client: OkHttpClient,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    /** 是否配置了更新源. 未配置时调用方应直接跳过更新相关的 UI. */
    val isEnabled: Boolean = API_URL.isNotBlank()

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        if (!isEnabled) {
            // 未配置更新源: 不发请求, 也不产生错误态, UI 侧不显示任何更新入口
            emit(UiState.Idle)
            return@flow
        }
        emit(UiState.Loading)
        emit(
            UiState.Success(
                data = try {
                    val response = client.newCall(
                        Request.Builder()
                            .url(API_URL)
                            .get()
                            .addHeader(
                                "User-Agent",
                                "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                            )
                            .build()
                    ).await()
                    if (response.isSuccessful) {
                        json.decodeFromString<UpdateInfo>(response.body.string())
                    } else {
                        throw Exception("Failed to fetch update info")
                    }
                } catch (e: Exception) {
                    throw Exception("Failed to fetch update info", e)
                }
            )
        )
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a verified same-package update and hands it to the system installer.
     * A lightweight backup is best-effort only so a large or failing snapshot cannot block install.
     */
    suspend fun downloadAndInstall(context: Context, download: UpdateDownload): Result<Unit> = runCatching {
        val appContext = context.applicationContext
        ensureCanRequestPackageInstalls(appContext)
        val notification = UpdateProgressNotification(appContext)
        val expectedHash = download.sha256?.lowercase()?.takeIf { it.matches(SHA256_PATTERN) }
            ?: error("更新包缺少有效的 SHA-256 校验值")
        val apk = downloadApk(appContext, download.url, notification)
        try {
            notification.showPreparingInstall()
            check(apk.sha256Hex() == expectedHash) { "更新包校验失败" }
            verifyApk(appContext, apk)
            runCatching { createBackup(appContext) }
                .onFailure { error -> Log.w(TAG, "pre-install backup failed", error) }
            notification.showOpeningInstaller()
            launchInstaller(appContext, apk)
            notification.cancel()
        } catch (error: Throwable) {
            apk.delete()
            throw error
        }
    }.onFailure { error ->
        UpdateProgressNotification(context.applicationContext).showFailure(error.message)
    }

    fun canShowDownloadProgress(context: Context): Boolean =
        UpdateProgressNotification(context).canShow

    private suspend fun downloadApk(
        context: Context,
        url: String,
        notification: UpdateProgressNotification,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").also { it.mkdirs() }
        val partial = File(directory, "pending.apk.part")
        val apk = File(directory, "pending.apk")
        partial.delete()
        notification.showDownloadProgress(progress = null)
        try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .get()
                    .build()
            ).await().use { response ->
                check(response.isSuccessful) { "下载更新包失败：HTTP ${response.code}" }
                val totalBytes = response.body.contentLength()
                FileOutputStream(partial).use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        var lastProgress: Int? = null
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            val progress = calculateDownloadProgress(downloadedBytes, totalBytes)
                            if (progress != null && progress != lastProgress) {
                                notification.showDownloadProgress(progress)
                                lastProgress = progress
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            apk.delete()
            check(partial.renameTo(apk)) { "无法保存更新包" }
            apk
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    private fun verifyApk(context: Context, apk: File) {
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfoCompat(apk.absolutePath)
            ?: error("更新包不是有效的 Android 安装包")
        check(archive.packageName == context.packageName) { "更新包不属于当前应用" }
        check(archive.versionCodeCompat() > BuildConfig.VERSION_CODE.toLong()) { "更新包版本未高于当前版本" }
        val installed = manager.getPackageInfoCompat(context.packageName)
        val archiveHashes = archive.signingHashes()
        val installedHashes = installed.signingHashes()
        check(archiveHashes.intersect(installedHashes).isNotEmpty()) { "更新包签名不一致" }
    }

    private suspend fun createBackup(context: Context): File = withContext(Dispatchers.IO) {
        DatabaseUtil.checkpoint(database)
        // Keep the safety backup in app-internal storage so it survives an in-place update.
        val root = context.filesDir.resolve("update-backups")
        check(root.exists() || root.mkdirs()) { "无法创建更新备份目录" }
        val output = File(root, "before_update_${System.currentTimeMillis()}.zip")
        check(!output.exists()) { "更新备份文件已存在" }
        val partial = File.createTempFile("before_update_", ".zip.part", root)
        try {
            FileOutputStream(partial).use { fileOutput ->
                val zip = ZipOutputStream(fileOutput)
                try {
                    zip.writeText("settings.json", json.encodeToString(settingsStore.settingsFlow.value.forBackup()))
                    context.getDatabasePath(AccountDatabaseManager.currentDatabaseName(context))
                        .takeIf(File::isFile)
                        ?.let { zip.writeFile(it, "rikka_hub.db") }
                    // Media folders can be hundreds of MB and used to stall the installer at 100%.
                    // Settings plus the live database are enough to recover after a failed overlay.
                    zip.finish()
                    zip.flush()
                    fileOutput.fd.sync()
                } finally {
                    zip.close()
                }
            }
            check(partial.length() > 0L) { "更新备份为空" }
            check(partial.renameTo(output)) { "无法保存更新备份" }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        root.listFiles()?.filter { it.name.startsWith("before_update_") && it.extension == "zip" }
            ?.sortedByDescending(File::lastModified)?.drop(3)?.forEach(File::delete)
        output
    }

    private fun launchInstaller(context: Context, apk: File) {
        runCatching { installWithPackageInstaller(context, apk) }
            .onFailure { error -> Log.w(TAG, "PackageInstaller session failed, falling back to ACTION_VIEW", error) }
            .recoverCatching { installWithViewIntent(context, apk) }
            .getOrThrow()
    }

    private fun installWithPackageInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("package", 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId,
                Intent(context, PackageInstallTrampolineActivity::class.java).apply {
                    action = ACTION_PACKAGE_INSTALLED
                    data = Uri.parse("package-install://$sessionId")
                },
                pendingFlags,
            )
            session.commit(pendingIntent.intentSender)
        } catch (error: Throwable) {
            session.abandon()
            throw error
        } finally {
            session.close()
        }
    }

    private fun installWithViewIntent(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        check(resolved.isNotEmpty()) { "没有可用的安装程序，请允许本应用安装未知应用后重试" }
        resolved.forEach { resolve ->
            context.grantUriPermission(
                resolve.activityInfo.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        context.startActivity(intent)
    }

    private fun ensureCanRequestPackageInstalls(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
            return
        }
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        error("请允许本应用安装更新后重试")
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo = getPackageInfo(
        packageName,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
    )

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageArchiveInfoCompat(path: String): PackageInfo? {
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = getPackageArchiveInfo(path, signingFlags)
            ?: getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
            ?: return null
        info.applicationInfo?.apply {
            sourceDir = path
            publicSourceDir = path
        }
        return info
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun PackageInfo.signingHashes(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.let { info ->
                if (info.hasMultipleSigners()) info.apkContentsSigners
                else info.signingCertificateHistory ?: info.apkContentsSigners
            } ?: signatures
        } else {
            signatures
        }
        check(!signatures.isNullOrEmpty()) { "无法读取安装包签名" }
        return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString() }.toSet()
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.toHexString(): String = joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun ZipOutputStream.writeText(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.writeFile(file: File, name: String) {
        putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

internal const val ACTION_PACKAGE_INSTALLED = "me.rerere.rikkahub.action.PACKAGE_INSTALLED"

internal fun handlePackageInstallResult(context: Context, intent: Intent): Boolean {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    return when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
            val confirm = intent.installConfirmIntent() ?: return false
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
            true
        }
        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> true
        else -> {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            UpdateProgressNotification(context).showFailure(message)
            false
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.installConfirmIntent(): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

internal fun calculateDownloadProgress(downloadedBytes: Long, totalBytes: Long): Int? {
    if (downloadedBytes < 0L || totalBytes <= 0L) return null
    return ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100)
        .toInt()
        .coerceIn(0, 100)
}

private class UpdateProgressNotification(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    val canShow: Boolean
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (!manager.areNotificationsEnabled()) return false
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                manager.getNotificationChannel(UPDATE_NOTIFICATION_CHANNEL_ID)?.importance !=
                NotificationManager.IMPORTANCE_NONE
        }

    fun showDownloadProgress(progress: Int?) {
        val content = progress?.let {
            context.getString(R.string.update_notification_progress, it)
        } ?: context.getString(R.string.update_notification_connecting)
        notify(
            baseBuilder()
                .setContentText(content)
                .setProgress(100, progress ?: 0, progress == null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        )
    }

    fun showPreparingInstall() {
        notify(
            baseBuilder()
                .setContentText(context.getString(R.string.update_notification_preparing))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        )
    }

    fun showOpeningInstaller() {
        notify(
            baseBuilder()
                .setContentText(context.getString(R.string.update_notification_installing))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        )
    }

    fun showFailure(message: String?) {
        val detail = message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.update_card_install_failed)
        val content = context.getString(R.string.update_notification_failed, detail)
        notify(
            baseBuilder()
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
        )
    }

    fun cancel() {
        manager.cancel(UPDATE_NOTIFICATION_ID)
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            0,
                            launchIntent,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                }
            }

    @SuppressLint("MissingPermission")
    private fun notify(builder: NotificationCompat.Builder) {
        if (canShow) manager.notify(UPDATE_NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val UPDATE_NOTIFICATION_ID = 2003
    }
}

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String,
    val sha256: String? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
