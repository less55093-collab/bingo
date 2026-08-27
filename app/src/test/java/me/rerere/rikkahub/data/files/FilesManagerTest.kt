package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
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
            val payload = ByteArray(32 * 1024) { index -> (index % 239).toByte() }
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
    fun `non empty target is preserved during an idempotent retry`() {
        val root = Files.createTempDirectory("image-move").toFile()
        try {
            val source = File(root, "download.part").apply { writeText("new result") }
            val target = File(root, "images/result.png").apply {
                parentFile!!.mkdirs()
                writeText("already committed")
            }

            val result = moveImageFileDurably(source, target)

            assertEquals(target.absoluteFile, result.absoluteFile)
            assertEquals("already committed", target.readText())
            assertEquals("new result", source.readText())
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zero byte stale target is replaced only after complete sibling copy`() {
        val root = Files.createTempDirectory("image-move").toFile()
        try {
            val payload = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
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
                writeText("committed")
            }

            val result = moveImageFileDurably(source, target)

            assertEquals(target.absoluteFile, result.absoluteFile)
            assertEquals("committed", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
