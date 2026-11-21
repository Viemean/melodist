package org.melodist.mobile.origin

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.qqmusic.third.api.contract.IQQMusicApi
import com.tencent.qqmusic.third.api.contract.IQQMusicApiCallback
import com.tencent.qqmusic.third.api.contract.IQQMusicApiEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.melodist.api.MusicApiService
import org.melodist.api.search
import org.melodist.data.DailyRecommendCacheManager
import org.melodist.data.LocalMusicManager
import org.melodist.data.LyricCacheManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.data.download.DownloadManager
import org.melodist.model.Song
import org.melodist.playback.PlaybackLoopMode
import org.melodist.playback.PlaybackManager

/**
 * QQ 音乐 AIDL 接口请求的分发与业务路由器。
 */
class QQMusicCommandDispatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val eventNotifier: QQMusicEventNotifier,
) : IQQMusicApi.Stub() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLyricJob: Job? = null

    @Volatile
    private var lastSearchResults: List<Song> = emptyList()

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    override fun execute(
        action: String?,
        params: Bundle?,
    ): Bundle {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val callingPkg = context.packageManager.getNameForUid(callingUid)
        val isHighFrequencyAction =
            action == "getCurrTime" ||
                action == "getTotalTime" ||
                action == "isFavouriteMid"
        if (!isHighFrequencyAction) {
            Log.i(TAG, "execute: action=$action, callingUid=$callingUid, callingPid=$callingPid, callingPkg=$callingPkg, params=$params")
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

                "search" -> {
                    handleSearchSync(params)
                }

                "voicePlay", "voiceShortcut", "playBySongName", "playByQuery" -> {
                    handleVoicePlay(params)
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", "success")
                        putString("msg", "success")
                    }
                }

                "getPlaybackState" -> {
                    val currSong = PlaybackManager.currentSong.value
                    val state =
                        if (currSong == null) {
                            QQMusicDataConverter.PLAY_STATUS_STOPPED
                        } else if (PlaybackManager.isPlaying.value) {
                            QQMusicDataConverter.PLAY_STATUS_STARTED
                        } else {
                            QQMusicDataConverter.PLAY_STATUS_PAUSED
                        }
                    Bundle().apply {
                        putInt("code", 0)
                        putInt("data", state)
                        putInt("playState", state)
                    }
                }

                "getCurrentSong" -> {
                    val songJson = QQMusicDataConverter.buildSongJson(context, PlaybackManager.currentSong.value)
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", songJson)
                        putString("playSong", songJson)
                        putString("song", songJson)
                    }
                }

                "getCurrTime" -> {
                    val pos = PlaybackManager.currentPositionMs.value
                    val posInt = pos.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                    Bundle().apply {
                        putInt("code", 0)
                        putLong("data", pos)
                        putInt("dataInt", posInt)
                        putLong("time", pos)
                        putInt("timeInt", posInt)
                        putLong("currTime", pos)
                        putInt("currTimeInt", posInt)
                        putLong("position", pos)
                        putInt("positionInt", posInt)
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
                    val requestedSongId = extractSongIdFromParams(params)
                    var lyrics = PlaybackManager.lyrics.value
                    val curr = PlaybackManager.currentSong.value
                    val targetMid =
                        if (requestedSongId.isNotBlank() && curr != null &&
                            (QQMusicDataConverter.resolveSongId(curr) == requestedSongId || curr.songMid == requestedSongId)
                        ) {
                            curr.songMid
                        } else if (requestedSongId.isNotBlank()) {
                            requestedSongId
                        } else {
                            curr?.songMid.orEmpty()
                        }
                    if (lyrics.isEmpty() && targetMid.isNotBlank()) {
                        lyrics = LyricCacheManager.getLyrics(targetMid).orEmpty()
                    }
                    val lrcText = QQMusicDataConverter.formatLyricsToLrc(lyrics)
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

                "playSongIdAtIndex", "playSongMidAtIndex" -> {
                    handlePlaySongAtIndex(params)
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
                    runOnMain {
                        PlaybackManager.seekTo(finalPos)
                    }
                    Bundle().apply { putInt("code", 0) }
                }

                "seekForward" -> {
                    val delta = params?.getLong("time", 0L) ?: 0L
                    runOnMain {
                        val target = PlaybackManager.currentPositionMs.value + delta
                        PlaybackManager.seekTo(target)
                    }
                    Bundle().apply { putInt("code", 0) }
                }

                "seekBack" -> {
                    val delta = params?.getLong("time", 0L) ?: 0L
                    runOnMain {
                        val target = (PlaybackManager.currentPositionMs.value - delta).coerceAtLeast(0L)
                        PlaybackManager.seekTo(target)
                    }
                    Bundle().apply { putInt("code", 0) }
                }

                "addToFavourite" -> {
                    handleFavoriteChange(params, isAdd = true)
                    Bundle().apply { putInt("code", 0) }
                }

                "removeFromFavourite" -> {
                    handleFavoriteChange(params, isAdd = false)
                    Bundle().apply { putInt("code", 0) }
                }

                "isFavouriteMid" -> {
                    val midList = params?.getStringArrayList("midList")
                    val singleMid = params?.getString("mid") ?: params?.getString("songMid")
                    val boolArray =
                        when {
                            !midList.isNullOrEmpty() -> {
                                BooleanArray(midList.size) { i -> PlaybackManager.isSongFavorite(midList[i]) }
                            }

                            !singleMid.isNullOrBlank() -> {
                                booleanArrayOf(PlaybackManager.isSongFavorite(singleMid))
                            }

                            else -> {
                                val isFav = PlaybackManager.isSongFavorite(PlaybackManager.currentSong.value?.songMid)
                                booleanArrayOf(isFav)
                            }
                        }
                    Bundle().apply {
                        putInt("code", 0)
                        putBooleanArray("data", boolArray)
                    }
                }

                "getFavouriteFolderId", "getFavoriteFolderId" -> {
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", "fav_folder_101")
                    }
                }

                "getFolderList" -> {
                    handleGetFolderList(params)
                }

                "getPlayList" -> {
                    val list = PlaybackManager.playlist.value
                    val array = JSONArray()
                    for (song in list) {
                        array.put(JSONObject(QQMusicDataConverter.buildSongJson(context, song)))
                    }
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", array.toString())
                        putBoolean("hasMore", false)
                    }
                }

                "getSongList", "getUserSongList" -> {
                    handleGetSongList(params)
                }

                "getPlayMode", "getLoopMode" -> {
                    val mode = QQMusicDataConverter.convertLoopModeToQQMode(PlaybackManager.loopMode.value)
                    Bundle().apply {
                        putInt("code", 0)
                        putInt("data", mode)
                        putInt("playMode", mode)
                    }
                }

                "setPlayMode", "setLoopMode", "switchPlayMode" -> {
                    handleSetPlayMode(params)
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
        Log.i(TAG, "executeAsync: action=$action, params=$params")
        if (action == "getLyricWithId" && callback != null) {
            val requestedSongId = extractSongIdFromParams(params)
            pendingLyricJob?.cancel()
            pendingLyricJob =
                scope.launch {
                    var lyrics = PlaybackManager.lyrics.value
                    val currSong = PlaybackManager.currentSong.value
                    val targetMid =
                        if (requestedSongId.isNotBlank() && currSong != null &&
                            (QQMusicDataConverter.resolveSongId(currSong) == requestedSongId || currSong.songMid == requestedSongId)
                        ) {
                            currSong.songMid
                        } else if (requestedSongId.isNotBlank()) {
                            requestedSongId
                        } else {
                            currSong?.songMid.orEmpty()
                        }

                    if (lyrics.isEmpty() && targetMid.isNotBlank()) {
                        lyrics = LyricCacheManager.getLyrics(targetMid).orEmpty()
                    }
                    if (lyrics.isEmpty()) {
                        withTimeoutOrNull(2500L) {
                            PlaybackManager.lyrics.first { it.isNotEmpty() }
                        }?.let {
                            lyrics = it
                        }
                    }
                    val lrcText = QQMusicDataConverter.formatLyricsToLrc(lyrics)
                    val result =
                        Bundle().apply {
                            putInt("code", 0)
                            putString("data", lrcText)
                        }
                    try {
                        callback.onReturn(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error invoking callback for getLyricWithId", e)
                    }
                }
            return
        }

        if (action == "search" && callback != null) {
            handleSearchAsync(params, callback)
            return
        }

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
    ): Bundle = eventNotifier.registerEventListener(events, listener)

    override fun unregisterEventListener(
        events: MutableList<String>?,
        listener: IQQMusicApiEventListener?,
    ): Bundle = eventNotifier.unregisterEventListener(events, listener)

    private fun extractSongIdFromParams(params: Bundle?): String =
        when {
            params?.containsKey("songId") == true -> params.getString("songId").orEmpty()
            params?.containsKey("song_id") == true -> params.getString("song_id").orEmpty()
            params?.containsKey("id") == true -> params.getString("id").orEmpty()
            else -> ""
        }

    private fun handlePlaySongAtIndex(params: Bundle?) {
        val index =
            when {
                params?.containsKey("index") == true -> params.getInt("index", -1)
                params?.containsKey("playIndex") == true -> params.getInt("playIndex", -1)
                params?.containsKey("musicIndex") == true -> params.getInt("musicIndex", -1)
                else -> -1
            }
        val midList = params?.getStringArrayList("midList")
        val songIdList = params?.getStringArrayList("songIdList")
        val targetIdentifiers = midList ?: songIdList

        runOnMain {
            if (index >= 0) {
                val currentPlaylist = PlaybackManager.playlist.value
                val favSongs = UserLibraryCacheManager.favoriteSongsFlow.value
                val localSongs = LocalMusicManager.getScannedSongs()
                val downloadSongs = DownloadManager.completedTasks.value.map { it.song }

                fun findSongByIdentifier(id: String): Song? =
                    currentPlaylist.find { QQMusicDataConverter.resolveSongId(it) == id || it.songMid == id || it.songId.toString() == id }
                        ?: favSongs.find { QQMusicDataConverter.resolveSongId(it) == id || it.songMid == id || it.songId.toString() == id }
                        ?: localSongs.find { QQMusicDataConverter.resolveSongId(it) == id || it.songMid == id || it.songId.toString() == id }
                        ?: downloadSongs.find { QQMusicDataConverter.resolveSongId(it) == id || it.songMid == id || it.songId.toString() == id }
                        ?: lastSearchResults.find { QQMusicDataConverter.resolveSongId(it) == id || it.songMid == id || it.songId.toString() == id }

                fun isSameSong(a: Song?, b: Song?): Boolean {
                    if (a == null || b == null) return false
                    return a.songMid == b.songMid ||
                        QQMusicDataConverter.resolveSongId(a) == QQMusicDataConverter.resolveSongId(b)
                }

                if (!targetIdentifiers.isNullOrEmpty()) {
                    val matchedList = targetIdentifiers.mapNotNull { findSongByIdentifier(it) }
                    if (matchedList.isNotEmpty()) {
                        val safeIndex = index.coerceIn(0, matchedList.size - 1)
                        val currSong = PlaybackManager.currentSong.value
                        val isPlayingCurrent = isSameSong(matchedList[safeIndex], currSong)
                        val targetIndex =
                            if (isPlayingCurrent && matchedList.size > 1) {
                                (safeIndex + 1) % matchedList.size
                            } else {
                                safeIndex
                            }
                        PlaybackManager.setPlaylist(matchedList, startIndex = targetIndex)
                        return@runOnMain
                    }
                }

                if (index < currentPlaylist.size) {
                    val currSong = PlaybackManager.currentSong.value
                    val isPlayingCurrent = isSameSong(currentPlaylist[index], currSong)
                    val targetIndex =
                        if (isPlayingCurrent && currentPlaylist.size > 1) {
                            (index + 1) % currentPlaylist.size
                        } else {
                            index
                        }
                    PlaybackManager.playSong(currentPlaylist[targetIndex])
                }
            }
        }
    }

    private fun handleFavoriteChange(
        params: Bundle?,
        isAdd: Boolean,
    ) {
        val midList = params?.getStringArrayList("midList")
        val singleMid =
            when {
                params?.containsKey("mid") == true -> params.getString("mid")
                params?.containsKey("songMid") == true -> params.getString("songMid")
                else -> null
            }

        runOnMain {
            val currSong = PlaybackManager.currentSong.value
            val targetSong =
                when {
                    !midList.isNullOrEmpty() -> {
                        val mid = midList.first()
                        if (currSong != null && (currSong.songMid == mid || QQMusicDataConverter.resolveSongId(currSong) == mid)) {
                            currSong
                        } else {
                            PlaybackManager.playlist.value.find { it.songMid == mid || QQMusicDataConverter.resolveSongId(it) == mid }
                                ?: currSong
                        }
                    }

                    !singleMid.isNullOrBlank() -> {
                        if (currSong != null && (currSong.songMid == singleMid || QQMusicDataConverter.resolveSongId(currSong) == singleMid)) {
                            currSong
                        } else {
                            PlaybackManager.playlist.value.find { it.songMid == singleMid || QQMusicDataConverter.resolveSongId(it) == singleMid }
                                ?: currSong
                        }
                    }

                    else -> {
                        currSong
                    }
                }

            if (targetSong != null) {
                val isCurrentFav = PlaybackManager.isSongFavorite(targetSong.songMid)
                Log.i(TAG, "handleFavoriteChange: isAdd=$isAdd, song=${targetSong.name}, currentFav=$isCurrentFav")
                if (isAdd != isCurrentFav) {
                    PlaybackManager.toggleSongFavorite(targetSong)
                    eventNotifier.notifyFavoriteStateChanged(targetSong, isAdd)
                }
            } else {
                Log.w(TAG, "handleFavoriteChange: targetSong is null, midList=$midList, singleMid=$singleMid")
            }
        }
    }

    private fun handleGetFolderList(params: Bundle?): Bundle {
        val folderType = params?.getInt("folderType", 0) ?: 0
        val favSongs = UserLibraryCacheManager.favoriteSongsFlow.value
        val localSongs =
            (DownloadManager.completedTasks.value.map { it.song } + LocalMusicManager.getScannedSongs())
                .distinctBy { it.songMid.ifBlank { it.songId.toString() } }
        val dailySongs = DailyRecommendCacheManager.recommendFlow.value.songs

        val array = JSONArray()
        when (folderType) {
            0 -> {
                array.put(
                    QQMusicDataConverter.buildFolderJson(
                        id = "fav_folder_101",
                        title = "我喜欢",
                        subTitle = "${favSongs.size}首",
                        type = 1,
                        picUrl = favSongs.firstOrNull()?.let { QQMusicDataConverter.resolveCoverUri(context, it) } ?: "",
                        isSongFolder = true,
                    ),
                )
                array.put(
                    QQMusicDataConverter.buildFolderJson(
                        id = "local_100",
                        title = "本地音乐",
                        subTitle = "${localSongs.size}首",
                        type = 100,
                        picUrl = localSongs.firstOrNull()?.let { QQMusicDataConverter.resolveCoverUri(context, it) } ?: "",
                        isSongFolder = true,
                    ),
                )
                array.put(
                    QQMusicDataConverter.buildFolderJson(
                        id = "recommend_103",
                        title = "每日推荐",
                        subTitle = "${if (dailySongs.isNotEmpty()) dailySongs.size else 30}首",
                        type = 3,
                        picUrl = dailySongs.firstOrNull()?.let { QQMusicDataConverter.resolveCoverUri(context, it) } ?: "",
                        isSongFolder = true,
                    ),
                )
            }

            1 -> {
                array.put(
                    QQMusicDataConverter.buildFolderJson(
                        id = "fav_folder_101",
                        title = "我喜欢",
                        subTitle = "${favSongs.size}首",
                        type = 1,
                        picUrl = favSongs.firstOrNull()?.let { QQMusicDataConverter.resolveCoverUri(context, it) } ?: "",
                        isSongFolder = true,
                    ),
                )
            }

            3 -> {
                array.put(
                    QQMusicDataConverter.buildFolderJson(
                        id = "recommend_103",
                        title = "每日推荐",
                        subTitle = "${if (dailySongs.isNotEmpty()) dailySongs.size else 30}首",
                        type = 3,
                        picUrl = dailySongs.firstOrNull()?.let { QQMusicDataConverter.resolveCoverUri(context, it) } ?: "",
                        isSongFolder = true,
                    ),
                )
            }

            100 -> {
                array.put(
                    QQMusicDataConverter.buildFolderJson(
                        id = "local_100",
                        title = "本地音乐",
                        subTitle = "${localSongs.size}首",
                        type = 100,
                        picUrl = localSongs.firstOrNull()?.let { QQMusicDataConverter.resolveCoverUri(context, it) } ?: "",
                        isSongFolder = true,
                    ),
                )
            }
        }

        return Bundle().apply {
            putInt("code", 0)
            putString("data", array.toString())
            putBoolean("hasMore", false)
        }
    }

    private fun handleGetSongList(params: Bundle?): Bundle {
        val folderType = params?.getInt("folderType", -1) ?: -1
        val folderId = params?.getString("folderId") ?: ""
        val songs: List<Song> =
            when {
                folderType == 1 || folderType == 101 || folderType == 201 ||
                    folderId == "fav_folder_101" || folderId == "1" -> {
                    UserLibraryCacheManager.favoriteSongsFlow.value
                }

                folderType == 100 || folderId == "100" || folderId == "local_100" -> {
                    val downloaded = DownloadManager.completedTasks.value.map { it.song }
                    val scanned = LocalMusicManager.getScannedSongs()
                    (downloaded + scanned).distinctBy { it.songMid.ifBlank { it.songId.toString() } }
                }

                folderType == 3 || folderType == 103 || folderId == "recommend_103" || folderId == "3" -> {
                    var dailySongs = DailyRecommendCacheManager.recommendFlow.value.songs
                    if (dailySongs.isEmpty()) {
                        try {
                            runBlocking {
                                withTimeoutOrNull(2500L) {
                                    DailyRecommendCacheManager.loadRecommendSongs(MusicApiService()).songs
                                }
                            }?.let {
                                dailySongs = it
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load daily recommend songs synchronously", e)
                        }
                    }
                    if (dailySongs.isNotEmpty()) {
                        dailySongs
                    } else {
                        UserLibraryCacheManager.favoriteSongsFlow.value
                    }
                }

                else -> {
                    PlaybackManager.playlist.value
                }
            }
        val array = JSONArray()
        for (song in songs) {
            array.put(JSONObject(QQMusicDataConverter.buildSongJson(context, song)))
        }
        return Bundle().apply {
            putInt("code", 0)
            putString("data", array.toString())
            putBoolean("hasMore", false)
        }
    }

    private fun handleVoicePlay(params: Bundle?) {
        val query =
            when {
                params?.containsKey("query") == true -> params.getString("query").orEmpty()
                params?.containsKey("keyword") == true -> params.getString("keyword").orEmpty()
                params?.containsKey("songName") == true -> params.getString("songName").orEmpty()
                params?.containsKey("song") == true -> params.getString("song").orEmpty()
                params?.containsKey("searchKey") == true -> params.getString("searchKey").orEmpty()
                params?.containsKey("key") == true -> params.getString("key").orEmpty()
                params?.containsKey("intent") == true -> params.getString("intent").orEmpty()
                else -> ""
            }.trim()

        Log.i(TAG, "handleVoicePlay query='$query'")
        if (query.isBlank()) {
            runOnMain { PlaybackManager.play() }
            return
        }

        val localSong =
            PlaybackManager.playlist.value.find { it.name.contains(query, ignoreCase = true) || it.singer.contains(query, ignoreCase = true) }
                ?: UserLibraryCacheManager.favoriteSongsFlow.value.find {
                    it.name.contains(query, ignoreCase = true) ||
                        it.singer.contains(query, ignoreCase = true)
                }
                ?: LocalMusicManager.getScannedSongs().find { it.name.contains(query, ignoreCase = true) || it.singer.contains(query, ignoreCase = true) }

        if (localSong != null) {
            runOnMain {
                PlaybackManager.playSong(localSong)
            }
            return
        }

        scope.launch {
            try {
                val results = MusicApiService().search(query, page = 1, pageSize = 20)
                if (results.isNotEmpty()) {
                    runOnMain {
                        PlaybackManager.setPlaylist(results, startIndex = 0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleVoicePlay search failed for query=$query", e)
            }
        }
    }

    private fun handleSetPlayMode(params: Bundle?): Bundle {
        val rawMode = extractPlayModeFromParams(params)
        val targetLoopMode = QQMusicDataConverter.convertQQModeToLoopMode(rawMode)
        runOnMain {
            if (targetLoopMode != null) {
                PlaybackManager.setLoopMode(targetLoopMode)
            } else {
                PlaybackManager.cycleLoopMode()
            }
        }
        val currentQQMode =
            QQMusicDataConverter.convertLoopModeToQQMode(
                targetLoopMode ?: PlaybackManager.loopMode.value,
            )
        return Bundle().apply {
            putInt("code", 0)
            putInt("data", currentQQMode)
            putInt("playMode", currentQQMode)
        }
    }

    private fun extractPlayModeFromParams(params: Bundle?): Int {
        if (params == null) return -1
        val keys = listOf("playMode", "mode", "data", "loopMode")
        for (key in keys) {
            if (!params.containsKey(key)) continue
            val intVal = params.getInt(key, -1)
            if (intVal in 0..2) return intVal
            val strVal = params.getString(key)
            val parsed = strVal?.toIntOrNull()
            if (parsed != null && parsed in 0..2) return parsed
        }
        return -1
    }

    private fun handleSearchAsync(
        params: Bundle?,
        callback: IQQMusicApiCallback,
    ) {
        val query = extractSearchQuery(params)
        scope.launch {
            try {
                val songs = performSongSearch(query)
                val result = buildSearchResultBundle(songs)
                callback.onReturn(result)
            } catch (e: Exception) {
                Log.e(TAG, "Search async failed for query=$query", e)
                val result =
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", "[]")
                        putInt("count", 0)
                        putBoolean("hasMore", false)
                    }
                try {
                    callback.onReturn(result)
                } catch (ce: Exception) {
                    Log.e(TAG, "Callback invocation failed", ce)
                }
            }
        }
    }

    private fun handleSearchSync(params: Bundle?): Bundle {
        val query = extractSearchQuery(params)
        val songs =
            try {
                runBlocking {
                    performSongSearch(query)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search sync failed for query=$query", e)
                emptyList()
            }
        return buildSearchResultBundle(songs)
    }

    private suspend fun performSongSearch(query: String): List<Song> {
        if (query.isBlank()) {
            val fallback =
                PlaybackManager.playlist.value.ifEmpty {
                    UserLibraryCacheManager.favoriteSongsFlow.value
                }
            lastSearchResults = fallback
            return fallback
        }

        val localMatches =
            (
                PlaybackManager.playlist.value +
                    UserLibraryCacheManager.favoriteSongsFlow.value +
                    LocalMusicManager.getScannedSongs()
            ).filter { it.name.contains(query, ignoreCase = true) || it.singer.contains(query, ignoreCase = true) }
                .distinctBy { it.songMid.ifBlank { it.songId.toString() } }

        val onlineResults =
            try {
                withTimeoutOrNull(3500L) {
                    MusicApiService().search(query, page = 1, pageSize = 30)
                }.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Online search failed for query=$query", e)
                emptyList()
            }

        val combined =
            if (onlineResults.isNotEmpty()) {
                (onlineResults + localMatches)
                    .distinctBy { it.songMid.ifBlank { it.songId.toString() } }
                    .take(30)
            } else {
                localMatches.take(30)
            }
        lastSearchResults = combined
        return combined
    }

    private fun extractSearchQuery(params: Bundle?): String {
        if (params == null) return ""
        val keys = listOf("keyword", "search_key", "searchKey", "query", "songName", "song", "key", "content")
        for (key in keys) {
            val value = params.getString(key)
            if (!value.isNullOrBlank()) {
                return value.trim()
            }
        }
        return ""
    }

    private fun buildSearchResultBundle(songs: List<Song>): Bundle {
        val array = JSONArray()
        for (song in songs) {
            array.put(JSONObject(QQMusicDataConverter.buildSongJson(context, song)))
        }
        return Bundle().apply {
            putInt("code", 0)
            putString("data", array.toString())
            putInt("count", array.length())
            putBoolean("hasMore", false)
        }
    }

    companion object {
        private const val TAG = "MelodistQQMusicDispatcher"
    }
}
