package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "PreferenceStoreV4Migration"

/**
 * Drops provider state left over from installs that predate the locked-down build.
 *
 * The settings merge only ever *adds* missing built-in providers; it has no concept of removing one.
 * So without this, an upgrading user keeps all 21 previous providers — along with whatever keys and
 * base URLs they had entered — showing up in the model picker next to the curated list.
 *
 * Also resets theme state, since the dynamic-color toggle that could set it is gone from the UI.
 *
 * Model id and theme preferences are *removed* rather than rewritten, so the settings flow's own
 * defaults apply and there is a single source of truth for what a fresh value should be.
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        when (val result = prefs[SettingsStore.PROVIDERS]?.let { pruneProviderJson(it) }) {
            is PruneResult.Pruned -> {
                prefs[SettingsStore.PROVIDERS] = result.json
                Log.i(TAG, "dropped ${result.dropped} stale provider(s)")
            }

            PruneResult.Unparseable -> {
                Log.w(TAG, "provider JSON unparseable, resetting to defaults")
                prefs.remove(SettingsStore.PROVIDERS)
            }

            PruneResult.Unchanged, null -> Unit
        }

        // Any of these may reference a model that no longer exists.
        listOf(
            SettingsStore.SELECT_MODEL,
            SettingsStore.FAST_MODEL,
            SettingsStore.TITLE_MODEL,
            SettingsStore.TRANSLATE_MODEL,
            SettingsStore.SUGGESTION_MODEL,
            SettingsStore.OCR_MODEL,
            SettingsStore.COMPRESS_MODEL,
            SettingsStore.IMAGE_GENERATION_MODEL,
            SettingsStore.FAVORITE_MODELS,
        ).forEach { prefs.remove(it) }

        prefs[SettingsStore.ASSISTANTS]?.let { raw ->
            val cleaned = stripAssistantModelPins(raw)
            if (cleaned == null) {
                Log.w(TAG, "assistant JSON unparseable, leaving as-is")
            } else {
                prefs[SettingsStore.ASSISTANTS] = cleaned
            }
        }

        // The dynamic-color toggle no longer has a UI, so a persisted `true` would be permanently
        // stuck and would override the brand palette with the user's wallpaper colors.
        listOf(SettingsStore.DYNAMIC_COLOR, SettingsStore.THEME_ID).forEach { prefs.remove(it) }

        prefs[SettingsStore.VERSION] = 4

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal sealed interface PruneResult {
    data class Pruned(val json: String, val dropped: Int) : PruneResult
    data object Unchanged : PruneResult
    data object Unparseable : PruneResult
}

/**
 * Keeps only the bingo container in a persisted provider array.
 *
 * Works on raw JSON instead of deserializing to `ProviderSetting`: an old entry may use a shape the
 * current model no longer accepts, and a decode failure inside a migration would leave the user
 * stuck on every launch. Unparseable state is reported so the caller can discard it, since the
 * settings flow re-seeds defaults from an absent key.
 */
internal fun pruneProviderJson(raw: String): PruneResult = runCatching {
    val array = JsonInstant.parseToJsonElement(raw) as? JsonArray
        ?: return@runCatching PruneResult.Unparseable
    val keep = array.filter { element ->
        val id = (element as? JsonObject)?.get("id")?.toString()?.trim('"')
        id == BINGO_PROVIDER_ID.toString()
    }
    if (keep.size == array.size) {
        PruneResult.Unchanged
    } else {
        PruneResult.Pruned(
            json = JsonInstant.encodeToString(JsonArray(keep)),
            dropped = array.size - keep.size,
        )
    }
}.getOrElse { PruneResult.Unparseable }

/**
 * Strips the per-assistant `chatModelId` pin, returning null if the payload cannot be parsed.
 *
 * A stale pin references a deleted model and that assistant silently fails to send. The field is
 * nullable with a default, so omitting it makes `getCurrentChatModel()` fall back to the global
 * default.
 */
internal fun stripAssistantModelPins(raw: String): String? = runCatching {
    val array = JsonInstant.parseToJsonElement(raw) as? JsonArray
        ?: return@runCatching null
    val cleaned = array.map { element ->
        val obj = element as? JsonObject ?: return@map element
        JsonObject(obj - "chatModelId")
    }
    JsonInstant.encodeToString(JsonArray(cleaned))
}.getOrNull()
