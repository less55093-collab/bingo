package me.rerere.rikkahub.data.auth

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.datastore.BINGO_IMAGE_MODEL_ID
import me.rerere.rikkahub.data.datastore.BINGO_PROVIDER_ID
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The injector is the only writer of API keys, so these tests pin the routing that makes chat and
 * image gateway groups look like one flat model list. Image generation keeps its own overwrite
 * because it is billed separately.
 */
class ProviderInjectorTest {
    private val keys = ProviderKeys(
        gptKey = "sk-gpt",
        imageKey = "sk-image",
    )

    private fun inject(settings: Settings = Settings()) = ProviderInjector.inject(settings, keys)

    private fun Settings.container() = providers.single() as ProviderSetting.OpenAI

    @Test
    fun `each group's key reaches its own models`() {
        val container = inject().container()

        assertEquals("sk-gpt", container.apiKey)
        assertTrue(container.useResponseApi)

        val image = container.models.single { it.id == BINGO_IMAGE_MODEL_ID }
        val imageProvider = image.providerOverwrite as ProviderSetting.OpenAI
        assertEquals("sk-image", imageProvider.apiKey)
        assertTrue(imageProvider.useAsyncImageTasks)
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

    @Test
    fun `isUpToDate rejects legacy Chat Completions routing`() {
        val injected = inject()
        val legacy = injected.copy(
            providers = listOf(injected.container().copy(useResponseApi = false))
        )

        assertFalse(ProviderInjector.isUpToDate(legacy, keys))
    }

    @Test
    fun `isUpToDate rejects a stale synchronous image overwrite`() {
        val injected = inject()
        val container = injected.container()
        val editedModels = container.models.map { model ->
            val overwrite = model.providerOverwrite
            if (overwrite is ProviderSetting.OpenAI) {
                model.copy(providerOverwrite = overwrite.copy(useAsyncImageTasks = false))
            } else model
        }

        assertFalse(
            ProviderInjector.isUpToDate(
                injected.copy(providers = listOf(container.copy(models = editedModels))),
                keys,
            )
        )
    }

    @Test
    fun `key reinjection preserves user selected built-in tools`() {
        val injected = inject()
        val container = injected.container()
        val gpt = container.models.first { it.modelId.startsWith("gpt-") }
        val withSearch = injected.copy(
            providers = listOf(
                container.copy(
                    models = container.models.map { model ->
                        if (model.id == gpt.id) model.copy(tools = setOf(BuiltInTools.Search)) else model
                    }
                )
            )
        )

        val reinjected = ProviderInjector.inject(withSearch, keys.copy(gptKey = "sk-rotated"))
        val updatedGpt = reinjected.container().models.first { it.id == gpt.id }

        assertEquals(setOf(BuiltInTools.Search), updatedGpt.tools)
    }
}
