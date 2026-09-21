package me.rerere.rikkahub.data.files

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Reject partial downloads and error documents before they can become a committed image. */
internal fun isStructurallyValidImageFile(file: File): Boolean = runCatching {
    if (!file.isFile || file.length() < 12) return@runCatching false
    RandomAccessFile(file, "r").use { input ->
        val signature = ByteArray(12)
        input.readFully(signature)
        input.seek(0)
        when {
            signature.take(8).toByteArray().contentEquals(PNG_SIGNATURE) -> validPng(input)
            signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() -> validJpeg(input)
            String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(signature, 8, 4, Charsets.US_ASCII) == "WEBP" -> validWebP(input)
            else -> false
        }
    }
}.getOrDefault(false)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)

private fun validPng(input: RandomAccessFile): Boolean {
    input.seek(8)
    var headerSeen = false
    var imageDataSeen = false
    val buffer = ByteArray(8192)
    while (input.filePointer + 12 <= input.length()) {
        val length = input.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4).also(input::readFully)
        if (length > input.length() - input.filePointer - 4) return false
        val name = String(type, Charsets.US_ASCII)
        if (!headerSeen && (name != "IHDR" || length != 13L)) return false
        if (name == "IHDR" && headerSeen) return false
        val crc = CRC32().apply { update(type) }
        if (name == "IHDR") {
            val header = ByteArray(13).also(input::readFully)
            crc.update(header)
            val width = readBigEndianInt(header, 0)
            val height = readBigEndianInt(header, 4)
            if (width <= 0 || height <= 0) return false
            headerSeen = true
        } else {
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count)
                crc.update(buffer, 0, count)
                remaining -= count
            }
        }
        if (crc.value != (input.readInt().toLong() and 0xffffffffL)) return false
        if (name == "IDAT" && length > 0) imageDataSeen = true
        if (name == "IEND") return length == 0L && headerSeen && imageDataSeen && input.filePointer == input.length()
    }
    return false
}

private fun validJpeg(input: RandomAccessFile): Boolean {
    if (input.readUnsignedShort() != 0xffd8) return false
    var hasDimensions = false
    var hasScan = false
    while (input.filePointer < input.length()) {
        if (input.readUnsignedByte() != 0xff) return false
        var marker = input.readUnsignedByte()
        while (marker == 0xff) marker = input.readUnsignedByte()
        if (marker == 0xd9) return hasDimensions && hasScan && input.filePointer == input.length()
        if (marker == 0x00 || marker == 0xd8 || marker in 0xd0..0xd7) return false
        if (marker == 0x01) continue
        val length = input.readUnsignedShort()
        if (length < 2 || length - 2 > input.length() - input.filePointer) return false
        val end = input.filePointer + length - 2
        if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
            if (length < 8) return false
            input.readUnsignedByte()
            if (input.readUnsignedShort() == 0 || input.readUnsignedShort() == 0) return false
            hasDimensions = true
        }
        input.seek(end)
        if (marker == 0xda) {
            hasScan = true
            // Entropy-coded data can contain escaped FF bytes and restart markers. Stop at the
            // next actual marker, including later progressive scans, rather than trusting EOF.
            while (input.filePointer < input.length()) {
                if (input.readUnsignedByte() != 0xff) continue
                val markerPosition = input.filePointer - 1
                var next = input.readUnsignedByte()
                while (next == 0xff) next = input.readUnsignedByte()
                if (next == 0x00 || next in 0xd0..0xd7) continue
                input.seek(markerPosition)
                break
            }
        }
    }
    return false
}

private fun validWebP(input: RandomAccessFile): Boolean {
    input.seek(4)
    val riffSize = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
    if (riffSize + 8 != input.length()) return false
    input.seek(12)
    var imageDataSeen = false
    while (input.filePointer + 8 <= input.length()) {
        val type = ByteArray(4).also(input::readFully)
        val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        val paddedSize = size + size % 2
        if (paddedSize > input.length() - input.filePointer) return false
        val name = String(type, Charsets.US_ASCII)
        if (name in setOf("VP8 ", "VP8L", "ANMF") && size > 0) imageDataSeen = true
        input.seek(input.filePointer + paddedSize)
    }
    return imageDataSeen && input.filePointer == input.length()
}

private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
