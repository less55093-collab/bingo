package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationToolTest {
    @Test
    fun `reference paths use images from the latest user message only`() {
        val latest = java.io.File.createTempFile("image-tool", ".png")
        val older = java.io.File.createTempFile("image-tool-old", ".png")
        try {
            val messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("旧图片"),
                        UIMessagePart.Image("file://${older.absolutePath}"),
                    ),
                ),
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("用这张图做商品主图"),
                        UIMessagePart.Image("file://${latest.absolutePath}"),
                    ),
                ),
            )

            assertEquals(listOf(latest.absolutePath), referenceImagePathsFromMessages(messages))
        } finally {
            latest.delete()
            older.delete()
        }
    }

    @Test
    fun `reference paths fall back to the latest generated image`() {
        val generated = java.io.File.createTempFile("image-tool-generated", ".png")
        try {
            val messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "image-call",
                            toolName = IMAGE_GENERATION_TOOL_NAME,
                            input = "{}",
                            output = listOf(UIMessagePart.Image("file://${generated.absolutePath}")),
                        )
                    ),
                ),
                UIMessage.user("把刚才那张图的背景改成红色"),
            )

            assertEquals(listOf(generated.absolutePath), referenceImagePathsFromMessages(messages))
        } finally {
            generated.delete()
        }
    }

    @Test
    fun `single request uses direct tool without approval`() {
        val args = Json.parseToJsonElement("""{"prompt":"a cat","size":"1024x1024"}""")

        assertFalse(directImageGenerationNeedsApproval(args))
        assertTrue(parseImageGenerationVariants(args).isEmpty())
    }

    @Test
    fun `multiple variants require one approval`() {
        val args = Json.parseToJsonElement(
            """{"variants":[{"label":"Studio","prompt":"studio product photo","size":"1024x1024"},{"label":"Outdoor","prompt":"outdoor product photo","size":"1536x1024"}]}"""
        )

        val variants = parseImageGenerationVariants(args)

        assertTrue(planImageGenerationNeedsApproval(args))
        assertEquals(2, variants.size)
        assertEquals("Outdoor", variants[1].label)
        assertEquals("1536x1024", variants[1].size)
    }

    @Test
    fun `malformed variants are gated before execution`() {
        val args = Json.parseToJsonElement("""{"variants":[{"label":"Missing prompt"}]}""")

        assertFalse(planImageGenerationNeedsApproval(args))
    }

    @Test
    fun `ecommerce recipe protects reference product and controls copy`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("用这张参考图做一个淘宝主图，突出轻便和防水"))
        )

        assertTrue(instruction.contains("外形轮廓、比例、颜色、材质"))
        assertTrue(instruction.contains("宁可简化背景，也不要改变商品本体"))
        assertTrue(instruction.contains("不超过 10 个字"))
        assertTrue(instruction.contains("不要编造具体参数"))
        assertTrue(instruction.contains("不能被裁切"))
    }

    @Test
    fun `pdd recipe uses promotional listing visual without inventing platform data`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("用参考图生成拼多多商品首页图，突出聚热和节能"))
        )

        assertTrue(instruction.contains("拼多多营销主图规则"))
        assertTrue(instruction.contains("红色或橙红色横向促销条"))
        assertTrue(instruction.contains("卖点徽章"))
        assertTrue(instruction.contains("不得猜测价格、折扣、销量"))
        assertFalse(instruction.contains("淘宝商品主图规则"))
    }

    @Test
    fun `taobao recipe stays cleaner than pdd promotional visual`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("用参考图生成淘宝商品主图"))
        )

        assertTrue(instruction.contains("淘宝商品主图规则"))
        assertTrue(instruction.contains("避免拼多多式密集徽章"))
        assertFalse(instruction.contains("拼多多营销主图规则"))
    }

    @Test
    fun `pdd recipe survives a follow-up edit request`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(
                UIMessage.user("生成拼多多商品首页图"),
                UIMessage.user("再把底部横条改成红色"),
            )
        )

        assertTrue(instruction.contains("拼多多营销主图规则"))
        assertTrue(instruction.contains("只修改指定部分"))
    }

    @Test
    fun `ecommerce keywords include selling point and marketplace requests`() {
        val sellingPointInstruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("做一张商品卖点宣传图"))
        )
        val marketplaceInstruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("生成拼多多主图"))
        )

        assertTrue(sellingPointInstruction.contains("配方 ecommerce"))
        assertTrue(marketplaceInstruction.contains("配方 ecommerce"))
    }

    @Test
    fun `ecommerce recipe does not affect unrelated product photography`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("拍一张桌上的玻璃花瓶静物照片"))
        )

        assertFalse(instruction.contains("配方 ecommerce"))
    }

    @Test
    fun `poster request receives only the poster recipe`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("帮我做一张咖啡店开业海报"))
        )

        assertTrue(instruction.contains("配方 poster"))
        assertTrue(instruction.contains("预留清晰文字区"))
        assertFalse(instruction.contains("商品外观必须准确完整"))
    }

    @Test
    fun `unmatched request adds no recipe tokens`() {
        val instruction = ImagePromptRecipes.instructionFor(
            listOf(UIMessage.user("帮我解释量子纠缠"))
        )

        assertEquals("", instruction)
    }
}
