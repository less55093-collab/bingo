package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test

class DefaultProvidersTest {
    @Test fun `ships one empty gateway catalog with no credentials`() {
        assertEquals(1, DEFAULT_PROVIDERS.size)
        val provider = DEFAULT_PROVIDERS.single() as ProviderSetting.OpenAI
        assertEquals(BINGO_PROVIDER_ID, provider.id)
        assertEquals("https://api.bingoapi.top/v1", provider.baseUrl)
        assertTrue(provider.enabled)
        assertTrue(provider.useResponseApi)
        assertTrue(provider.models.isEmpty())
        assertEquals("", provider.apiKey)
    }

    @Test fun `legacy reference identities survive the catalog migration`() {
        assertEquals(BingoModelIds.GPT_5_6_SOL, BINGO_DEFAULT_MODEL_ID)
        assertEquals(BingoModelIds.GPT_IMAGE_2, BINGO_IMAGE_MODEL_ID)
        assertEquals(BingoModelIds.GPT_5_4_MINI, BINGO_FAST_MODEL_ID)
    }
}
