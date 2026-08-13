package me.rerere.rikkahub.data.auth

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.BINGO_MODELS
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.Settings

/**
 * The **only** place API keys are written into provider settings. Key rotation and logout both go
 * through here, so there is no second writer to fall out of sync with.
 *
 * Also the enforcement point for locked-down provider config: [inject] rebuilds baseUrl, paths and
 * the model list from code on every call, so a restored backup or a leaked editor route cannot
 * leave edited provider config in effect past the next launch.
 */
object ProviderInjector {

    /** Rebuilds the bingo provider from code with [keys] applied, dropping every other provider. */
    fun inject(settings: Settings, keys: ProviderKeys): Settings {
        val provider = BINGO_PROVIDER.copy(
            apiKey = keys.gptKey,
            models = BINGO_MODELS.map { it.withOverwriteKey(keys) },
        )
        return settings.copy(providers = listOf(provider))
    }

    /**
     * Clears secrets while keeping the provider shape intact, so a logged-out app has no spendable
     * key on disk but the model picker still renders rather than crashing on a missing provider.
     */
    fun clear(settings: Settings): Settings = inject(settings, ProviderKeys())

    /**
     * Keys are duplicated into each model's `providerOverwrite`, which is the cost of keeping one
     * flat provider-free picker. Contained here so rotation stays a single call.
     */
    private fun Model.withOverwriteKey(keys: ProviderKeys): Model = when (val o = providerOverwrite) {
        is ProviderSetting.Claude -> copy(providerOverwrite = o.copy(apiKey = keys.claudeKey))
        is ProviderSetting.OpenAI -> copy(providerOverwrite = o.copy(apiKey = keys.imageKey))
        else -> this
    }

    /** True when [settings] already matches what [inject] would produce, so a write can be skipped. */
    fun isUpToDate(settings: Settings, keys: ProviderKeys): Boolean {
        val provider = settings.providers.singleOrNull() as? ProviderSetting.OpenAI ?: return false
        if (provider.id != BINGO_PROVIDER_ID) return false
        if (provider.apiKey != keys.gptKey) return false
        if (provider.baseUrl != BINGO_PROVIDER.baseUrl) return false
        if (provider.models.map { it.modelId } != BINGO_MODELS.map { it.modelId }) return false
        return provider.models.all { model ->
            val expected = BINGO_MODELS.firstOrNull { it.id == model.id }?.providerOverwrite
            when (val o = model.providerOverwrite) {
                is ProviderSetting.Claude -> o.apiKey == keys.claudeKey
                is ProviderSetting.OpenAI ->
                    o.apiKey == keys.imageKey && o.useAsyncImageTasks ==
                        (expected as? ProviderSetting.OpenAI)?.useAsyncImageTasks
                else -> true
            }
        }
    }
}
