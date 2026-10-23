package org.melodist.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.RecentHistoryType
import org.melodist.api.UserSession
import org.melodist.api.reportPlayStream
import org.melodist.api.reportRecentHistory
import org.melodist.data.ListeningStatsManager
import org.melodist.model.PlaybackSourceContext
import org.melodist.model.Song

/**
 * 播放统计与历史记录异步上报调度器。
 */
class PlaybackAnalyticsReporter(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val getContext: () -> Context?,
    private val getSourceContext: () -> PlaybackSourceContext?,
    private val getCurrentSong: () -> Song?,
) {
    private var hasReportedCurrentContext: Boolean = false

    /**
     * 重置当前播放上下文上报标志位。
     */
    fun resetContextReportedState() {
        hasReportedCurrentContext = false
    }

    /**
     * 上报单曲播放会话统计。
     *
     * @param song 播放曲目实体
     * @param playedDurationMs 累计播放时长（毫秒）
     */
    fun onSongPlaybackSessionFinished(
        song: Song,
        playedDurationMs: Long,
    ) {
        if (song.isLocal || song.isWebDav || song.songMid.startsWith("local_") || song.songMid.startsWith("webdav_")) {
            return
        }
        if (song.songId <= 0L) {
            return
        }
        if (!UserSession.isLoggedIn) {
            return
        }
        val playedSec = playedDurationMs / 1000L
        if (playedSec < 5L) {
            return
        }
        val durationSec = song.durationSeconds.toLong()
        val effectivePlaySec = if (durationSec > 0L) minOf(playedSec, durationSec) else playedSec
        if (effectivePlaySec < 5L) {
            return
        }
        val context = getContext()
        val deviceInfo = DeviceReportHelper.getDeviceInfo(context)

        ListeningStatsManager.recordPlaybackDelta(song, effectivePlaySec)

        scope.launch(Dispatchers.IO) {
            try {
                apiService.reportPlayStream(
                    songId = song.songId,
                    durationSec = durationSec,
                    playTimeSec = effectivePlaySec,
                    deviceInfo = deviceInfo,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("MelodistPlayback", "Failed to report play stream for song: ${song.songMid}", e)
            }
        }
    }

    /**
     * 上报当前曲目进入最近播放。
     *
     * @param song 播放曲目实体
     */
    fun onSongActivePlaybackQualified(song: Song) {
        if (song.isLocal || song.isWebDav || song.songMid.startsWith("local_") || song.songMid.startsWith("webdav_")) {
            return
        }
        if (!UserSession.isLoggedIn) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val reportId = if (song.songId > 0L) song.songId.toString() else song.songMid
                if (reportId.isNotBlank()) {
                    apiService.reportRecentHistory(
                        id = reportId,
                        type = RecentHistoryType.Song,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("MelodistPlayback", "Failed to report recent history for song: ${song.songMid}", e)
            }
        }
    }

    /**
     * 上报当前歌单或专辑进入最近播放上下文。
     */
    fun onContextActivePlaybackQualified() {
        if (hasReportedCurrentContext) return
        val context = getSourceContext() ?: return
        val currSong = getCurrentSong()
        if (currSong == null || currSong.isLocal || currSong.isWebDav || currSong.songMid.startsWith("local_") || currSong.songMid.startsWith("webdav_")) {
            return
        }
        if (!UserSession.isLoggedIn) {
            return
        }
        hasReportedCurrentContext = true
        scope.launch(Dispatchers.IO) {
            try {
                when (context) {
                    is PlaybackSourceContext.Album -> {
                        val id = if (context.albumId > 0L) context.albumId.toString() else context.albumMid
                        if (id.isNotBlank() && !id.startsWith("local_") && !id.startsWith("webdav_")) {
                            apiService.reportRecentHistory(id = id, type = RecentHistoryType.Album)
                        }
                    }

                    is PlaybackSourceContext.Playlist -> {
                        val id = context.id
                        if (id.isNotBlank() && !id.startsWith("local_") && !id.startsWith("webdav_")) {
                            apiService.reportRecentHistory(id = id, type = RecentHistoryType.Playlist)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("MelodistPlayback", "Failed to report recent context: $context", e)
            }
        }
    }
}
