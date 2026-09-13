package org.melodist.playback

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.acr.AcousticFeature
import org.melodist.api.acr.AcousticFingerprintExtractor
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 高性能本地/缓存音频切片声学指纹提取器
 * 使用 Android 原生 MediaExtractor + MediaCodec 精准 seek 到歌曲高潮/主歌切片 (15s ~ 23s)，
 * 解码后重采样为 8000Hz 单声道 PCM，送入 AcousticFingerprintExtractor 提取声学特征。
 */
object AudioSliceExtractor {
    private const val TAG = "AudioSliceExtractor"
    private const val TARGET_SAMPLE_RATE = 8000
    private const val SLICE_DURATION_SECONDS = 8L
    private const val DEFAULT_START_SECONDS = 15L
    private const val FALLBACK_START_SECONDS = 5L

    /**
     * 对给定的本地音频文件进行快速局部切片解码并提取声学指纹
     */
    suspend fun extractSliceFeature(audioFile: File): AcousticFeature? =
        withContext(Dispatchers.IO) {
            if (!audioFile.exists() || !audioFile.canRead() || audioFile.length() < 32 * 1024L) {
                return@withContext null
            }

            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null

            try {
                extractor = MediaExtractor()
                extractor.setDataSource(audioFile.absolutePath)

                var audioTrackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        format = f
                        break
                    }
                }

                if (audioTrackIndex < 0 || format == null) {
                    Log.w(TAG, "No audio track found in: ${audioFile.name}")
                    return@withContext null
                }

                extractor.selectTrack(audioTrackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                val srcSampleRate =
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } else {
                        44100
                    }
                val channelCount =
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else {
                        2
                    }
                val durationUs =
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else {
                        0L
                    }

                val durationSec = durationUs / 1_000_000L
                val startSec =
                    when {
                        durationSec > 25L -> DEFAULT_START_SECONDS
                        durationSec > 10L -> FALLBACK_START_SECONDS
                        else -> 0L
                    }

                // Seek 到目标切片起点
                extractor.seekTo(startSec * 1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                // 目标解码出的原始 PCM 采样点数量 (按源采样率计算)
                val maxSamplesToDecode = (SLICE_DURATION_SECONDS * srcSampleRate * channelCount).toInt()
                val rawPcmList = ArrayList<Short>(maxSamplesToDecode)

                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false
                val timeoutUs = 10_000L

                while (!isEos && rawPcmList.size < maxSamplesToDecode) {
                    // 1. 发送输入数据
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEos = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // 2. 获取解码输出
                    var outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    while (outputIndex >= 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEos = true
                        }

                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                                val shortBuf = outputBuffer.asShortBuffer()
                                val shortCount = shortBuf.remaining()
                                val needed = maxSamplesToDecode - rawPcmList.size
                                val toRead = min(shortCount, needed)

                                val temp = ShortArray(toRead)
                                shortBuf.get(temp, 0, toRead)
                                for (s in temp) {
                                    rawPcmList.add(s)
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                        if (rawPcmList.size >= maxSamplesToDecode || isEos) break
                        outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
                    }
                }

                if (rawPcmList.isEmpty()) {
                    Log.w(TAG, "Decoded 0 samples for: ${audioFile.name}")
                    return@withContext null
                }

                // 3. 转单声道
                val monoSamples = convertToMono(rawPcmList, channelCount)

                // 4. 重采样到 8000Hz
                val resampled8k = resampleTo8k(monoSamples, srcSampleRate)

                // 5. 提取指纹
                val feature = AcousticFingerprintExtractor.extract(resampled8k)
                if (feature != null) {
                    Log.i(
                        TAG,
                        "Successfully extracted slice feature for ${audioFile.name}, duration=${feature.duration}s, data=${feature.data.size} bytes",
                    )
                } else {
                    Log.w(TAG, "Feature extraction returned null for ${audioFile.name}, sampleCount=${resampled8k.size}")
                }
                feature
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode and extract slice feature for ${audioFile.name}", e)
                null
            } finally {
                try {
                    codec?.stop()
                    codec?.release()
                } catch (_: Exception) {
                }
                try {
                    extractor?.release()
                } catch (_: Exception) {
                }
            }
        }

    /**
     * 将多声道 16-bit PCM 混音为单声道
     */
    private fun convertToMono(
        samples: List<Short>,
        channels: Int,
    ): ShortArray {
        if (channels <= 1) {
            return ShortArray(samples.size) { samples[it] }
        }
        val frames = samples.size / channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            val base = i * channels
            for (ch in 0 until channels) {
                sum += samples[base + ch]
            }
            mono[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mono
    }

    /**
     * 线性插值高精重采样至 8000Hz
     */
    private fun resampleTo8k(
        monoSamples: ShortArray,
        srcSampleRate: Int,
    ): ShortArray {
        if (srcSampleRate == TARGET_SAMPLE_RATE) return monoSamples
        val ratio = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        val targetLen = (monoSamples.size / ratio).toInt()
        if (targetLen <= 0) return ShortArray(0)

        val output = ShortArray(targetLen)
        for (i in 0 until targetLen) {
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            if (idx + 1 < monoSamples.size) {
                val s1 = monoSamples[idx].toDouble()
                val s2 = monoSamples[idx + 1].toDouble()
                output[i] = (s1 + frac * (s2 - s1)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else if (idx < monoSamples.size) {
                output[i] = monoSamples[idx]
            }
        }
        return output
    }
}
