package org.melodist.tv.acr

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

sealed interface AcrUiState {
    data object Idle : AcrUiState

    data object PermissionRequired : AcrUiState

    data class Listening(
        val recordedSeconds: Float,
    ) : AcrUiState

    data object Recognizing : AcrUiState

    data class Success(
        val song: Song,
        val offsetSeconds: Double,
    ) : AcrUiState

    data class Failed(
        val message: String,
    ) : AcrUiState
}

class AcrViewModel(
    private val recordingManager: AudioRecordingManager = AudioRecordingManager(),
    private val recognizeClient: AcousticRecognizeClient = AcousticRecognizeClient(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<AcrUiState>(AcrUiState.Idle)
    val uiState: StateFlow<AcrUiState> = _uiState.asStateFlow()

    val audioEnergy: StateFlow<Float> = recordingManager.audioEnergy

    private var recognitionJob: Job? = null

    fun setPermissionRequired() {
        _uiState.value = AcrUiState.PermissionRequired
    }

    /**
     * 开始听歌识曲会话
     */
    fun startRecognition() {
        if (_uiState.value is AcrUiState.Listening || _uiState.value is AcrUiState.Recognizing) {
            return
        }

        val started = recordingManager.start()
        if (!started) {
            _uiState.value = AcrUiState.Failed("无法初始化麦克风录音设备")
            return
        }

        _uiState.value = AcrUiState.Listening(0f)

        recognitionJob?.cancel()
        recognitionJob =
            viewModelScope.launch {
                val startTime = System.currentTimeMillis()
                val sessionUniqueId = startTime
                val probeMilestones = listOf(3000L, 4500L, 6000L, 8000L, 10000L, 12000L)
                var nextProbeIndex = 0
                val maxDurationMs = 12500L

                var isSearching = false

                while (isActive) {
                    delay(100)
                    val elapsedMs = System.currentTimeMillis() - startTime
                    val elapsedSeconds = elapsedMs / 1000f

                    if (!isSearching && _uiState.value !is AcrUiState.Success) {
                        _uiState.value = AcrUiState.Listening(elapsedSeconds)
                    }

                    // 检查是否到达预定的切片探测检查点
                    if (!isSearching && nextProbeIndex < probeMilestones.size && elapsedMs >= probeMilestones[nextProbeIndex]) {
                        val milestone = probeMilestones[nextProbeIndex]
                        nextProbeIndex++
                        isSearching = true
                        android.util.Log.i(
                            "AcrViewModel",
                            "Probe milestone reached: ${milestone}ms (elapsed: ${elapsedMs}ms, sessionId: $sessionUniqueId)",
                        )

                        val samples =
                            withContext(Dispatchers.Default) {
                                recordingManager.getProcessedSnapshotSamples()
                            }

                        android.util.Log.i("AcrViewModel", "Snapshot samples count: ${samples.size}")
                        if (samples.isNotEmpty()) {
                            val feature =
                                withContext(Dispatchers.Default) {
                                    val pcm8k = AcousticFingerprintExtractor.downsample16kTo8k(samples)
                                    val feat = AcousticFingerprintExtractor.extract(pcm8k)
                                    android.util.Log.i(
                                        "AcrViewModel",
                                        "Extracted feature: dataSize=${feat?.data?.size ?: 0}, duration=${feat?.duration ?: 0f}",
                                    )
                                    feat
                                }

                            if (feature != null) {
                                val result =
                                    withContext(Dispatchers.IO) {
                                        recognizeClient.search(feature, sessionUniqueId)
                                    }
                                android.util.Log.i(
                                    "AcrViewModel",
                                    "Search result: success=${result.success}, title=${result.title}, artist=${result.artist}, error=${result.errorMessage}",
                                )

                                if (result.success && result.song != null) {
                                    recordingManager.stop()
                                    _uiState.value = AcrUiState.Success(result.song!!, result.offsetSeconds)
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

                    // 超时终止
                    if (elapsedMs >= maxDurationMs) {
                        recordingManager.stop()
                        _uiState.value = AcrUiState.Failed("未在当前环境录音中识别出匹配曲目，请靠近音源重试")
                        return@launch
                    }
                }
            }
    }

    /**
     * 重置并准备下一次识别
     */
    fun reset() {
        recognitionJob?.cancel()
        recognitionJob = null
        recordingManager.stop()
        _uiState.value = AcrUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        recognitionJob?.cancel()
        recordingManager.stop()
    }
}
