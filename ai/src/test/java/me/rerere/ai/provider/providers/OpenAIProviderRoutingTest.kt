package me.rerere.ai.provider.providers

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIProviderRoutingTest {
    @Test
    fun `built-in tools force the Responses API`() {
        val params = TextGenerationParams(
            model = Model(tools = setOf(BuiltInTools.Search))
        )

        assertTrue(shouldUseResponsesApi(ProviderSetting.OpenAI(useResponseApi = false), params))
    }

    @Test
    fun `plain chat keeps the configured Chat Completions route`() {
        val params = TextGenerationParams(model = Model())

        assertFalse(shouldUseResponsesApi(ProviderSetting.OpenAI(useResponseApi = false), params))
        assertTrue(shouldUseResponsesApi(ProviderSetting.OpenAI(useResponseApi = true), params))
    }
}
