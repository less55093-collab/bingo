package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the locked-down provider config. The product ships exactly one provider pointing at the
 * bingoapi gateway: a stray built-in would surface in the model picker with no key behind it, and
 * unstable model uuids would break saved model selection across restarts.
 */
class DefaultProvidersTest {
    private val provider get() = DEFAULT_PROVIDERS.single() as ProviderSetting.OpenAI

    @Test
    fun `ships exactly the bingo gateway provider`() {
        assertEquals(1, DEFAULT_PROVIDERS.size)
        assertEquals(BINGO_PROVIDER_ID, provider.id)
        assertEquals("https://api.bingoapi.top/v1", provider.baseUrl)
        assertTrue(provider.enabled)
        assertTrue(provider.builtIn)
    }

    @Test
    fun `model ids and uuids are unique`() {
        val models = provider.models

        assertTrue(models.isNotEmpty())
        assertEquals(models.map { it.modelId }.distinct().size, models.size)
        assertEquals(models.map { it.id }.distinct().size, models.size)
    }

    @Test
    fun `default model references resolve to a shipped model`() {
        val ids = provider.models.map { it.id }.toSet()

        // These back Settings.chatModelId and friends. An unresolvable default silently disables
        // the feature it drives instead of failing loudly.
        assertTrue(DEFAULT_AUTO_MODEL_ID in ids)
        assertTrue(BINGO_DEFAULT_MODEL_ID in ids)
        assertTrue(BINGO_FAST_MODEL_ID in ids)
    }

    @Test
    fun `claude models carry a claude provider overwrite`() {
        val claudeModels = provider.models.filter { it.modelId.startsWith("claude-") }

        assertTrue(claudeModels.isNotEmpty())
        claudeModels.forEach { model ->
            val overwrite = model.providerOverwrite
            assertNotNull("${model.modelId} needs an overwrite to reach /v1/messages", overwrite)
            assertTrue(
                "${model.modelId} must overwrite with a Claude provider",
                overwrite is ProviderSetting.Claude,
            )
        }
    }

    @Test
    fun `gpt chat models inherit the container provider`() {
        provider.models
            .filter { it.type == ModelType.CHAT && !it.modelId.startsWith("claude-") }
            .forEach { model ->
                assertNull(
                    "${model.modelId} should use the OpenAI-compatible container",
                    model.providerOverwrite,
                )
            }
    }

    @Test
    fun `image generation ships exactly one model on its own key`() {
        val imageModels = provider.models.filter { it.type == ModelType.IMAGE }

        // Image generation is billed on a separate gateway group, so it cannot share the chat
        // container's key — it needs its own overwrite despite being the same openai platform.
        assertEquals(1, imageModels.size)
        val image = imageModels.single()
        assertEquals("gpt-image-2", image.modelId)
        assertEquals(BINGO_IMAGE_MODEL_ID, image.id)
        assertTrue(Modality.IMAGE in image.outputModalities)

        val overwrite = image.providerOverwrite
        assertTrue(
            "generateImage() requires an OpenAI-typed provider",
            overwrite is ProviderSetting.OpenAI,
        )
        assertEquals(BINGO_IMAGE_OVERWRITE_ID, (overwrite as ProviderSetting.OpenAI).id)
    }

    @Test
    fun `no model ships a baked in api key`() {
        assertEquals("", provider.apiKey)
        provider.models.forEach { model ->
            val message = "${model.modelId} must not persist a key; it is injected at runtime"
            when (val overwrite = model.providerOverwrite) {
                is ProviderSetting.Claude -> assertEquals(message, "", overwrite.apiKey)
                is ProviderSetting.OpenAI -> assertEquals(message, "", overwrite.apiKey)
                else -> Unit
            }
        }
    }
}
