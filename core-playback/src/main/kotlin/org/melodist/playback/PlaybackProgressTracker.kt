package org.melodist.playback

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.melodist.data.AppLifecycleManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 负责播放进度轮询、边播边存文件缓存占比计算、自动预拉取触发与定期进度持久化。
 * 支持根据应用前后台状态自适应降频（前台 60ms 高刷，后台 1000ms 低功耗），
 * 并在用户由后台切回前台时即刻唤醒刷新，保障 UI 零延迟响应。
 */
class PlaybackProgressTracker(
    private val scope: CoroutineScope,
    private val getPlayer: () -> ExoPlayer?,
    private val isRemoteActive: () -> Boolean,
    private val getEstimatedRemotePositionMs: (durationMs: Long) -> Long?,
    private val getCurrentSong: () -> Song?,
    private val getCurrentTier: () -> AudioQualityTier,
    private val isCurrentTrackFromCache: () -> Boolean,
    private val getDurationMs: () -> Long,
    private val isSongFavorite: (songMid: String) -> Boolean,
    private val onPositionUpdated: (positionMs: Long) -> Unit,
    private val onDurationUpdated: (durationMs: Long) -> Unit,
    private val onBufferedPositionUpdated: (bufferedMs: Long) -> Unit,
    private val onFileCacheFractionUpdated: (fraction: Float) -> Unit,
    private val onTrackFromCacheConfirmed: () -> Unit,
    private val onSavePlaybackProgressRequest: (posMs: Long) -> Unit,
    private val onTriggerPrefetchNextSongRequest: () -> Unit,
) {
    private var progressJob: Job? = null

    fun start() {
        progressJob?.cancel()
        progressJob =
            scope.launch {
                var lastSaveTimestampMs = 0L
                var lastPrefetchCheckTimestampMs = 0L
                while (isActive) {
                    val isAppForeground = AppLifecycleManager.isForeground.value
                    if (isRemoteActive()) {
                        val estimated = getEstimatedRemotePositionMs(getDurationMs())
                        if (estimated != null) {
                            onPositionUpdated(estimated)
                        }
                        if (isAppForeground) {
                            delay(50L)
                        } else {
                            withTimeoutOrNull(1000L) {
                                AppLifecycleManager.isForeground.first { it }
                            }
                        }
                        continue
                    }
                    getPlayer()?.let { player ->
                        val currentBuf = player.bufferedPosition.coerceAtLeast(0L)
                        onBufferedPositionUpdated(currentBuf)

                        val currSong = getCurrentSong()
                        val mid = currSong?.songMid
                        if (!mid.isNullOrBlank()) {
                            val curTier = getCurrentTier()
                            val isLocal = currSong.isLocal || mid.startsWith("local_") || !currSong.localFilePath.isNullOrBlank()
                            if (isLocal || isCurrentTrackFromCache()) {
                                onFileCacheFractionUpdated(1f)
                            } else if (mid.startsWith("webdav_") || currSong.isWebDav) {
                                val dur = player.duration.takeIf { it > 0L } ?: getDurationMs()
                                val streamBufFraction = if (dur > 0L) (currentBuf.toFloat() / dur).coerceIn(0f, 1f) else 0f
                                onFileCacheFractionUpdated(streamBufFraction)
                            } else {
                                val dur = player.duration.takeIf { it > 0L } ?: getDurationMs()
                                val streamBufFraction = if (dur > 0L) (currentBuf.toFloat() / dur).coerceIn(0f, 1f) else 0f
                                val fileProgress = MelodistCacheManager.getSongFileCacheProgress(mid, curTier)
                                val combinedFraction = maxOf(fileProgress.fraction, streamBufFraction)
                                onFileCacheFractionUpdated(combinedFraction)
                                if (fileProgress.isFullyCached || combinedFraction >= 1f) {
                                    onFileCacheFractionUpdated(1f)
                                    onTrackFromCacheConfirmed()
                                    MelodistCacheManager.confirmCacheRetention(mid, curTier)
                                }
                            }
                        }

                        if (player.isPlaying) {
                            val pos = player.currentPosition.coerceAtLeast(0L)
                            val dur = player.duration
                            onPositionUpdated(pos)
                            if (dur > 0L) {
                                onDurationUpdated(dur)
                                if (!mid.isNullOrBlank()) {
                                    MelodistCacheManager.recordPlayProgress(mid, pos, dur)
                                    val curTier = getCurrentTier()
                                    if (!isCurrentTrackFromCache()) {
                                        val isFav = isSongFavorite(mid)
                                        if (MelodistCacheManager.shouldCacheSong(mid, isFav, curTier)) {
                                            val thresholdMs = MelodistCacheManager.currentProfile.getTrialThresholdMs(dur)
                                            if (pos >= thresholdMs) {
                                                MelodistCacheManager.confirmCacheRetention(mid, curTier)
                                                if (MelodistCacheManager.isSongTierFullyCached(mid, curTier)) {
                                                    onTrackFromCacheConfirmed()
                                                    onFileCacheFractionUpdated(1f)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastSaveTimestampMs >= 15_000L) {
                                lastSaveTimestampMs = now
                                onSavePlaybackProgressRequest(pos)
                            }
                            if (now - lastPrefetchCheckTimestampMs >= 1_000L) {
                                lastPrefetchCheckTimestampMs = now
                                if (PlaybackSourceResolver.shouldTriggerPrefetch(dur, pos)) {
                                    onTriggerPrefetchNextSongRequest()
                                }
                            }
                        }
                    }
                    if (isAppForeground) {
                        delay(60L)
                    } else {
                        // 后台状态：降低轮询唤醒至 1000ms；若用户切回前台，第一时间内退出等待即刻刷新
                        withTimeoutOrNull(1000L) {
                            AppLifecycleManager.isForeground.first { it }
                        }
                    }
                }
            }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
    }
}
