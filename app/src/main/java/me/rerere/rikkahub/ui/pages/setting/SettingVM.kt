package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.s3.S3CredentialStore
import me.rerere.rikkahub.data.sync.s3.S3Credentials
import me.rerere.rikkahub.data.auth.AuthTokenStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val s3Sync: S3Sync,
    private val authTokenStore: AuthTokenStore,
    private val s3CredentialStore: S3CredentialStore,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateS3Config(config: S3Config) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(s3Config = config.copy(accessKeyId = "", secretAccessKey = ""))
            }
        }
    }

    suspend fun testS3(config: S3Config) {
        s3Sync.testS3(config)
    }

    suspend fun backupToS3(config: S3Config) {
        s3Sync.backupToS3(config)
    }

    suspend fun listS3Backups(config: S3Config): List<S3BackupItem> {
        return s3Sync.listBackupFiles(config)
    }

    suspend fun restoreS3Backup(config: S3Config, item: S3BackupItem): Boolean {
        return s3Sync.restoreFromS3(config, item)
    }

    suspend fun deleteS3Backup(config: S3Config, item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(config, item)
    }

    suspend fun loadS3Credentials(): S3Credentials? {
        val accountId = currentAccountId()
        s3CredentialStore.load(accountId)?.let { return it }

        // Older app versions wrote these secrets into the normal settings DataStore. Move them
        // into the encrypted, no-backup store before the UI decides that no credentials exist.
        val legacy = settingsStore.settingsFlow.value.s3Config
            .takeIf { it.accessKeyId.isNotBlank() && it.secretAccessKey.isNotBlank() }
            ?.let { S3Credentials(it.accessKeyId, it.secretAccessKey) }
            ?: return null

        s3CredentialStore.save(accountId, legacy)
        settingsStore.update { settings ->
            settings.copy(s3Config = settings.s3Config.copy(accessKeyId = "", secretAccessKey = ""))
        }
        return legacy
    }

    suspend fun saveS3Credentials(accessKeyId: String, secretAccessKey: String) {
        s3CredentialStore.save(currentAccountId(), S3Credentials(accessKeyId, secretAccessKey))
    }

    suspend fun clearS3Credentials() {
        s3CredentialStore.clear(currentAccountId())
    }

    private suspend fun currentAccountId(): Long {
        return authTokenStore.profileFlow.first()?.id?.takeIf { it > 0 }
            ?: throw IllegalStateException("A signed-in profile is required for S3 backup")
    }
}
