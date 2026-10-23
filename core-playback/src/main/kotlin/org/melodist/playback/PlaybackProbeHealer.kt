package org.melodist.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getSongVisualMid
import org.melodist.data.WebDavManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

/**
 * 音频音质探测与元数据/封面异步自愈调度器。
 */
class PlaybackProbeHealer(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val qualityCoordinator: AudioQualityCoordinator,
    private val queueManager: PlaybackQueueManager,
    private val lyricsCoordinator: PlaybackLyricsCoordinator,
    private val getContext: () -> Context?,
    private val getCurrentSong: () -> Song?,
    private val setCurrentSong: (Song) -> Unit,
    private val getCurrentTier: () -> AudioQualityTier,
    private val setCurrentTier: (AudioQualityTier) -> Unit,
    private val getPreferredTier: () -> AudioQualityTier,
    private val getCurrentTrackSpec: () -> AudioTrackSpec?,
    private val setCurrentTrackSpec: (AudioTrackSpec) -> Unit,
    private val onSwitchTierRequest: (AudioQualityTier) -> Unit,
    private val onUpdateMediaMetadataRequest: (Song) -> Unit,
    private val onPrefetchAdjacentCoversRequest: () -> Unit,
) {
    /**
     * 触发异步音质嗅探任务。
     *
     * @param song 目标曲目实体
     * @param delayMs 延迟执行毫秒数
     */
    fun launchProbeJob(
        song: Song,
        delayMs: Long,
    ) {
        qualityCoordinator.launchProbeJob(
            song = song,
            delayMs = delayMs,
            currentSongMidProvider = { getCurrentSong()?.songMid },
            currentTierProvider = { getCurrentTier() },
            preferredTierProvider = { getPreferredTier() },
            context = getContext(),
            onTrackSpecBitrateCalculated = { bitrate ->
                val curSpec = getCurrentTrackSpec()
                if (curSpec != null && curSpec.bitrateKbps <= 0) {
                    setCurrentTrackSpec(curSpec.copy(bitrateKbps = bitrate))
                }
            },
            onAutoUpgrade = { onSwitchTierRequest(it) },
        )
    }

    /**
     * 针对缺失封面的在线歌曲触发单曲封面异步修复。
     *
     * @param song 目标曲目实体
     */
    fun launchCoverHealJob(song: Song) {
        if (song.coverUrl.isNotBlank() || song.songMid.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val vsMid = apiService.getSongVisualMid(song.songMid)
                if (!vsMid.isNullOrBlank()) {
                    val singleCover = MusicApiService.getSingleCoverUrl(vsMid)
                    val healed = song.copy(visualMid = vsMid, coverUrl = singleCover)
                    withContext(Dispatchers.Main) {
                        if (getCurrentSong()?.songMid == song.songMid) {
                            setCurrentSong(healed)
                        }
                        queueManager.updateSongInPlaylist(healed)
                    }
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Self-heal single cover failed", e)
            }
        }
    }

    /**
     * 异步提取并修复 WebDAV 歌曲的音频元数据与内嵌封面。
     *
     * @param song 目标 WebDAV 曲目实体
     */
    fun triggerWebDavMetadataAndCoverHeal(song: Song) {
        scope.launch(Dispatchers.IO) {
            try {
                val server = WebDavManager.getActiveServer() ?: return@launch
                val meta = WebDavManager.extractPlaybackMetadata(server, song)
                if (getCurrentSong()?.songMid == song.songMid) {
                    val currentPlaying = getCurrentSong() ?: return@launch
                    val currentCover = currentPlaying.coverUrl.orEmpty()
                    val currentCoverFile =
                        if (currentCover.startsWith("file://")) {
                            File(currentCover.removePrefix("file://").substringBefore('?'))
                        } else {
                            null
                        }
                    val isCurrentCoverMissing =
                        currentCover.isBlank() || (currentCoverFile != null && (!currentCoverFile.exists() || currentCoverFile.length() == 0L))
                    val effectiveNewCover =
                        if (isCurrentCoverMissing && !meta.coverUrl.isNullOrBlank()) {
                            meta.coverUrl
                        } else if (!meta.coverUrl.isNullOrBlank() && currentCover.startsWith("file://")) {
                            meta.coverUrl
                        } else {
                            null
                        }
                    val newTier = meta.inferredTier

                    val rawNewTitle = meta.title?.trim()?.ifBlank { null }
                    val rawNewArtist = meta.artist?.trim()?.ifBlank { null }
                    val rawNewAlbum = meta.album?.trim()?.ifBlank { null }

                    val hasMetadataUpdate = rawNewTitle != null || rawNewArtist != null || rawNewAlbum != null
                    val hasCoverOrTierUpdate = !effectiveNewCover.isNullOrBlank() || newTier != null

                    if (hasMetadataUpdate || hasCoverOrTierUpdate) {
                        val versionedCover =
                            if (!effectiveNewCover.isNullOrBlank()) {
                                val clean = effectiveNewCover.substringBefore('?')
                                "$clean?t=${System.currentTimeMillis()}"
                            } else {
                                currentPlaying.coverUrl.orEmpty()
                            }
                        withContext(Dispatchers.Main) {
                            if (getCurrentSong()?.songMid == song.songMid) {
                                val baseSong = getCurrentSong() ?: return@withContext
                                var updated =
                                    baseSong.copy(
                                        coverUrl = versionedCover,
                                        rawCoverUrl = meta.rawCoverUrl ?: baseSong.rawCoverUrl.orEmpty(),
                                        currentTier = newTier ?: baseSong.currentTier,
                                    )
                                var metadataActuallyChanged = false
                                if (hasMetadataUpdate) {
                                    val enriched =
                                        PlaybackMetadataCoordinator.enrichSongMetadata(
                                            currentSong = updated,
                                            title = rawNewTitle,
                                            artist = rawNewArtist,
                                            album = rawNewAlbum,
                                        )
                                    if (enriched != null) {
                                        updated = enriched
                                        metadataActuallyChanged = true
                                    }
                                }
                                setCurrentSong(updated)
                                if (newTier != null) {
                                    setCurrentTier(newTier)
                                    qualityCoordinator.updateAvailableTiers(setOf(newTier))
                                }
                                queueManager.updateSongInPlaylist(updated)
                                onUpdateMediaMetadataRequest(updated)
                                if (metadataActuallyChanged) {
                                    lyricsCoordinator.loadLyricsForSong(updated)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Error extracting WebDAV song metadata", e)
            } finally {
                onPrefetchAdjacentCoversRequest()
            }
        }
    }
}
