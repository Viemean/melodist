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
 * 基于 ITU-R BS.775 矩阵将多通道 PCM 16-bit 线性下混至双通道立体声，
 * 通过增益标定与双曲正切限幅防止整数加权溢出。
 */
class AntiClippingSurroundDownmixer : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 仅在输入通道数 > 2 (例如 6 通道 5.1 或 8 通道 7.1) 时激活下混处理
        return if (inputAudioFormat.channelCount > 2) {
            AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                // channelCount =
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
        val bytesPerFrame = inputChannelCount * 2
        val frameCount = remaining / bytesPerFrame
        val outputBytes = frameCount * 4 // 2 channels * 2 bytes

        val outputBuffer = replaceOutputBuffer(outputBytes)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        // 针对 5.1 (6ch) 或 7.1 (8ch) 的 ITU-R BS.775 标称下混系数
        val invScale = if (inputChannelCount >= 8) 0.35f else 0.45f
        val centerGain = 0.7071f
        val surroundGain = 0.7071f

        for (f in 0 until frameCount) {
            val fl = inputBuffer.short.toFloat()
            val fr = inputBuffer.short.toFloat()
            val c = if (inputChannelCount >= 3) inputBuffer.short.toFloat() else 0f
            val lfe = if (inputChannelCount >= 4) inputBuffer.short.toFloat() else 0f
            val bl = if (inputChannelCount >= 5) inputBuffer.short.toFloat() else 0f
            val br = if (inputChannelCount >= 6) inputBuffer.short.toFloat() else 0f
            val sl = if (inputChannelCount >= 7) inputBuffer.short.toFloat() else 0f
            val sr = if (inputChannelCount >= 8) inputBuffer.short.toFloat() else 0f

            // 跳过多余通道 (如果有)
            for (ch in 8 until inputChannelCount) {
                inputBuffer.short
            }

            // ITU-R BS.775 加权合成
            val sumL = (fl + centerGain * c + surroundGain * (sl + bl)) * invScale
            val sumR = (fr + centerGain * c + surroundGain * (sr + br)) * invScale

            // 软限幅防止溢出
            outputBuffer.putShort(softClipToShort(sumL))
            outputBuffer.putShort(softClipToShort(sumR))
        }

        outputBuffer.flip()
    }

    private fun softClipToShort(sample: Float): Short {
        val normalized = sample / 32767.0
        val clipped =
            if (abs(normalized) > 0.85) {
                tanh(normalized) * 32767.0
            } else {
                sample.toDouble()
            }
        return clipped.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
