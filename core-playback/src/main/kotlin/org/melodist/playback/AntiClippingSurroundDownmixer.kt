package org.melodist.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * 环绕声/多声道 (5.1 / 7.1) 下混抗削顶音频处理器
 * 基于 ITU-R BS.775 矩阵将多通道 PCM 线性下混至双通道立体声，
 * 通过增益标定与双曲正切限幅处理输出幅度。
 */
class AntiClippingSurroundDownmixer : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (inputAudioFormat.channelCount > 2) {
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                2,
                C.ENCODING_PCM_16BIT,
            )
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val inputChannelCount = inputAudioFormat.channelCount
        val downmixed = downmixPcm16ToStereo(inputBuffer, inputChannelCount)
        val outputBuffer = replaceOutputBuffer(downmixed.size)
        outputBuffer.put(downmixed)
        outputBuffer.flip()
    }

    companion object {
        private const val CENTER_GAIN = 0.7071f
        private const val SURROUND_GAIN = 0.7071f

        fun downmixPcm16ToStereo(
            inputBuffer: ByteBuffer,
            inputChannels: Int,
        ): ByteArray {
            val remaining = inputBuffer.remaining()
            val bytesPerFrame = inputChannels * 2
            val frameCount = remaining / bytesPerFrame
            val outputBytes = ByteArray(frameCount * 4)
            val outputBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)

            val invScale = if (inputChannels >= 8) 0.35f else 0.45f

            for (f in 0 until frameCount) {
                val fl = inputBuffer.short.toFloat()
                val fr = inputBuffer.short.toFloat()
                val c = if (inputChannels >= 3) inputBuffer.short.toFloat() else 0f
                if (inputChannels >= 4) {
                    inputBuffer.short
                }
                val bl = if (inputChannels >= 5) inputBuffer.short.toFloat() else 0f
                val br = if (inputChannels >= 6) inputBuffer.short.toFloat() else 0f
                val sl = if (inputChannels >= 7) inputBuffer.short.toFloat() else 0f
                val sr = if (inputChannels >= 8) inputBuffer.short.toFloat() else 0f

                for (ch in 8 until inputChannels) {
                    inputBuffer.short
                }

                val sumL = (fl + CENTER_GAIN * c + SURROUND_GAIN * (sl + bl)) * invScale
                val sumR = (fr + CENTER_GAIN * c + SURROUND_GAIN * (sr + br)) * invScale

                outputBuffer.putShort(softClipToShort(sumL))
                outputBuffer.putShort(softClipToShort(sumR))
            }

            return outputBytes
        }

        fun duplicateMonoPcm16(inputBuffer: ByteBuffer): ByteArray {
            val remaining = inputBuffer.remaining()
            val frameCount = remaining / 2
            val outputBytes = ByteArray(frameCount * 4)
            val outputBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frameCount) {
                val sample = inputBuffer.short
                outputBuffer.putShort(sample)
                outputBuffer.putShort(sample)
            }
            return outputBytes
        }

        fun downmixPcm24ToStereo(
            inputBuffer: ByteBuffer,
            inputChannels: Int,
        ): ByteArray {
            val remaining = inputBuffer.remaining()
            val bytesPerFrame = inputChannels * 3
            val frameCount = remaining / bytesPerFrame
            val outputBytes = ByteArray(frameCount * 6)
            val invScale = if (inputChannels >= 8) 0.35f else 0.45f

            var outIdx = 0
            for (f in 0 until frameCount) {
                val fl = readInt24(inputBuffer).toFloat()
                val fr = readInt24(inputBuffer).toFloat()
                val c = if (inputChannels >= 3) readInt24(inputBuffer).toFloat() else 0f
                if (inputChannels >= 4) {
                    skipBytes(inputBuffer, 3)
                }
                val bl = if (inputChannels >= 5) readInt24(inputBuffer).toFloat() else 0f
                val br = if (inputChannels >= 6) readInt24(inputBuffer).toFloat() else 0f
                val sl = if (inputChannels >= 7) readInt24(inputBuffer).toFloat() else 0f
                val sr = if (inputChannels >= 8) readInt24(inputBuffer).toFloat() else 0f

                for (ch in 8 until inputChannels) {
                    skipBytes(inputBuffer, 3)
                }

                val sumL = (fl + CENTER_GAIN * c + SURROUND_GAIN * (sl + bl)) * invScale
                val sumR = (fr + CENTER_GAIN * c + SURROUND_GAIN * (sr + br)) * invScale

                val outL = softClipToInt24(sumL)
                val outR = softClipToInt24(sumR)

                writeInt24(outputBytes, outIdx, outL)
                outIdx += 3
                writeInt24(outputBytes, outIdx, outR)
                outIdx += 3
            }

            return outputBytes
        }

        fun duplicateMonoPcm24(inputBuffer: ByteBuffer): ByteArray {
            val remaining = inputBuffer.remaining()
            val frameCount = remaining / 3
            val outputBytes = ByteArray(frameCount * 6)
            var outIdx = 0
            for (f in 0 until frameCount) {
                val b0 = inputBuffer.get()
                val b1 = inputBuffer.get()
                val b2 = inputBuffer.get()
                outputBytes[outIdx++] = b0
                outputBytes[outIdx++] = b1
                outputBytes[outIdx++] = b2
                outputBytes[outIdx++] = b0
                outputBytes[outIdx++] = b1
                outputBytes[outIdx++] = b2
            }
            return outputBytes
        }

        fun downmixPcm32ToStereo(
            inputBuffer: ByteBuffer,
            inputChannels: Int,
        ): ByteArray {
            val remaining = inputBuffer.remaining()
            val bytesPerFrame = inputChannels * 4
            val frameCount = remaining / bytesPerFrame
            val outputBytes = ByteArray(frameCount * 8)
            val outputBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
            val invScale = if (inputChannels >= 8) 0.35f else 0.45f

            for (f in 0 until frameCount) {
                val fl = inputBuffer.int.toFloat()
                val fr = inputBuffer.int.toFloat()
                val c = if (inputChannels >= 3) inputBuffer.int.toFloat() else 0f
                if (inputChannels >= 4) {
                    inputBuffer.int
                }
                val bl = if (inputChannels >= 5) inputBuffer.int.toFloat() else 0f
                val br = if (inputChannels >= 6) inputBuffer.int.toFloat() else 0f
                val sl = if (inputChannels >= 7) inputBuffer.int.toFloat() else 0f
                val sr = if (inputChannels >= 8) inputBuffer.int.toFloat() else 0f

                for (ch in 8 until inputChannels) {
                    inputBuffer.int
                }

                val sumL = (fl + CENTER_GAIN * c + SURROUND_GAIN * (sl + bl)) * invScale
                val sumR = (fr + CENTER_GAIN * c + SURROUND_GAIN * (sr + br)) * invScale

                outputBuffer.putInt(softClipToInt32(sumL))
                outputBuffer.putInt(softClipToInt32(sumR))
            }

            return outputBytes
        }

        fun duplicateMonoPcm32(inputBuffer: ByteBuffer): ByteArray {
            val remaining = inputBuffer.remaining()
            val frameCount = remaining / 4
            val outputBytes = ByteArray(frameCount * 8)
            val outputBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (f in 0 until frameCount) {
                val sample = inputBuffer.int
                outputBuffer.putInt(sample)
                outputBuffer.putInt(sample)
            }
            return outputBytes
        }

        fun downmixFloatToStereo(
            inputBuffer: ByteBuffer,
            inputChannels: Int,
        ): FloatArray {
            val remaining = inputBuffer.remaining()
            val bytesPerFrame = inputChannels * 4
            val frameCount = remaining / bytesPerFrame
            val output = FloatArray(frameCount * 2)
            val invScale = if (inputChannels >= 8) 0.35f else 0.45f

            var outIdx = 0
            for (f in 0 until frameCount) {
                val fl = inputBuffer.float
                val fr = inputBuffer.float
                val c = if (inputChannels >= 3) inputBuffer.float else 0f
                if (inputChannels >= 4) {
                    inputBuffer.float
                }
                val bl = if (inputChannels >= 5) inputBuffer.float else 0f
                val br = if (inputChannels >= 6) inputBuffer.float else 0f
                val sl = if (inputChannels >= 7) inputBuffer.float else 0f
                val sr = if (inputChannels >= 8) inputBuffer.float else 0f

                for (ch in 8 until inputChannels) {
                    inputBuffer.float
                }

                val sumL = (fl + CENTER_GAIN * c + SURROUND_GAIN * (sl + bl)) * invScale
                val sumR = (fr + CENTER_GAIN * c + SURROUND_GAIN * (sr + br)) * invScale

                output[outIdx++] = softClipFloat(sumL)
                output[outIdx++] = softClipFloat(sumR)
            }

            return output
        }

        fun duplicateMonoFloat(inputBuffer: ByteBuffer): FloatArray {
            val remaining = inputBuffer.remaining()
            val frameCount = remaining / 4
            val output = FloatArray(frameCount * 2)
            var outIdx = 0
            for (f in 0 until frameCount) {
                val sample = inputBuffer.float
                output[outIdx++] = sample
                output[outIdx++] = sample
            }
            return output
        }

        private fun readInt24(buffer: ByteBuffer): Int {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            val b2 = buffer.get().toInt()
            return (b2 shl 16) or (b1 shl 8) or b0
        }

        private fun writeInt24(
            dest: ByteArray,
            offset: Int,
            value: Int,
        ) {
            dest[offset] = (value and 0xFF).toByte()
            dest[offset + 1] = ((value shr 8) and 0xFF).toByte()
            dest[offset + 2] = ((value shr 16) and 0xFF).toByte()
        }

        private fun skipBytes(
            buffer: ByteBuffer,
            count: Int,
        ) {
            buffer.position(buffer.position() + count)
        }

        private fun softClipNormalized(normalized: Double): Double {
            val absVal = abs(normalized)
            if (absVal <= 0.85) {
                return normalized
            }
            val sign = if (normalized < 0.0) -1.0 else 1.0
            val surplus = (absVal - 0.85) / 0.15
            return sign * (0.85 + 0.15 * tanh(surplus))
        }

        private fun softClipToShort(sample: Float): Short {
            val clipped = softClipNormalized(sample / 32767.0) * 32767.0
            return clipped.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        private fun softClipToInt24(sample: Float): Int {
            val clipped = softClipNormalized(sample / 8388607.0) * 8388607.0
            return clipped.toInt().coerceIn(-8388608, 8388607)
        }

        private fun softClipToInt32(sample: Float): Int {
            val clipped = softClipNormalized(sample / 2147483647.0) * 2147483647.0
            return clipped.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }

        private fun softClipFloat(sample: Float): Float = softClipNormalized(sample.toDouble()).toFloat()
    }
}
