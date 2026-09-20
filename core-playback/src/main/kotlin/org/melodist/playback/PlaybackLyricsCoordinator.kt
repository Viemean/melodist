package org.melodist.playback

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.melodist.api.LyricParser
import org.melodist.api.MusicApiService
import org.melodist.api.getLyrics
import org.melodist.data.LocalMusicManager
import org.melodist.data.LyricCacheManager
import org.melodist.data.WebDavManager
import org.melodist.model.LyricLine
import org.melodist.model.Song
import java.io.File

class PlaybackLyricsCoordinator(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val currentSongProvider: () -> Song?,
    private val onLyricsUpdated: (List<LyricLine>) -> Unit,
    private val onOffsetCalibrated: (Long) -> Unit,
    private val onLyricsLoadedBroadcast: (Pair<Song, List<LyricLine>>) -> Unit,
) {
    private var lyricLoadJob: Job? = null

    fun loadLyricsForSong(song: Song) {
        lyricLoadJob?.cancel()
        val cached = LyricCacheManager.getLyrics(song.songMid)
        if (cached != null && cached.isNotEmpty()) {
            onLyricsUpdated(cached)
        } else {
            onLyricsUpdated(emptyList())
        }
        val cachedOffset = LyricCacheManager.getLyricOffsetMs(song.songMid)
        onOffsetCalibrated(cachedOffset)

        lyricLoadJob =
            scope.launch(Dispatchers.IO) {
                try {
                    if (song.songMid.startsWith("webdav_")) {
                        val lrcText = WebDavManager.getSongLyrics(song)
                        val baseLyrics =
                            if (!lrcText.isNullOrBlank()) {
                                LyricParser.parseMergedLyrics(lrcText, null)
                            } else {
                                emptyList()
                            }
                        if (currentSongProvider()?.songMid == song.songMid && baseLyrics.isNotEmpty()) {
                            val current = LyricCacheManager.getLyrics(song.songMid).orEmpty()
                            if (LyricCacheManager.isBetterQuality(baseLyrics, current)) {
                                onLyricsUpdated(baseLyrics)
                                LyricCacheManager.saveLyrics(song.songMid, baseLyrics)
                                onLyricsLoadedBroadcast(song to baseLyrics)
                            }
                        }
                        val server = WebDavManager.getActiveServer()
                        val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
                        val validFile =
                            if (server != null && relativeHref.isNotBlank()) {
                                WebDavManager.fetchAudioSliceSample(server, relativeHref)
                            } else {
                                null
                            }

                        val matched = LocalLyricAutoMatcher.matchLyricsAsync(song, validFile, baseLyrics)
                        if (matched != null && matched.isNotEmpty() && currentSongProvider()?.songMid == song.songMid) {
                            Log.i("MelodistPlayback", "Applied auto-matched lyrics for WebDAV song: ${song.name}")
                            val current = LyricCacheManager.getLyrics(song.songMid).orEmpty()
                            if (LyricCacheManager.isBetterQuality(matched, current)) {
                                onLyricsUpdated(matched)
                                LyricCacheManager.saveLyrics(song.songMid, matched)
                                onLyricsLoadedBroadcast(song to matched)
                            }
                        }
                        if (!LyricCacheManager.hasLyricOffsetRecord(song.songMid)) {
                            val calibratedOffset = LocalLyricAutoMatcher.calibrateOffsetAsync(song, validFile)
                            if (currentSongProvider()?.songMid == song.songMid) {
                                onOffsetCalibrated(calibratedOffset)
                            }
                        }
                    } else if (!song.localFilePath.isNullOrBlank() || song.isLocal) {
                        val lrcText = LocalMusicManager.getSongLyrics(song)
                        val baseLyrics =
                            if (!lrcText.isNullOrBlank()) {
                                LyricParser.parseMergedLyrics(lrcText, null)
                            } else {
                                emptyList()
                            }
                        if (currentSongProvider()?.songMid == song.songMid && baseLyrics.isNotEmpty()) {
                            val current = LyricCacheManager.getLyrics(song.songMid).orEmpty()
                            if (LyricCacheManager.isBetterQuality(baseLyrics, current)) {
                                onLyricsUpdated(baseLyrics)
                                LyricCacheManager.saveLyrics(song.songMid, baseLyrics)
                                onLyricsLoadedBroadcast(song to baseLyrics)
                            }
                        }
                        val path = song.localFilePath
                        val directFile = if (!path.isNullOrBlank()) File(path) else null
                        val validFile = if (directFile != null && directFile.exists() && directFile.isFile) directFile else null
                        val matched = LocalLyricAutoMatcher.matchLyricsAsync(song, validFile, baseLyrics)
                        if (matched != null && matched.isNotEmpty() && currentSongProvider()?.songMid == song.songMid) {
                            Log.i("MelodistPlayback", "Applied auto-matched lyrics for local song: ${song.name}")
                            val current = LyricCacheManager.getLyrics(song.songMid).orEmpty()
                            if (LyricCacheManager.isBetterQuality(matched, current)) {
                                onLyricsUpdated(matched)
                                LyricCacheManager.saveLyrics(song.songMid, matched)
                                onLyricsLoadedBroadcast(song to matched)
                            }
                        }
                        if (!LyricCacheManager.hasLyricOffsetRecord(song.songMid)) {
                            val calibratedOffset = LocalLyricAutoMatcher.calibrateOffsetAsync(song, validFile)
                            if (currentSongProvider()?.songMid == song.songMid) {
                                onOffsetCalibrated(calibratedOffset)
                            }
                        }
                    } else {
                        val onlineLyrics =
                            apiService.getLyrics(
                                song.songMid,
                                song.songId,
                                songName = song.name,
                                singer = song.singer,
                            )
                        if (currentSongProvider()?.songMid == song.songMid && onlineLyrics.isNotEmpty()) {
                            val current = LyricCacheManager.getLyrics(song.songMid).orEmpty()
                            if (LyricCacheManager.isBetterQuality(onlineLyrics, current)) {
                                onLyricsUpdated(onlineLyrics)
                                LyricCacheManager.saveLyrics(song.songMid, onlineLyrics)
                                onLyricsLoadedBroadcast(song to onlineLyrics)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MelodistPlayback", "Error loading lyrics for ${song.name}", e)
                }
            }
    }

    fun setExternalLyrics(
        songMid: String,
        lyrics: List<LyricLine>,
        currentLyrics: List<LyricLine>,
        isCurrentSong: Boolean,
    ) {
        if (songMid.isBlank() || lyrics.isEmpty()) return
        if (LyricCacheManager.isBetterQuality(lyrics, currentLyrics)) {
            if (isCurrentSong) {
                onLyricsUpdated(lyrics)
            }
            LyricCacheManager.saveLyrics(songMid, lyrics, isRemoteSynced = true)
        }
    }

    fun cancel() {
        lyricLoadJob?.cancel()
    }
}
