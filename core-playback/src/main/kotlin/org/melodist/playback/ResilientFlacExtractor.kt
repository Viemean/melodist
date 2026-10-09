package org.melodist.playback

import android.util.Log
import androidx.media3.common.ParserException
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.flac.FlacExtractor
import java.io.IOException

/**
 * 具备自动同步恢复与尾部坏帧平滑终止能力的 FLAC 解封装包装器。
 *
 * @param delegate 基础 Media3 FlacExtractor 实例
 */
class ResilientFlacExtractor(
    private val delegate: Extractor = FlacExtractor(),
) : Extractor {
    private var pendingSeekResync = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(output)
    }

    override fun read(
        input: ExtractorInput,
        seekPosition: PositionHolder,
    ): Int {
        if (pendingSeekResync) {
            alignToNextSyncCodeIfUnaligned(input)
            pendingSeekResync = false
        }

        try {
            return delegate.read(input, seekPosition)
        } catch (e: Exception) {
            if (e is ParserException) {
                val len = input.length
                val pos = input.position

                if (len > 0L && (pos >= len - 65536L || pos.toFloat() / len >= 0.95f)) {
                    Log.i(TAG, "Tolerating trailing malformed frame at position $pos of $len; ending stream")
                    return Extractor.RESULT_END_OF_INPUT
                }

                try {
                    input.skipFully(1)
                    val resyncOffset = findNextSyncCode(input, maxScanBytes = 65536)
                    if (resyncOffset >= 0) {
                        val totalSkipped = 1 + resyncOffset
                        Log.w(TAG, "Skipping $totalSkipped corrupted bytes to re-align with next FLAC sync frame")
                        if (resyncOffset > 0) {
                            input.skipFully(resyncOffset)
                        }
                        return Extractor.RESULT_CONTINUE
                    }
                } catch (_: Exception) {
                    Log.i(TAG, "Reached EOF while seeking sync code; ending stream")
                    return Extractor.RESULT_END_OF_INPUT
                }
            }
            throw e
        }
    }

    override fun seek(
        position: Long,
        timeUs: Long,
    ) {
        delegate.seek(position, timeUs)
        pendingSeekResync = timeUs > 0L
    }

    override fun release() {
        delegate.release()
    }

    private fun alignToNextSyncCodeIfUnaligned(input: ExtractorInput) {
        try {
            val offset = findNextSyncCode(input, maxScanBytes = 65536)
            if (offset > 0) {
                Log.d(TAG, "Seek unaligned, advancing $offset bytes to next frame header")
                input.skipFully(offset)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to verify seek frame alignment", e)
        }
    }

    private fun findNextSyncCode(
        input: ExtractorInput,
        maxScanBytes: Int,
    ): Int {
        input.resetPeekPosition()
        var scannedBytes = 0
        var prevByte = -1
        val bufferSize = 4096
        val buffer = ByteArray(bufferSize)

        while (scannedBytes < maxScanBytes) {
            val toRead = minOf(bufferSize, maxScanBytes - scannedBytes)
            val bytesRead = input.peek(buffer, 0, toRead)
            if (bytesRead <= 0) {
                break
            }
            for (i in 0 until bytesRead) {
                val b = buffer[i].toInt() and 0xFF
                if (prevByte == 0xFF && (b and 0xFC) == 0xF8) {
                    input.resetPeekPosition()
                    return scannedBytes + i - 1
                }
                prevByte = b
            }
            scannedBytes += bytesRead
        }
        input.resetPeekPosition()
        return -1
    }

    companion object {
        private const val TAG = "ResilientFlacExtractor"
    }
}
