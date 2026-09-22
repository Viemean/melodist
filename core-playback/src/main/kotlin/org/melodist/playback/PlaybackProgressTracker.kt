package org.melodist.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 负责高频播放进度轮询、边播边存文件缓存占比计算、自动预拉取触发与定期进度持久化
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
    private val onSongActivePlaybackQualified: ((Song) -> Unit)? = null,
    private val onContextActivePlaybackQualified: (() -> Unit)? = null,
) {
    private var progressJob: Job? = null

    fun start() {
        progressJob?.cancel()
        progressJob =
            scope.launch {
                var saveCounter = 0
                var prefetchCounter = 0
                var lastTrackedSongMid: String? = null
                var activePlayDurationMs = 0L
                var lastTickTime = android.os.SystemClock.elapsedRealtime()
                var hasReportedCurrentSong = false

                while (isActive) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val elapsedDelta = (now - lastTickTime).coerceAtLeast(0L)
                    lastTickTime = now

                    if (isRemoteActive()) {
                        val estimated = getEstimatedRemotePositionMs(getDurationMs())
                        if (estimated != null) {
                            onPositionUpdated(estimated)
                        }
                        delay(50L)
                        continue
                    }
                    getPlayer()?.let { player ->
                        val currentBuf = player.bufferedPosition.coerceAtLeast(0L)
                        onBufferedPositionUpdated(currentBuf)

                        val currSong = getCurrentSong()
                        val mid = currSong?.songMid
                        if (mid != lastTrackedSongMid) {
                            lastTrackedSongMid = mid
                            activePlayDurationMs = 0L
                            hasReportedCurrentSong = false
                        }

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
                            activePlayDurationMs += elapsedDelta
                            if (activePlayDurationMs >= 5000L && !hasReportedCurrentSong) {
                                hasReportedCurrentSong = true
                                currSong?.let { onSongActivePlaybackQualified?.invoke(it) }
                            }
                            if (activePlayDurationMs >= 15000L) {
                                onContextActivePlaybackQualified?.invoke()
                            }

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
                            saveCounter++
                            if (saveCounter >= 250) {
                                saveCounter = 0
                                onSavePlaybackProgressRequest(pos)
                            }
                            prefetchCounter++
                            if (prefetchCounter >= 16) {
                                prefetchCounter = 0
                                if (PlaybackSourceResolver.shouldTriggerPrefetch(dur, pos)) {
                                    onTriggerPrefetchNextSongRequest()
                                }
                            }
                        }
                    }
                    delay(60L)
                }
            }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
    }
}
