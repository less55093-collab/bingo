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
 * Fallback for `chatModelId` / `fastModelId` / `translateModeId` / `compressModelId`. Kept under the
 * original name so those call sites need no change, but it now resolves to a real curated model
 * rather than the old "auto" entry.
 */
val DEFAULT_AUTO_MODEL_ID: Uuid = BINGO_DEFAULT_MODEL_ID
