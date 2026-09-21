package org.melodist.mobile.origin

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import com.tencent.qqmusic.third.api.contract.IQQMusicApi
import com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback
import com.tencent.qqmusic.third.api.contract.IQQMusicApiEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

open class QQMusicApiService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val callbackList = RemoteCallbackList<IQQMusicApiEventListener>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "QQMusicApiService created")

        serviceScope.launch {
            PlaybackManager.isPlaying.collectLatest { playing ->
                val state = if (playing) PLAY_STATUS_STARTED else PLAY_STATUS_PAUSED
                val bundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putInt("data", state)
                        putInt("playState", state)
                    }
                dispatchSystemEvent(EVENT_PLAY_STATE_CHANGED, bundle)
            }
        }

        serviceScope.launch {
            PlaybackManager.currentSong.collectLatest { song ->
                val songJson = buildSongJson(song)
                val bundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", songJson)
                        putString("playSong", songJson)
                        putString("song", songJson)
                    }
                dispatchSystemEvent(EVENT_PLAY_SONG_CHANGED, bundle)
            }
        }

        serviceScope.launch {
            PlaybackManager.loopMode.collectLatest { loopMode ->
                val mode =
                    when (loopMode) {
                        org.melodist.playback.PlaybackLoopMode.ListRepeat -> 0
                        org.melodist.playback.PlaybackLoopMode.SingleRepeat -> 1
                        org.melodist.playback.PlaybackLoopMode.Shuffle -> 2
                    }
                val bundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putInt("data", mode)
                        putInt("playMode", mode)
                    }
                dispatchSystemEvent(EVENT_PLAY_MODE_CHANGED, bundle)
            }
        }

        serviceScope.launch {
            PlaybackManager.lyrics.collectLatest { lyrics ->
                if (lyrics.isNotEmpty()) {
                    val songJson = buildSongJson(PlaybackManager.currentSong.value)
                    val bundle =
                        Bundle().apply {
                            putInt("code", 0)
                            putString("data", songJson)
                            putString("playSong", songJson)
                            putString("song", songJson)
                        }
                    dispatchSystemEvent(EVENT_PLAY_SONG_CHANGED, bundle)
                }
            }
        }

        serviceScope.launch {
            org.melodist.data.AppSettingsManager.settings
                .map { it.showBilingualLyrics }
                .distinctUntilChanged()
                .collectLatest {
                    if (PlaybackManager.lyrics.value.isNotEmpty()) {
                        val songJson = buildSongJson(PlaybackManager.currentSong.value)
                        val bundle =
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", songJson)
                                putString("playSong", songJson)
                                putString("song", songJson)
                            }
                        dispatchSystemEvent(EVENT_PLAY_SONG_CHANGED, bundle)
                    }
                }
        }

        serviceScope.launch {
            org.melodist.data.UserLibraryCacheManager.favoriteSongsFlow.collectLatest { favList ->
                val currSong = PlaybackManager.currentSong.value ?: return@collectLatest
                val isFav = favList.any { it.songMid == currSong.songMid || (currSong.songId > 0 && it.songId == currSong.songId) }
                val favBundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putString("song", buildSongJson(currSong))
                        putBoolean("isFavorite", isFav)
                    }
                dispatchSystemEvent("API_EVENT_SONG_FAVORITE_STATE_CHANGED", favBundle)
            }
        }

        try {
            val initIntent = Intent("vivo.intent.musicwidgetmix.notify.init")
            sendBroadcast(initIntent, "vivo.intent.musicwidgetmix.notify.init.PERMISSION")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast notify.init", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callbackList.kill()
        serviceScope.cancel()
        Log.i(TAG, "QQMusicApiService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind action=${intent?.action}")
        return binder
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun formatLyricsToLrc(lyrics: List<org.melodist.model.LyricLine>): String {
        if (lyrics.isEmpty()) return ""
        val showBilingual = org.melodist.data.AppSettingsManager.settings.value.showBilingualLyrics
        val sb = StringBuilder()
        var transCount = 0
        for (line in lyrics) {
            val ms = line.timestampMs
            val min = ms / 60000
            val sec = (ms % 60000) / 1000
            val hundredths = (ms % 1000) / 10
            val text =
                if (showBilingual && line.hasTranslation) {
                    transCount++
                    "${line.text}^${line.transText}"
                } else {
                    line.text
                }
            sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", min, sec, hundredths, text))
        }
        return sb.toString()
    }

    private val binder =
        object : IQQMusicApi.Stub() {
            override fun execute(
                action: String?,
                params: Bundle?,
            ): Bundle {
                val callingUid = android.os.Binder.getCallingUid()
                val callingPid = android.os.Binder.getCallingPid()
                val callingPkg = packageManager.getNameForUid(callingUid)
                if (action != "getCurrTime" && action != "getTotalTime") {
                    Log.d(TAG, "execute: action=$action, callingUid=$callingUid, callingPid=$callingPid, callingPkg=$callingPkg")
                }
                val act = action ?: return Bundle().apply { putInt("code", -1) }

                return try {
                    when (act) {
                        "hi" -> {
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", "hello")
                            }
                        }

                        "getPlaybackState" -> {
                            val currSong = PlaybackManager.currentSong.value
                            val state =
                                if (currSong == null) {
                                    PLAY_STATUS_STOPPED
                                } else if (PlaybackManager.isPlaying.value) {
                                    PLAY_STATUS_STARTED
                                } else {
                                    PLAY_STATUS_PAUSED
                                }
                            Bundle().apply {
                                putInt("code", 0)
                                putInt("data", state)
                                putInt("playState", state)
                            }
                        }

                        "getCurrentSong" -> {
                            val songJson = buildSongJson(PlaybackManager.currentSong.value)
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", songJson)
                                putString("playSong", songJson)
                                putString("song", songJson)
                            }
                        }

                        "getCurrTime" -> {
                            val pos = PlaybackManager.currentPositionMs.value
                            Bundle().apply {
                                putInt("code", 0)
                                putLong("data", pos)
                                putLong("time", pos)
                            }
                        }

                        "getTotalTime" -> {
                            val dur = PlaybackManager.durationMs.value
                            Bundle().apply {
                                putInt("code", 0)
                                putLong("data", dur)
                                putLong("time", dur)
                            }
                        }

                        "getLyricWithId" -> {
                            val lyrics = PlaybackManager.lyrics.value
                            val lrcText = formatLyricsToLrc(lyrics)
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", lrcText)
                            }
                        }

                        "resume", "playMusic", "play", "resumeMusic" -> {
                            runOnMain { PlaybackManager.play() }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "pause", "pauseMusic" -> {
                            runOnMain { PlaybackManager.pause() }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "playNext", "skipToNext" -> {
                            runOnMain { PlaybackManager.playNext() }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "playPrevious", "skipToPrevious" -> {
                            runOnMain { PlaybackManager.playPrevious() }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "playSongIdAtIndex" -> {
                            val index =
                                when {
                                    params?.containsKey("index") == true -> params.getInt("index", -1)
                                    params?.containsKey("playIndex") == true -> params.getInt("playIndex", -1)
                                    params?.containsKey("musicIndex") == true -> params.getInt("musicIndex", -1)
                                    else -> -1
                                }
                            val songIdList = params?.getStringArrayList("songIdList")
                            runOnMain {
                                if (index >= 0) {
                                    val currentPlaylist = PlaybackManager.playlist.value
                                    val targetSong =
                                        if (!songIdList.isNullOrEmpty() && index < songIdList.size) {
                                            val targetSongId = songIdList[index]
                                            currentPlaylist.find { it.songMid == targetSongId || it.songId.toString() == targetSongId }
                                                ?: org.melodist.data.UserLibraryCacheManager.favoriteSongsFlow.value.find {
                                                    it.songMid == targetSongId ||
                                                        it.songId.toString() == targetSongId
                                                }
                                                ?: org.melodist.data.LocalMusicManager.getScannedSongs().find {
                                                    it.songMid == targetSongId ||
                                                        it.songId.toString() == targetSongId
                                                }
                                                ?: org.melodist.data.download.DownloadManager.completedTasks.value.map { it.song }.find {
                                                    it.songMid == targetSongId ||
                                                        it.songId.toString() == targetSongId
                                                }
                                        } else {
                                            null
                                        }

                                    if (targetSong != null) {
                                        PlaybackManager.playSong(targetSong)
                                    } else if (index < currentPlaylist.size) {
                                        PlaybackManager.playSong(currentPlaylist[index])
                                    }
                                }
                            }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "seekPos", "seek" -> {
                            val pos =
                                when {
                                    params?.containsKey("pos") == true -> params.getLong("pos", -1L)
                                    params?.containsKey("position") == true -> params.getLong("position", -1L)
                                    else -> -1L
                                }
                            val finalPos =
                                if (pos >= 0L) {
                                    pos
                                } else {
                                    (params?.getInt("pos", 0) ?: 0).toLong()
                                }
                            runOnMain { PlaybackManager.seekTo(finalPos) }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "seekForward" -> {
                            val delta = params?.getLong("time", 0L) ?: 0L
                            runOnMain { PlaybackManager.seekTo(PlaybackManager.currentPositionMs.value + delta) }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "seekBack" -> {
                            val delta = params?.getLong("time", 0L) ?: 0L
                            runOnMain { PlaybackManager.seekTo((PlaybackManager.currentPositionMs.value - delta).coerceAtLeast(0L)) }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "addToFavourite", "removeFromFavourite" -> {
                            runOnMain { PlaybackManager.toggleCurrentSongFavorite() }
                            Bundle().apply { putInt("code", 0) }
                        }

                        "isFavouriteMid" -> {
                            val isFav = PlaybackManager.isSongFavorite(PlaybackManager.currentSong.value?.songMid)
                            Bundle().apply {
                                putInt("code", 0)
                                putBooleanArray("data", booleanArrayOf(isFav))
                            }
                        }

                        "getFavouriteFolderId", "getFavoriteFolderId" -> {
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", "fav_folder_101")
                            }
                        }

                        "getPlayList" -> {
                            val list = PlaybackManager.playlist.value
                            val array = org.json.JSONArray()
                            for (song in list) {
                                array.put(JSONObject(buildSongJson(song)))
                            }
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", array.toString())
                                putBoolean("hasMore", false)
                            }
                        }

                        "getSongList", "getUserSongList" -> {
                            val folderType = params?.getInt("folderType", -1) ?: -1
                            val folderId = params?.getString("folderId") ?: ""
                            val songs: List<Song> =
                                when {
                                    folderType == 100 || folderId == "100" -> {
                                        val downloaded =
                                            org.melodist.data.download.DownloadManager.completedTasks.value
                                                .map { it.song }
                                        val scanned =
                                            org.melodist.data.LocalMusicManager
                                                .getScannedSongs()
                                        (downloaded + scanned).distinctBy { it.songMid.ifBlank { it.songId.toString() } }
                                    }
                                    folderType == 101 || folderId == "fav_folder_101" -> {
                                        org.melodist.data.UserLibraryCacheManager.favoriteSongsFlow.value
                                    }
                                    else -> {
                                        emptyList()
                                    }
                                }
                            val array = org.json.JSONArray()
                            for (song in songs) {
                                array.put(JSONObject(buildSongJson(song)))
                            }
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", array.toString())
                                putBoolean("hasMore", false)
                            }
                        }

                        "getPlayMode", "getLoopMode" -> {
                            val mode =
                                when (PlaybackManager.loopMode.value) {
                                    org.melodist.playback.PlaybackLoopMode.ListRepeat -> 0
                                    org.melodist.playback.PlaybackLoopMode.SingleRepeat -> 1
                                    org.melodist.playback.PlaybackLoopMode.Shuffle -> 2
                                }
                            Bundle().apply {
                                putInt("code", 0)
                                putInt("data", mode)
                                putInt("playMode", mode)
                            }
                        }

                        "setPlayMode" -> {
                            runOnMain { PlaybackManager.cycleLoopMode() }
                            Bundle().apply { putInt("code", 0) }
                        }

                        else -> {
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", "")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing action: $act", e)
                    Bundle().apply {
                        putInt("code", -1)
                        putString("msg", e.message ?: "Unknown error")
                    }
                }
            }

            override fun executeAsync(
                action: String?,
                params: Bundle?,
                callback: IQQMusicApiCallback?,
            ) {
                val result = execute(action, params)
                if (callback != null) {
                    try {
                        callback.onReturn(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking callback for action: $action", e)
                    }
                }
            }

            override fun registerEventListener(
                events: MutableList<String>?,
                listener: IQQMusicApiEventListener?,
            ): Bundle {
                val callingUid = android.os.Binder.getCallingUid()
                val callingPid = android.os.Binder.getCallingPid()
                val callingPkg = packageManager.getNameForUid(callingUid)
                Log.d(TAG, "registerEventListener: callingPkg=$callingPkg, events=$events")
                if (listener != null) {
                    val registered = callbackList.register(listener)
                    Log.i(TAG, "registerEventListener: events=$events, registered=$registered")
                    // Immediate initial state push to the newly connected listener
                    serviceScope.launch {
                        try {
                            val state = if (PlaybackManager.isPlaying.value) PLAY_STATUS_STARTED else PLAY_STATUS_PAUSED
                            listener.onEvent(
                                EVENT_PLAY_STATE_CHANGED,
                                Bundle().apply {
                                    putInt("code", 0)
                                    putInt("data", state)
                                    putInt("playState", state)
                                },
                            )
                            val songJson = buildSongJson(PlaybackManager.currentSong.value)
                            listener.onEvent(
                                EVENT_PLAY_SONG_CHANGED,
                                Bundle().apply {
                                    putInt("code", 0)
                                    putString("data", songJson)
                                    putString("playSong", songJson)
                                    putString("song", songJson)
                                },
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed initial push to listener", e)
                        }
                    }
                }
                return Bundle().apply { putInt("code", 0) }
            }

            override fun unregisterEventListener(
                events: MutableList<String>?,
                listener: IQQMusicApiEventListener?,
            ): Bundle {
                if (listener != null) {
                    val unregistered = callbackList.unregister(listener)
                    Log.i(TAG, "unregisterEventListener: unregistered=$unregistered")
                }
                return Bundle().apply { putInt("code", 0) }
            }
        }

    private fun dispatchSystemEvent(
        event: String,
        bundle: Bundle,
    ) {
        val count = callbackList.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbackList.getBroadcastItem(i).onEvent(event, bundle)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send event to broadcast item $i", e)
                }
            }
        } finally {
            callbackList.finishBroadcast()
        }
    }

    private fun buildSongJson(song: Song?): String {
        if (song == null) return "{}"
        val primaryArtist = song.singerList.firstOrNull()
        val singerObj =
            JSONObject().apply {
                put("id", primaryArtist?.id ?: 0L)
                put("mid", primaryArtist?.mid?.ifBlank { "0" } ?: "0")
                put("title", song.singer)
            }
        val albumObj =
            JSONObject().apply {
                put("id", 0L)
                put("mid", song.albumMid.ifBlank { "0" })
                put("title", song.album)
                put("coverUri", song.coverUrl)
            }
        return JSONObject()
            .apply {
                put("id", song.songId.toString())
                put("mid", song.songMid)
                put("title", song.name)
                put("type", 0)
                put("singer", singerObj)
                put("album", albumObj)
            }.toString()
    }

    companion object {
        private const val TAG = "MelodistQQMusicApi"

        private const val PLAY_STATUS_STOPPED = 3
        private const val PLAY_STATUS_STARTED = 4
        private const val PLAY_STATUS_PAUSED = 5

        private const val EVENT_PLAY_STATE_CHANGED = "API_EVENT_PLAY_STATE_CHANGED"
        private const val EVENT_PLAY_SONG_CHANGED = "API_EVENT_PLAY_SONG_CHANGED"
        private const val EVENT_PLAY_MODE_CHANGED = "API_EVENT_PLAY_MODE_CHANGED"
    }
}
