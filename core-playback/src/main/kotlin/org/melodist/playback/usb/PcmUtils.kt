package org.melodist.playback.usb

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal object PcmUtils {
    fun isEncodingSupported(encoding: Int): Boolean =
        encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_FLOAT

    fun bytesPerSample(encoding: Int): Int =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }

    fun readFloat(
        buffer: ByteBuffer,
        encoding: Int,
    ): Float =
        when (encoding) {
            C.ENCODING_PCM_16BIT -> buffer.getShort().toFloat() / 32768f
            C.ENCODING_PCM_FLOAT -> buffer.getFloat()
            C.ENCODING_PCM_24BIT -> readInt24(buffer).toFloat() / 8388608f
            C.ENCODING_PCM_32BIT -> buffer.getInt().toFloat() / 2.1474836E9f
            else -> buffer.getShort().toFloat() / 32768f
        }

    fun writeFloat(
        buffer: ByteBuffer,
        sample: Float,
        encoding: Int,
    ) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                buffer.putShort(
                    (sample * 32767f).coerceIn(-32768f, 32767f).toInt().toShort(),
                )
            }

            C.ENCODING_PCM_FLOAT -> {
                buffer.putFloat(sample)
            }

            C.ENCODING_PCM_24BIT -> {
                writeInt24(
                    buffer,
                    (sample * 8388607f).coerceIn(-8388608f, 8388607f).toInt(),
                )
            }

            C.ENCODING_PCM_32BIT -> {
                buffer.putInt(
                    (sample.toDouble() * 2147483647.0)
                        .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
                        .toInt(),
                )
            }
        }
    }

    private fun readInt24(buffer: ByteBuffer): Int {
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        val b2 = buffer.get().toInt() and 0xFF
        val unsigned = b0 or (b1 shl 8) or (b2 shl 16)
        return if (unsigned and 0x800000 != 0) unsigned or (-0x1000000) else unsigned
    }

    private fun writeInt24(
        buffer: ByteBuffer,
        value: Int,
    ) {
        buffer.put((value and 0xFF).toByte())
        buffer.put((value shr 8 and 0xFF).toByte())
        buffer.put((value shr 16 and 0xFF).toByte())
    }
}
