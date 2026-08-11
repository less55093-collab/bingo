package me.rerere.rikkahub.data.auth

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.BINGO_IMAGE_MODEL_ID
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The injector is the only writer of API keys, so these tests pin the routing that makes three
 * gateway groups look like one flat model list: chat on the container key, Claude on a per-model
 * overwrite, image generation on its own overwrite because it is billed separately.
 */
class ProviderInjectorTest {
    private val keys = ProviderKeys(
        claudeKey = "sk-claude",
        gptKey = "sk-gpt",
        imageKey = "sk-image",
    )

    private fun inject(settings: Settings = Settings()) = ProviderInjector.inject(settings, keys)

    private fun Settings.container() = providers.single() as ProviderSetting.OpenAI

    @Test
    fun `each group's key reaches its own models`() {
        val container = inject().container()

        assertEquals("sk-gpt", container.apiKey)

        val claude = container.models.first { it.modelId.startsWith("claude-") }
        assertEquals("sk-claude", (claude.providerOverwrite as ProviderSetting.Claude).apiKey)

        val image = container.models.single { it.id == BINGO_IMAGE_MODEL_ID }
        assertEquals("sk-image", (image.providerOverwrite as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `injecting drops providers left over from an older install`() {
        val stale = ProviderSetting.OpenAI(name = "stale", apiKey = "sk-user-entered")

        val result = inject(Settings(providers = listOf(stale)))

        assertEquals(1, result.providers.size)
        assertEquals(BINGO_PROVIDER_ID, result.container().id)
    }

    @Test
    fun `clear leaves no spendable key but keeps the picker renderable`() {
        val cleared = ProviderInjector.clear(inject())
        val container = cleared.container()

        assertEquals("", container.apiKey)
        assertTrue(container.models.isNotEmpty())
        container.models.forEach { model ->
            when (val overwrite = model.providerOverwrite) {
                is ProviderSetting.Claude -> assertEquals("", overwrite.apiKey)
                is ProviderSetting.OpenAI -> assertEquals("", overwrite.apiKey)
                else -> Unit
            }
        }
    }

    @Test
    fun `isUpToDate accepts freshly injected settings`() {
        assertTrue(ProviderInjector.isUpToDate(inject(), keys))
    }

    @Test
    fun `isUpToDate rejects a stale key in any group`() {
        val injected = inject()

        assertFalse(ProviderInjector.isUpToDate(injected, keys.copy(gptKey = "sk-rotated")))
        assertFalse(ProviderInjector.isUpToDate(injected, keys.copy(claudeKey = "sk-rotated")))
        // Regression guard: an image key rotated server-side must trigger a rewrite, otherwise
        // drawing silently 401s while chat keeps working.
        assertFalse(ProviderInjector.isUpToDate(injected, keys.copy(imageKey = "sk-rotated")))
    }

    @Test
    fun `isUpToDate rejects edited provider config`() {
        val edited = inject().let { settings ->
            settings.copy(providers = listOf(settings.container().copy(baseUrl = "https://evil.test/v1")))
        }

        assertFalse(ProviderInjector.isUpToDate(edited, keys))
    }
}
