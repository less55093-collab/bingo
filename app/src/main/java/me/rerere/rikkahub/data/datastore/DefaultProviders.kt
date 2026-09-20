package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

/**
 * The app ships exactly one provider, pointing at the bingoapi gateway. Users never see or edit
 * provider configuration, so the previous list of 21 third-party providers is gone: each one was a
 * place where a key could be entered, a base URL changed, or an unvetted model selected.
 *
 * Definitions live in [BINGO_PROVIDER]; this alias is what the settings merge logic consumes.
 */
val DEFAULT_PROVIDERS: List<ProviderSetting> = listOf(BINGO_PROVIDER)

/**
 * Legacy fallback identity. Catalog synchronization replaces unavailable references with an
 * upstream model, or NIL when the selected group has no models.
 */
val DEFAULT_AUTO_MODEL_ID: Uuid = BINGO_DEFAULT_MODEL_ID
