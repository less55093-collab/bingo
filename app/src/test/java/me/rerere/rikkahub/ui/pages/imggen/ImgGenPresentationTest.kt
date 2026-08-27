package me.rerere.rikkahub.ui.pages.imggen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImgGenPresentationTest {
    @Test
    fun `text generation only requires a prompt`() {
        assertTrue(canSubmitImageGeneration(ImageCreationMode.TEXT, "a city at night", 0))
        assertFalse(canSubmitImageGeneration(ImageCreationMode.TEXT, "  ", 3))
    }

    @Test
    fun `reference generation requires a prompt and at least one image`() {
        assertFalse(canSubmitImageGeneration(ImageCreationMode.REFERENCE, "change the sky", 0))
        assertTrue(canSubmitImageGeneration(ImageCreationMode.REFERENCE, "change the sky", 1))
    }

    @Test
    fun `image aspect ratio follows valid dimensions and safely falls back`() {
        assertEquals(1.5f, imageAspectRatio("1536x1024"), 0.001f)
        assertEquals(2f / 3f, imageAspectRatio("1024x1536"), 0.001f)
        assertEquals(1f, imageAspectRatio("auto"), 0.001f)
        assertEquals(1f, imageAspectRatio("0x1024"), 0.001f)
        assertEquals(1f, imageAspectRatio("invalid"), 0.001f)
    }
}
