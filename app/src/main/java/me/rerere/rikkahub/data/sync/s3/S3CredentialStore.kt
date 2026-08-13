package me.rerere.rikkahub.data.sync.s3

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps S3 secrets outside the settings DataStore, which participates in Android's cloud backup.
 *
 * The encrypted records live under [Context.noBackupFilesDir], so neither cloud backup nor a
 * device-to-device transfer can copy them. Android Keystore also makes a copied ciphertext
 * unusable outside this application installation.
 */
class S3CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val credentialDirectory = File(appContext.noBackupFilesDir, CREDENTIALS_DIRECTORY)
    private val legacyPreferences = appContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun load(userId: Long): S3Credentials? = synchronized(lock) {
        requireValidUserId(userId)
        read(credentialFile(userId))?.let { return@synchronized it }

        read(legacySettingsFile())?.also { credentials ->
            write(credentialFile(userId), credentials)
            AtomicFile(legacySettingsFile()).delete()
            return@synchronized credentials
        }

        // Builds that briefly stored this encrypted value in SharedPreferences can be upgraded
        // without asking the user to enter the same OSS credentials again.
        readLegacy(userId)?.also { credentials ->
            write(credentialFile(userId), credentials)
            legacyPreferences.edit { remove(legacyKeyFor(userId)) }
        }
    }

    fun save(userId: Long, credentials: S3Credentials) = synchronized(lock) {
        requireValidUserId(userId)
        require(credentials.accessKeyId.isNotBlank()) { "Access key ID must not be blank" }
        require(credentials.secretAccessKey.isNotBlank()) { "Secret access key must not be blank" }
        write(credentialFile(userId), credentials)
        legacyPreferences.edit { remove(legacyKeyFor(userId)) }
    }

    fun clear(userId: Long) = synchronized(lock) {
        requireValidUserId(userId)
        AtomicFile(credentialFile(userId)).delete()
        AtomicFile(legacySettingsFile()).delete()
        legacyPreferences.edit { remove(legacyKeyFor(userId)) }
    }

    /**
     * Persists an unscoped credential found in a pre-account-version settings file. The next
     * signed-in account to open backup settings claims it via [load].
     */
    fun saveLegacySettingsCredentials(credentials: S3Credentials) = synchronized(lock) {
        require(credentials.accessKeyId.isNotBlank()) { "Access key ID must not be blank" }
        require(credentials.secretAccessKey.isNotBlank()) { "Secret access key must not be blank" }
        if (read(legacySettingsFile()) == null) {
            write(legacySettingsFile(), credentials)
        }
    }

    /** Drops an unclaimed pre-account credential at an account boundary. */
    fun clearLegacySettingsCredentials() = synchronized(lock) {
        AtomicFile(legacySettingsFile()).delete()
    }

    private fun read(file: File): S3Credentials? {
        if (!file.exists()) return null
        return runCatching {
            AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                decrypt(reader.readText())
            }
        }.getOrNull()?.takeIf { it.accessKeyId.isNotBlank() && it.secretAccessKey.isNotBlank() }
    }

    private fun readLegacy(userId: Long): S3Credentials? {
        val encrypted = legacyPreferences.getString(legacyKeyFor(userId), null) ?: return null
        return runCatching { decrypt(encrypted) }
            .getOrNull()
            ?.takeIf { it.accessKeyId.isNotBlank() && it.secretAccessKey.isNotBlank() }
    }

    private fun write(file: File, credentials: S3Credentials) {
        check(credentialDirectory.exists() || credentialDirectory.mkdirs()) {
            "Unable to create S3 credential directory"
        }
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(encrypt(credentials).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun encrypt(credentials: S3Credentials): String {
        val iv = ByteArray(GCM_IV_LENGTH).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val plaintext = credentials.toPayload().toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    private fun decrypt(encrypted: String): S3Credentials {
        val combined = Base64.getDecoder().decode(encrypted)
        require(combined.size > GCM_IV_LENGTH) { "Invalid S3 credential payload" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        return S3Credentials.fromPayload(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun credentialFile(userId: Long): File = File(credentialDirectory, "account_$userId")

    private fun legacySettingsFile(): File = File(credentialDirectory, LEGACY_SETTINGS_FILE)

    private fun legacyKeyFor(userId: Long): String = "$CREDENTIALS_KEY/$userId"

    private fun requireValidUserId(userId: Long) {
        require(userId > 0) { "A signed-in account is required for S3 credentials" }
    }

    private companion object {
        const val CREDENTIALS_DIRECTORY = "s3_credentials"
        const val LEGACY_SETTINGS_FILE = "legacy_settings"
        const val LEGACY_PREFERENCES_NAME = "s3_credentials"
        const val CREDENTIALS_KEY = "credentials"
        const val KEY_ALIAS = "rikkahub_s3_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

data class S3Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
) {
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun toPayload(): String {
        val accessKey = Base64.getEncoder().encodeToString(accessKeyId.toByteArray(Charsets.UTF_8))
        val secret = Base64.getEncoder().encodeToString(secretAccessKey.toByteArray(Charsets.UTF_8))
        return "$accessKey:$secret"
    }

    companion object {
        fun fromPayload(payload: String): S3Credentials {
            val delimiter = payload.indexOf(':')
            require(delimiter > 0 && delimiter < payload.lastIndex) { "Invalid S3 credential payload" }
            val accessKey = Base64.getDecoder().decode(payload.substring(0, delimiter)).toString(Charsets.UTF_8)
            val secret = Base64.getDecoder().decode(payload.substring(delimiter + 1)).toString(Charsets.UTF_8)
            require(secret.isNotBlank()) { "Invalid S3 credential payload" }
            return S3Credentials(accessKey, secret)
        }
    }
}
