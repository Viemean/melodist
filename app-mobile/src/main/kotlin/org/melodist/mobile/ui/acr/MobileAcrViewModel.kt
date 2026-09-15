package org.melodist.mobile.ui.acr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.acr.AcousticFingerprintExtractor
import org.melodist.api.acr.AcousticRecognizeClient
import org.melodist.data.acr.AudioRecordingManager
import org.melodist.model.Song

sealed interface MobileAcrUiState {
    data object Idle : MobileAcrUiState

    data object PermissionRequired : MobileAcrUiState

    data class Listening(
        val elapsedSeconds: Float,
    ) : MobileAcrUiState

    data class Success(
        val song: Song,
        val offsetSeconds: Double,
        val anchorRealtimeMs: Long = 0L,
        val matchDurationSeconds: Float = 0f,
    ) : MobileAcrUiState

    data class Failed(
        val message: String,
    ) : MobileAcrUiState
}

class MobileAcrViewModel(
    private val recordingManager: AudioRecordingManager = AudioRecordingManager(),
    private val recognizeClient: AcousticRecognizeClient = AcousticRecognizeClient(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<MobileAcrUiState>(MobileAcrUiState.Idle)
    val uiState: StateFlow<MobileAcrUiState> = _uiState.asStateFlow()

    val audioEnergy: StateFlow<Float> = recordingManager.audioEnergy

    private var recognitionJob: Job? = null

    // 录音启动时的单调时间戳 (用于锚定云端返回的 offsetSeconds)
    private var recordStartRealtimeMs: Long = 0L
    private var successOffsetSeconds: Double = 0.0

    /**
     * 返回补偿后的播放偏移秒数。
     * 以录音开始时刻为基准锚点，计算当前环境音乐所处的实时进度。
     */
    fun getAdjustedOffsetSeconds(): Double {
        if (recordStartRealtimeMs <= 0L) return successOffsetSeconds
        val elapsedSec = (android.os.SystemClock.elapsedRealtime() - recordStartRealtimeMs) / 1000.0
        return successOffsetSeconds + elapsedSec
    }

    fun setPermissionRequired() {
        _uiState.value = MobileAcrUiState.PermissionRequired
    }

    fun startRecognition() {
        if (_uiState.value is MobileAcrUiState.Listening) return
        // 新一轮识别开始时清零上一轮的成功数据与锚点时间戳
        recordStartRealtimeMs = 0L
        successOffsetSeconds = 0.0

        val started = recordingManager.start(null)
        if (!started) {
            _uiState.value = MobileAcrUiState.Failed("无法初始化麦克风录音设备")
            return
        }

        // 记录录音启动单调时间戳，精确锚定 PCM 缓冲区的物理时间起点
        recordStartRealtimeMs = android.os.SystemClock.elapsedRealtime()
        _uiState.value = MobileAcrUiState.Listening(0f)

        recognitionJob?.cancel()
        recognitionJob =
            viewModelScope.launch {
                val startTime = System.currentTimeMillis()
                val sessionUniqueId = startTime
                val probeMilestones = listOf(3000L, 4500L, 6500L, 9000L, 11500L)
                var nextProbeIndex = 0
                val maxDurationMs = 12000L

                var isSearching = false

                while (isActive) {
                    delay(100)
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val elapsedSeconds = elapsedMs / 1000f

                    if (!isSearching && _uiState.value !is MobileAcrUiState.Success) {
                        _uiState.value = MobileAcrUiState.Listening(elapsedSeconds)
                    }

                    if (!isSearching && nextProbeIndex < probeMilestones.size && elapsedMs >= probeMilestones[nextProbeIndex]) {
                        val milestone = probeMilestones[nextProbeIndex]
                        nextProbeIndex++
                        isSearching = true

                        val samples =
                            withContext(Dispatchers.Default) {
                                recordingManager.getProcessedSnapshotSamples()
                            }

                        val sampleDurationSec = samples.size / 16000.0
                        android.util.Log.i(
                            "MobileAcrViewModel",
                            "Probe milestone reached: ${milestone}ms (elapsed: ${elapsedMs}ms, pcmSamples: ${samples.size}, duration: %.2fs)".format(
                                sampleDurationSec,
                            ),
                        )

                        if (samples.isNotEmpty()) {
                            val feature =
                                withContext(Dispatchers.Default) {
                                    val pcm8k = AcousticFingerprintExtractor.downsample16kTo8k(samples)
                                    AcousticFingerprintExtractor.extract(pcm8k)
                                }

                            if (feature != null) {
                                val t0 = System.currentTimeMillis()
                                val result =
                                    withContext(Dispatchers.IO) {
                                        recognizeClient.search(feature, sessionUniqueId)
                                    }
                                val searchRtt = System.currentTimeMillis() - t0

                                android.util.Log.i(
                                    "MobileAcrViewModel",
                                    "Search finished in ${searchRtt}ms, success=${result.success}, title=${result.title}, offset=${result.offsetSeconds}",
                                )

                                val matchedSong = result.song
                                if (result.success && matchedSong != null) {
                                    recordingManager.stop()
                                    successOffsetSeconds = result.offsetSeconds
                                    val durationSec = (System.currentTimeMillis() - startTime) / 1000f
                                    _uiState.value =
                                        MobileAcrUiState.Success(
                                            song = matchedSong,
                                            offsetSeconds = result.offsetSeconds,
                                            anchorRealtimeMs = recordStartRealtimeMs,
                                            matchDurationSeconds = durationSec,
                                        )
                                    return@launch
                                }
                            }
                        }

                        val currentElapsed = System.currentTimeMillis() - startTime
                        while (nextProbeIndex < probeMilestones.size && currentElapsed >= probeMilestones[nextProbeIndex]) {
                            nextProbeIndex++
                        }
                        isSearching = false
                    }

                    if (elapsedMs >= maxDurationMs) {
                        recordingManager.stop()
                        _uiState.value = MobileAcrUiState.Failed("未在录音中识别出匹配曲目，请靠近音源重试")
                        return@launch
                    }
                }
            }
    }

    fun reset() {
        recognitionJob?.cancel()
        recognitionJob = null
        recordingManager.stop()
        recordStartRealtimeMs = 0L
        successOffsetSeconds = 0.0
        _uiState.value = MobileAcrUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        reset()
    }
}
