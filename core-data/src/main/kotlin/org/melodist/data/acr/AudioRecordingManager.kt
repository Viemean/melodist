package org.melodist.data.acr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Android TV 麦克风录音管理器
 * 基于 AudioRecord 实现 16000Hz 单声道 PCM 采集、动态 RMS 计算与音频前处理 (DC去除 / 预加重 / AGC)
 */
class AudioRecordingManager {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    private val pcmBuffer = mutableListOf<Short>()
    private val lock = Any()

    private val _audioEnergy = MutableStateFlow(0f)
    val audioEnergy: StateFlow<Float> = _audioEnergy.asStateFlow()

    val isRunning: Boolean
        get() = isRecording.get()

    /**
     * 开始采集麦克风音频
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(lock) {
            if (isRecording.get()) return true

            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = max(minBufferSize, 4096)

            val audioSources =
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                )

            var initializedRecord: AudioRecord? = null
            for (source in audioSources) {
                try {
                    val record =
                        AudioRecord(
                            source,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize,
                        )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord = record
                        android.util.Log.i(
                            "AudioRecordingManager",
                            "AudioRecord initialized successfully with source=$source, bufferSize=$bufferSize",
                        )
                        break
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AudioRecordingManager", "AudioRecord init attempt failed for source=$source: ${e.message}")
                }
            }

            if (initializedRecord == null) {
                android.util.Log.e("AudioRecordingManager", "AudioRecord initialization failed on all sources!")
                return false
            }

            try {
                audioRecord = initializedRecord
                pcmBuffer.clear()
                audioRecord?.startRecording()
                isRecording.set(true)

                recordingThread =
                    Thread({ recordLoop(bufferSize) }, "MicRecordThread").apply {
                        isDaemon = true
                        start()
                    }

                return true
            } catch (e: Exception) {
                android.util.Log.e("AudioRecordingManager", "AudioRecord start failed: ${e.message}", e)
                initializedRecord.release()
                audioRecord = null
                isRecording.set(false)
                return false
            }
        }
    }

    private fun recordLoop(bufferSize: Int) {
        val shortBuffer = ShortArray(bufferSize / 2)
        while (isRecording.get()) {
            val record = audioRecord ?: break
            val readCount = record.read(shortBuffer, 0, shortBuffer.size)
            if (readCount > 0) {
                var sumSquares = 0L
                synchronized(lock) {
                    for (i in 0 until readCount) {
                        val s = shortBuffer[i]
                        pcmBuffer.add(s)
                        sumSquares += s.toLong() * s.toLong()
                    }
                }

                val rms = sqrt((sumSquares.toDouble() / readCount))
                val normalizedEnergy = (rms / 2500.0).coerceIn(0.0, 1.0).toFloat()
                _audioEnergy.value = normalizedEnergy
            } else if (readCount < 0) {
                if (isRecording.get()) {
                    android.util.Log.e("AudioRecordingManager", "AudioRecord.read error: $readCount")
                }
                break
            }
        }
    }

    /**
     * 检查最近 0.5 秒内是否存在有效声音信号 (RMS > 20)
     */
    fun hasMeaningfulSignal(): Boolean {
        synchronized(lock) {
            val total = pcmBuffer.size
            val windowSamples = 8000 // 0.5s @ 16000Hz
            val start = max(0, total - windowSamples)
            val count = total - start
            if (count <= 0) return false

            var sumSquares = 0L
            for (i in start until total) {
                val s = pcmBuffer[i].toLong()
                sumSquares += s * s
            }
            val rms = sqrt(sumSquares.toDouble() / count)
            return rms > 20.0
        }
    }

    /**
     * 获取当前累积并经过高通滤波、FIR 预加重和双曲正切 AGC 增益处理后的 16000Hz PCM 采样切片
     */
    fun getProcessedSnapshotSamples(): ShortArray {
        synchronized(lock) {
            val size = pcmBuffer.size
            if (size == 0) return ShortArray(0)

            val rawSamples = ShortArray(size)
            for (i in 0 until size) {
                rawSamples[i] = pcmBuffer[i]
            }

            if (size <= 1) return rawSamples

            // 1. DC 偏置去除
            var sum = 0L
            for (s in rawSamples) sum += s
            val dcOffset = (sum / size).toInt()

            // 2. 120Hz 高通滤波 (滤除低频机身震动与环境手震杂音)
            // y[n] = alpha * (y[n-1] + x[n] - x[n-1]), alpha = 0.953 @ 16kHz
            val hpAlpha = 0.953f
            val hpFiltered = FloatArray(size)
            var prevX = (rawSamples[0].toInt() - dcOffset).toFloat()
            var prevY = 0f
            hpFiltered[0] = prevX

            for (i in 1 until size) {
                val curX = (rawSamples[i].toInt() - dcOffset).toFloat()
                val curY = hpAlpha * (prevY + curX - prevX)
                hpFiltered[i] = curY
                prevX = curX
                prevY = curY
            }

            // 3. 标准一阶 FIR 预加重：y[n] = x[n] - preAlpha * x[n-1] (preAlpha = 0.93)
            val preAlpha = 0.93f
            val preEmphasized = FloatArray(size)
            preEmphasized[0] = hpFiltered[0]
            for (i in 1 until size) {
                preEmphasized[i] = hpFiltered[i] - preAlpha * hpFiltered[i - 1]
            }

            // AGC 增益与限幅计算
            val agcWindow = 24000
            val agcStart = max(0, size - agcWindow)
            val agcCount = size - agcStart
            var sumSquares = 0.0
            for (i in agcStart until size) {
                val s = preEmphasized[i].toDouble()
                sumSquares += s * s
            }
            val rms = if (agcCount > 0) sqrt(sumSquares / agcCount) else 0.0

            val gain =
                when {
                    rms >= 15.0 && rms < 2800.0 -> (3600.0 / rms).toFloat().coerceIn(1.0f, 14.0f)
                    else -> 1.0f
                }

            val result = ShortArray(size)
            for (i in 0 until size) {
                val amplified = preEmphasized[i] * gain
                // 双曲正切软限幅：tanh(x / 32767.0) * 32767.0，消除硬削顶破音
                val normalized = amplified / 32767.0
                val softClipped =
                    if (kotlin.math.abs(normalized) > 0.85) {
                        kotlin.math.tanh(normalized) * 32767.0
                    } else {
                        amplified.toDouble()
                    }
                result[i] = softClipped.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            return result
        }
    }

    /**
     * 停止录音并释放资源
     */
    fun stop() {
        isRecording.set(false)
        _audioEnergy.value = 0f

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        val thread = recordingThread
        recordingThread = null
        try {
            thread?.join(600)
        } catch (_: Exception) {
        }

        synchronized(lock) {
            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }
            audioRecord = null
        }
    }
}
