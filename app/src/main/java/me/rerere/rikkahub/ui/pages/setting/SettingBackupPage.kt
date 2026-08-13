package me.rerere.rikkahub.ui.pages.setting

import android.app.Activity
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.CloudUpload
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingBackupPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var config by remember(settings.s3Config) {
        mutableStateOf(settings.s3Config.copy(accessKeyId = "", secretAccessKey = ""))
    }
    var accessKeyId by remember { mutableStateOf("") }
    var secretAccessKey by remember { mutableStateOf("") }
    var hasCredentials by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    var busyAction by remember { mutableStateOf<BackupAction?>(null) }
    var backups by remember { mutableStateOf<List<S3BackupItem>>(emptyList()) }
    var hasLoadedBackups by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<S3BackupItem?>(null) }
    var deleteTarget by remember { mutableStateOf<S3BackupItem?>(null) }
    var restartRequired by remember { mutableStateOf(false) }
    val unknownError = stringResource(R.string.backup_page_unknown_error)
    val connectionSuccess = stringResource(R.string.backup_page_connection_success)
    val backupSuccess = stringResource(R.string.backup_page_backup_success)
    val restoreSuccess = stringResource(R.string.backup_page_restore_success)
    val restartDescription = stringResource(R.string.backup_page_restart_desc)
    val deleteSuccess = stringResource(R.string.backup_page_delete_success)

    LaunchedEffect(Unit) {
        runCatching { vm.loadS3Credentials() }
            .onSuccess { credentials ->
                credentials?.let {
                    accessKeyId = it.accessKeyId
                    secretAccessKey = it.secretAccessKey
                    hasCredentials = true
                }
            }
            .onFailure { toaster.show(it.message ?: unknownError, ToastType.Error) }
    }

    fun saveConfig(updated: S3Config) {
        val sanitized = updated.copy(accessKeyId = "", secretAccessKey = "")
        config = sanitized
        vm.updateS3Config(sanitized)
    }

    fun runAction(action: BackupAction, block: suspend () -> Unit) {
        if (busyAction != null) return
        busyAction = action
        scope.launch {
            runCatching { block() }
                .onFailure { toaster.show(it.message ?: unknownError, ToastType.Error) }
            busyAction = null
        }
    }

    fun refreshBackups() {
        runAction(BackupAction.REFRESH) {
            check(hasCredentials) { "请先保存 OSS 凭据" }
            backups = vm.listS3Backups(config)
            hasLoadedBackups = true
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.backup_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("s3Config") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.backup_page_s3_backup)) },
                ) {
                    item(
                        leadingContent = { Icon(HugeIcons.CloudServer, null) },
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_endpoint)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = config.endpoint,
                                onValueChange = { saveConfig(config.copy(endpoint = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("https://s3.oss-cn-shenzhen.aliyuncs.com") },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_bucket)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = config.bucket,
                                onValueChange = { saveConfig(config.copy(bucket = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_region)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = config.region,
                                onValueChange = { saveConfig(config.copy(region = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("cn-shenzhen") },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("对象前缀") },
                        supportingContent = {
                            OutlinedTextField(
                                value = config.prefix,
                                onValueChange = { saveConfig(config.copy(prefix = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("android") },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_path_style)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_s3_path_style_desc)) },
                        trailingContent = {
                            Switch(
                                checked = config.pathStyle,
                                onCheckedChange = { saveConfig(config.copy(pathStyle = it)) },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_chat_records)) },
                        trailingContent = {
                            Switch(
                                checked = config.items.contains(S3Config.BackupItem.DATABASE),
                                onCheckedChange = { enabled ->
                                    saveConfig(config.copy(items = config.items.withItem(S3Config.BackupItem.DATABASE, enabled)))
                                },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_files)) },
                        supportingContent = { Text("上传的文件、技能和字体") },
                        trailingContent = {
                            Switch(
                                checked = config.items.contains(S3Config.BackupItem.FILES),
                                onCheckedChange = { enabled ->
                                    saveConfig(config.copy(items = config.items.withItem(S3Config.BackupItem.FILES, enabled)))
                                },
                            )
                        },
                    )
                }
            }

            item("actions") {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                runAction(BackupAction.TEST) {
                                    check(hasCredentials) { "请先保存 OSS 凭据" }
                                    vm.testS3(config)
                                    toaster.show(connectionSuccess, ToastType.Success)
                                }
                            },
                            enabled = busyAction == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            ActionIcon(action = BackupAction.TEST, busyAction = busyAction)
                            Text(stringResource(R.string.backup_page_test_connection))
                        }
                        Button(
                            onClick = {
                                runAction(BackupAction.BACKUP) {
                                    check(hasCredentials) { "请先保存 OSS 凭据" }
                                    vm.backupToS3(config)
                                    toaster.show(backupSuccess, ToastType.Success)
                                    backups = vm.listS3Backups(config)
                                    hasLoadedBackups = true
                                }
                            },
                            enabled = busyAction == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            ActionIcon(action = BackupAction.BACKUP, busyAction = busyAction)
                            Text(stringResource(R.string.backup_page_backup_now))
                        }
                    }
                }
            }

            item("credentials") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("访问凭据") },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_access_key_id)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = accessKeyId,
                                onValueChange = {
                                    accessKeyId = it
                                    hasCredentials = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_secret_access_key)) },
                        supportingContent = {
                            OutlinedTextField(
                                value = secretAccessKey,
                                onValueChange = {
                                    secretAccessKey = it
                                    hasCredentials = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showSecret) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                            )
                        },
                        trailingContent = {
                            Switch(checked = showSecret, onCheckedChange = { showSecret = it })
                        },
                    )
                    item(
                        headlineContent = { Text("保存凭据") },
                        supportingContent = {
                            Text(
                                if (hasCredentials) "凭据已保存在本机加密存储中，不会随备份上传。"
                                else "填写后保存；凭据不会写入设置或备份文件。"
                            )
                        },
                        trailingContent = {
                            FilledTonalButton(
                                onClick = {
                                    runAction(BackupAction.SAVE_CREDENTIALS) {
                                        vm.saveS3Credentials(accessKeyId.trim(), secretAccessKey.trim())
                                        hasCredentials = true
                                        toaster.show("凭据已保存", ToastType.Success)
                                    }
                                },
                                enabled = busyAction == null &&
                                    accessKeyId.isNotBlank() &&
                                    secretAccessKey.isNotBlank(),
                            ) {
                                ActionIcon(action = BackupAction.SAVE_CREDENTIALS, busyAction = busyAction)
                                Text("保存")
                            }
                        },
                    )
                    if (hasCredentials) {
                        item(
                            headlineContent = { Text("移除凭据") },
                            supportingContent = { Text("移除后无法继续连接此 OSS/S3 配置。") },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        runAction(BackupAction.CLEAR_CREDENTIALS) {
                                            vm.clearS3Credentials()
                                            accessKeyId = ""
                                            secretAccessKey = ""
                                            hasCredentials = false
                                        }
                                    },
                                    enabled = busyAction == null,
                                ) {
                                    if (busyAction == BackupAction.CLEAR_CREDENTIALS) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(HugeIcons.Delete01, contentDescription = "移除凭据")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item("backupsHeader") {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.backup_page_s3_backup_files),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::refreshBackups, enabled = busyAction == null) {
                        if (busyAction == BackupAction.REFRESH) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.backup_page_s3_backup_files))
                        }
                    }
                }
            }

            if (hasLoadedBackups && backups.isEmpty()) {
                item("emptyBackups") {
                    Text(
                        text = "没有云端备份",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(backups, key = { it.key }) { backup ->
                ListItem(
                    headlineContent = { Text(backup.displayName) },
                    supportingContent = { Text("${backup.size.fileSizeToString()}  |  ${backup.lastModified.toLocalDateTime()}") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { restoreTarget = backup }, enabled = busyAction == null) {
                                Icon(HugeIcons.DatabaseRestore, contentDescription = stringResource(R.string.backup_page_restore))
                            }
                            IconButton(onClick = { deleteTarget = backup }, enabled = busyAction == null) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.backup_page_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    colors = CustomColors.listItemColors,
                )
            }
        }
    }

    RikkaConfirmDialog(
        show = restoreTarget != null,
        title = stringResource(R.string.backup_page_restore),
        confirmText = stringResource(android.R.string.ok),
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = { restoreTarget = null },
        onConfirm = {
            val target = restoreTarget ?: return@RikkaConfirmDialog
            restoreTarget = null
            runAction(BackupAction.RESTORE) {
                val databaseRestored = vm.restoreS3Backup(config, target)
                restartRequired = databaseRestored
                toaster.show(
                    if (databaseRestored) restartDescription else restoreSuccess,
                    ToastType.Success,
                )
            }
        },
        text = { Text("恢复会覆盖已选择的本地数据；聊天记录恢复后需关闭并重新打开应用。") },
    )

    RikkaConfirmDialog(
        show = restartRequired,
        title = stringResource(R.string.backup_page_restore_success),
        confirmText = stringResource(R.string.backup_page_restart_app),
        dismissText = stringResource(android.R.string.ok),
        onDismiss = { restartRequired = false },
        onConfirm = {
            restartRequired = false
            val activity = context as? Activity ?: return@RikkaConfirmDialog
            activity.finishAffinity()
            Process.killProcess(Process.myPid())
        },
        text = { Text(stringResource(R.string.backup_page_restart_desc)) },
    )

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.backup_page_delete),
        confirmText = stringResource(R.string.backup_page_delete),
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = { deleteTarget = null },
        onConfirm = {
            val target = deleteTarget ?: return@RikkaConfirmDialog
            deleteTarget = null
            runAction(BackupAction.DELETE) {
                vm.deleteS3Backup(config, target)
                backups = backups.filterNot { it.key == target.key }
                toaster.show(deleteSuccess, ToastType.Success)
            }
        },
        text = { Text("删除后无法恢复：${deleteTarget?.displayName.orEmpty()}") },
    )
}

@Composable
private fun ActionIcon(action: BackupAction, busyAction: BackupAction?) {
    if (busyAction == action) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    } else {
        val icon = when (action) {
            BackupAction.TEST -> HugeIcons.CloudServer
            BackupAction.BACKUP -> HugeIcons.CloudUpload
            else -> HugeIcons.Refresh01
        }
        Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
    }
}

private fun List<S3Config.BackupItem>.withItem(item: S3Config.BackupItem, enabled: Boolean): List<S3Config.BackupItem> {
    return when {
        enabled && item !in this -> this + item
        !enabled -> this - item
        else -> this
    }
}

private enum class BackupAction {
    TEST,
    BACKUP,
    REFRESH,
    RESTORE,
    DELETE,
    SAVE_CREDENTIALS,
    CLEAR_CREDENTIALS,
}
