package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_PLAN_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.IMAGE_GENERATION_TOOL_NAME
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.ai.tools.local.ImageGenerationVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolUITest {
    @Test
    fun `direct tool stays in image generation while input is partial`() {
        val phase = resolveImageGenerationToolPhase(
            toolName = IMAGE_GENERATION_TOOL_NAME,
            input = """{"prompt":"a cat""",
            approvalState = ToolApprovalState.Auto,
            hasOutput = false,
            hasImages = false,
        )

        assertEquals(ImageGenerationToolPhase.GENERATING, phase)
    }

    @Test
    fun `direct prompt starts image generation without approval`() {
        val phase = resolveImageGenerationToolPhase(
            toolName = IMAGE_GENERATION_TOOL_NAME,
            input = """{"prompt":"a cat","size":"1024x1024"}""",
            approvalState = ToolApprovalState.Auto,
            hasOutput = false,
            hasImages = false,
        )

        assertEquals(ImageGenerationToolPhase.GENERATING, phase)
    }

    @Test
    fun `partial plan input shows plan preparation`() {
        val phase = resolveImageGenerationToolPhase(
            toolName = IMAGE_GENERATION_PLAN_TOOL_NAME,
            input = """{"request":"a cat","variants":[{"prompt":"A"""",
            approvalState = ToolApprovalState.Auto,
            hasOutput = false,
            hasImages = false,
        )

        assertEquals(ImageGenerationToolPhase.PLAN_PREPARING, phase)
    }

    @Test
    fun `approved variants switch from approval to confirmed generation`() {
        val input = """{"variants":[{"prompt":"A"},{"prompt":"B"}]}"""

        assertEquals(
            ImageGenerationToolPhase.PLAN_APPROVAL,
            resolveImageGenerationToolPhase(
                toolName = IMAGE_GENERATION_PLAN_TOOL_NAME,
                input = input,
                approvalState = ToolApprovalState.Pending,
                hasOutput = false,
                hasImages = false,
            ),
        )
        assertEquals(
            ImageGenerationToolPhase.PLAN_CONFIRMED,
            resolveImageGenerationToolPhase(
                toolName = IMAGE_GENERATION_PLAN_TOOL_NAME,
                input = input,
                approvalState = ToolApprovalState.Approved,
                hasOutput = false,
                hasImages = false,
            ),
        )
    }

    @Test
    fun `approved plan keeps a confirmed state before output`() {
        val input = """{"request":"a cat","variants":[{"prompt":"A"},{"prompt":"B"}]}"""

        assertEquals(
            ImageGenerationToolPhase.PLAN_CONFIRMED,
            resolveImageGenerationToolPhase(
                toolName = IMAGE_GENERATION_PLAN_TOOL_NAME,
                input = input,
                approvalState = ToolApprovalState.Approved,
                hasOutput = false,
                hasImages = false,
            ),
        )
    }

    @Test
    fun `selection defaults to first and supports arbitrary combinations`() {
        var selected = initialImageGenerationSelection(3)

        assertEquals(setOf(0), selected)
        selected = updateImageGenerationSelection(selected, index = 2, selected = true)
        assertEquals(setOf(0, 2), selected)
        selected = updateImageGenerationSelection(selected, index = 0, selected = false)
        selected = updateImageGenerationSelection(selected, index = 2, selected = false)

        assertTrue(selected.isEmpty())
        assertTrue(initialImageGenerationSelection(0).isEmpty())
    }

    @Test
    fun `selected input preserves only chosen variants in original order`() {
        val original = Json.parseToJsonElement(
            """{"prompt":"legacy","variants":[{"prompt":"old"}],"request_id":"keep"}"""
        ).jsonObject
        val selected = listOf(
            ImageGenerationVariant("Second", "prompt 2", "1536x1024"),
            ImageGenerationVariant("Third", "prompt 3", "1024x1536"),
        )

        val result = Json.parseToJsonElement(
            buildSelectedImageGenerationInput(original, selected)
        ).jsonObject
        val variants = result.getValue("variants").jsonArray

        assertEquals("legacy", result.getValue("prompt").jsonPrimitive.content)
        assertEquals("keep", result.getValue("request_id").jsonPrimitive.content)
        assertEquals(2, variants.size)
        assertEquals("Second", variants[0].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("Third", variants[1].jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals("1024x1536", variants[1].jsonObject.getValue("size").jsonPrimitive.content)
    }
}
