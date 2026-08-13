package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.s3.S3CredentialStore
import me.rerere.rikkahub.data.sync.s3.S3Credentials
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Removes OSS access keys from the settings DataStore, which participates in Android backup.
 *
 * The encrypted value is handed to [SettingsStore] after the DataStore migration completes, where
 * it is placed in noBackupFilesDir and claimed by the signed-in account that opens backup settings.
 */
class PreferenceStoreV5Migration(
    private val credentialStore: S3CredentialStore,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < VERSION
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.S3_CONFIG]?.let { raw ->
            val legacyConfig = legacyS3Credentials(raw)
            if (legacyConfig != null) {
                // Failing to secure the credentials aborts this migration, leaving the old data
                // untouched rather than silently discarding the user's only copy.
                credentialStore.saveLegacySettingsCredentials(
                    S3Credentials(legacyConfig.accessKeyId, legacyConfig.secretAccessKey)
                )
            }
            val sanitized = runCatching {
                JsonInstant.decodeFromString<S3Config>(raw)
                    .copy(accessKeyId = "", secretAccessKey = "")
            }.getOrNull()
            if (sanitized == null) {
                prefs.remove(SettingsStore.S3_CONFIG)
            } else {
                prefs[SettingsStore.S3_CONFIG] = JsonInstant.encodeToString(sanitized)
            }
        }
        prefs[SettingsStore.VERSION] = VERSION
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    companion object {
        const val VERSION = 5
    }
}

/** Extracts a valid legacy OSS credential pair before the settings payload is overwritten. */
internal fun legacyS3Credentials(raw: String?): S3Config? = raw
    ?.let { encoded -> runCatching { JsonInstant.decodeFromString<S3Config>(encoded) }.getOrNull() }
    ?.takeIf { it.accessKeyId.isNotBlank() && it.secretAccessKey.isNotBlank() }
