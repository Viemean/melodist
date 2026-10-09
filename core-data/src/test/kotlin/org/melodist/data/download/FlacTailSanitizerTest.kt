package org.melodist.data.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FlacTailSanitizerTest {

    private fun computeFlacCrc16(data: ByteArray, start: Int, len: Int): Int {
        var crc = 0
        for (i in start until start + len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x8005) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }

    @Test
    fun `truncates trailing garbage bytes after valid frame crc`() {
        val tempFile = File.createTempFile("test_flac_tail", ".flac")
        tempFile.deleteOnExit()

        val validSize = 5000
        val data = ByteArray(validSize)
        // 1. "fLaC" 标志
        data[0] = 'f'.code.toByte()
        data[1] = 'L'.code.toByte()
        data[2] = 'a'.code.toByte()
        data[3] = 'C'.code.toByte()

        // 2. 在偏移 4800 处布置最后一帧 (长度 200 字节)
        val frameStart = 4800
        val frameDataLen = 198 // 198 字节载荷 + 2 字节 CRC-16
        data[frameStart] = 0xFF.toByte()
        data[frameStart + 1] = 0xF8.toByte()
        for (i in frameStart + 2 until frameStart + frameDataLen) {
            data[i] = (i and 0x7F).toByte()
        }

        val crc = computeFlacCrc16(data, frameStart, frameDataLen)
        data[frameStart + frameDataLen] = ((crc ushr 8) and 0xFF).toByte()
        data[frameStart + frameDataLen + 1] = (crc and 0xFF).toByte()

        val expectedValidLength = frameStart + frameDataLen + 2

        // 3. 追加 15 字节非音频垃圾填充数据
        val trailingGarbage = byteArrayOf(
            0xF0.toByte(), 0x00, 0xFF.toByte(), 0x0F,
            0x44, 0x44, 0x40, 0x48, 0x46, 0x3C, 0x36, 0x0E, 0x55, 0xFF.toByte(), 0xF0.toByte()
        )
        val fullData = data.copyOf(expectedValidLength) + trailingGarbage
        tempFile.writeBytes(fullData)

        assertEquals(expectedValidLength + 15, tempFile.length().toInt())

        val sanitized = FlacTailSanitizer.sanitize(tempFile)
        assertTrue(sanitized)
        assertEquals(expectedValidLength.toLong(), tempFile.length())
    }

    @Test
    fun `leaves already valid flac untouched`() {
        val tempFile = File.createTempFile("test_flac_valid", ".flac")
        tempFile.deleteOnExit()

        val frameStart = 4800
        val frameDataLen = 198
        val validLength = frameStart + frameDataLen + 2
        val data = ByteArray(validLength)
        data[0] = 'f'.code.toByte()
        data[1] = 'L'.code.toByte()
        data[2] = 'a'.code.toByte()
        data[3] = 'C'.code.toByte()

        data[frameStart] = 0xFF.toByte()
        data[frameStart + 1] = 0xF8.toByte()

        val crc = computeFlacCrc16(data, frameStart, frameDataLen)
        data[frameStart + frameDataLen] = ((crc ushr 8) and 0xFF).toByte()
        data[frameStart + frameDataLen + 1] = (crc and 0xFF).toByte()

        tempFile.writeBytes(data)

        val sanitized = FlacTailSanitizer.sanitize(tempFile)
        assertFalse(sanitized)
        assertEquals(validLength.toLong(), tempFile.length())
    }

    @Test
    fun `ignores non-flac files`() {
        val tempFile = File.createTempFile("test_not_flac", ".mp3")
        tempFile.deleteOnExit()
        tempFile.writeBytes(ByteArray(5000) { 0x55 })

        val sanitized = FlacTailSanitizer.sanitize(tempFile)
        assertFalse(sanitized)
    }
}
