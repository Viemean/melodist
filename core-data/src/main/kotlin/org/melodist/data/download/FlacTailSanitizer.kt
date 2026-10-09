package org.melodist.data.download

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * FLAC 文件尾部坏帧与非法填充字节无损净化工具。
 */
object FlacTailSanitizer {
    private const val TAG = "FlacTailSanitizer"
    private const val MAX_SCAN_TAIL_BYTES = 65536
    private const val MIN_FLAC_FILE_SIZE = 4096L

    private val CRC16_TABLE =
        IntArray(256) { i ->
            var crc = i shl 8
            repeat(8) {
                crc =
                    if ((crc and 0x8000) != 0) {
                        ((crc shl 1) xor 0x8005) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
            }
            crc
        }

    /**
     * 校验并净化 FLAC 文件尾部多余的失步字节。
     *
     * @param file 待净化的音频文件
     * @return 若发生有效截断修复返回 true；若文件本来合规或不符合处理条件返回 false
     */
    fun sanitize(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val fileLength = file.length()
        if (fileLength < MIN_FLAC_FILE_SIZE) return false

        try {
            RandomAccessFile(file, "rw").use { raf ->
                // 校验 fLaC 文件头
                raf.seek(0L)
                val magic = ByteArray(4)
                if (raf.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") {
                    return false
                }

                // 读取尾部最多 64KB 缓冲区
                val tailSize = minOf(MAX_SCAN_TAIL_BYTES.toLong(), fileLength - 4L).toInt()
                val tailOffset = fileLength - tailSize
                raf.seek(tailOffset)
                val buffer = ByteArray(tailSize)
                raf.readFully(buffer)

                // 从后向前寻找最后一个 FLAC 帧同步头
                var lastSyncIndexInTail = -1
                for (i in tailSize - 2 downTo 0) {
                    val b0 = buffer[i].toInt() and 0xFF
                    val b1 = buffer[i + 1].toInt() and 0xFF
                    if (b0 == 0xFF && (b1 and 0xFC) == 0xF8) {
                        lastSyncIndexInTail = i
                        break
                    }
                }

                if (lastSyncIndexInTail < 0) {
                    return false
                }

                val lastFrameStartOffset = tailOffset + lastSyncIndexInTail
                val maxFrameBytes = (fileLength - lastFrameStartOffset).toInt()
                if (maxFrameBytes < 6) {
                    return false
                }

                // 从同步头向后计算增量 CRC-16，匹配帧尾 CRC-16 校验码
                var runningCrc = 0
                var validFrameLength = -1

                for (offset in 0 until maxFrameBytes - 2) {
                    val byteVal = buffer[lastSyncIndexInTail + offset].toInt() and 0xFF
                    runningCrc = ((runningCrc shl 8) and 0xFFFF) xor CRC16_TABLE[(runningCrc ushr 8) xor byteVal]

                    val nextByte0 = buffer[lastSyncIndexInTail + offset + 1].toInt() and 0xFF
                    val nextByte1 = buffer[lastSyncIndexInTail + offset + 2].toInt() and 0xFF
                    val storedCrc = (nextByte0 shl 8) or nextByte1

                    if (runningCrc == storedCrc) {
                        validFrameLength = offset + 3
                    }
                }

                if (validFrameLength <= 0) {
                    return false
                }

                val expectedEndOffset = lastFrameStartOffset + validFrameLength
                val extraBytes = fileLength - expectedEndOffset

                if (extraBytes in 1..MAX_SCAN_TAIL_BYTES) {
                    Log.i(TAG, "Truncating $extraBytes trailing corrupted bytes from ${file.name}")
                    raf.setLength(expectedEndOffset)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sanitize FLAC tail for ${file.name}", e)
        }

        return false
    }
}
