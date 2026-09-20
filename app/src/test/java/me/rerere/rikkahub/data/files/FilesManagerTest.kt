package me.rerere.rikkahub.data.files

import java.io.File
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesManagerTest {
    @Test
    fun `new target uses rename and leaves no partial destination`() {
        val root = Files.createTempDirectory("image-move").toFile()
        try {
            val payload = imageBytes()
            val source = File(root, "download.part").apply { writeBytes(payload) }
            val target = File(root, "images/result.png")

            val result = moveImageFileDurably(source, target)

            assertEquals(target.absoluteFile, result.absoluteFile)
            assertArrayEquals(payload, target.readBytes())
            assertFalse(source.exists())
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `validated target is preserved during an idempotent retry`() {
        val root = Files.createTempDirectory("image-move").toFile()
        try {
            val source = File(root, "download.part").apply { writeBytes(imageBytes(0xffffff)) }
            val target = File(root, "images/result.png").apply {
                parentFile!!.mkdirs()
                writeBytes(imageBytes())
            }

            val result = moveImageFileDurably(source, target)

            assertEquals(target.absoluteFile, result.absoluteFile)
            assertArrayEquals(imageBytes(), target.readBytes())
            assertArrayEquals(imageBytes(0xffffff), source.readBytes())
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zero byte stale target is replaced only after complete sibling copy`() {
        val root = Files.createTempDirectory("image-move").toFile()
        try {
            val payload = imageBytes()
            val source = File(root, "download.part").apply { writeBytes(payload) }
            val target = File(root, "images/result.png").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(0))
            }

            val result = moveImageFileDurably(source, target)

            assertEquals(target.absoluteFile, result.absoluteFile)
            assertArrayEquals(payload, target.readBytes())
            assertFalse(source.exists())
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `complete target wins even when resumable source was already cleaned up`() {
        val root = Files.createTempDirectory("image-move").toFile()
        try {
            val source = File(root, "download.part")
            val target = File(root, "images/result.png").apply {
                parentFile!!.mkdirs()
                writeBytes(imageBytes())
            }

            val result = moveImageFileDurably(source, target)

            assertEquals(target.absoluteFile, result.absoluteFile)
            assertArrayEquals(imageBytes(), target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `partial nonempty target is repaired from complete source`() {
        val root = Files.createTempDirectory("image-partial").toFile()
        try {
            val bytes = imageBytes()
            val target = File(root, "result.png").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
            val source = File(root, "download.part").apply { writeBytes(bytes) }

            moveImageFileDurably(source, target)

            assertArrayEquals(bytes, target.readBytes())
            assertTrue(isStructurallyValidImageFile(target))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid bytes never create a final image and leave no temporary file`() {
        val root = Files.createTempDirectory("image-invalid").toFile()
        try {
            val target = File(root, "result.png")

            val failure = runCatching { writeImageBytesDurably(imageBytes().dropLast(8).toByteArray(), target) }

            assertTrue(failure.isFailure)
            assertFalse(target.exists())
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed validation preserves earlier final bytes`() {
        val root = Files.createTempDirectory("image-validation-failure").toFile()
        try {
            val target = File(root, "result.png").apply { writeText("incomplete prior attempt") }
            val failure = runCatching { writeImageBytesDurably(imageBytes(), target) { false } }

            assertTrue(failure.isFailure)
            assertEquals("incomplete prior attempt", target.readText())
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `truncated jpeg and png plus corrupted png checksum are rejected`() {
        val root = Files.createTempDirectory("image-structure").toFile()
        try {
            for (format in listOf("png", "jpg")) {
                val bytes = imageBytes(format = format)
                val file = File(root, "result.$format")
                file.writeBytes(bytes)
                assertTrue("valid $format", isStructurallyValidImageFile(file))
                file.writeBytes(bytes.dropLast(2).toByteArray())
                assertFalse("truncated $format", isStructurallyValidImageFile(file))
            }
            val corrupted = imageBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            val file = File(root, "bad.png").apply { writeBytes(corrupted) }
            assertFalse(isStructurallyValidImageFile(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `byte install is replayable after source no longer exists`() {
        val root = Files.createTempDirectory("image-byte-install").toFile()
        try {
            val target = File(root, "result.png")
            writeImageBytesDurably(imageBytes(), target)
            writeImageBytesDurably(ByteArray(0), target)
            assertArrayEquals(imageBytes(), target.readBytes())
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun imageBytes(color: Int = 0x446688, format: String = "png"): ByteArray {
        val bitmap = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) bitmap.setRGB(x, y, color)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(bitmap, format, output))
            output.toByteArray()
        }
    }
}
