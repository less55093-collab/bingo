package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.ModelType
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelListStateTest {
    @Test
    fun `single type stays a one item list`() {
        assertEquals(listOf(ModelType.CHAT), modelTypes(ModelType.CHAT, null))
    }

    @Test
    fun `chat picker lists chat models before image models`() {
        assertEquals(
            listOf(ModelType.CHAT, ModelType.IMAGE),
            modelTypes(ModelType.IMAGE, ModelType.CHAT),
        )
    }

    @Test
    fun `section titles match the two chat picker groups`() {
        assertEquals("聊天模型", modelTypeSectionTitle(ModelType.CHAT))
        assertEquals("生图模型", modelTypeSectionTitle(ModelType.IMAGE))
    }
}
