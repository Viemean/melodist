package org.melodist.playback

import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 播放器核心对外暴露的不可变聚合状态快照。
 * 供移动端、平板与 TV 端 UI 统一订阅，消除散落的多次独立 flow 收集。
 */
data class PlaybackSnapshot(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val loopMode: PlaybackLoopMode = PlaybackLoopMode.ListRepeat,
    val currentTier: AudioQualityTier = AudioQualityTier.Standard,
    val isRadioMode: Boolean = false,
    val queueSize: Int = 0,
    val currentIndex: Int = -1,
    val isLoading: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasNext: Boolean
        get() = if (isRadioMode) true else queueSize > 1 && currentIndex in 0 until queueSize - 1

    val hasPrevious: Boolean
        get() = if (isRadioMode) currentIndex > 0 else queueSize > 1 && currentIndex > 0
}
