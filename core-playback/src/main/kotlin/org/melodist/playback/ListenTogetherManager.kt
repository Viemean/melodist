package org.melodist.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.ApiLogger
import org.melodist.api.MusicApiService
import org.melodist.api.createListenTogetherRoom
import org.melodist.api.getListenTogetherSongList
import org.melodist.api.getSongBySongId
import org.melodist.api.getSongsBySongIds
import org.melodist.api.heartbeatListenTogetherRoom
import org.melodist.api.joinListenTogetherRoom
import org.melodist.api.quitListenTogetherRoom
import org.melodist.api.sendListenTogetherSongOper
import org.melodist.model.ListenTogetherRoomInfo
import org.melodist.model.Song
import kotlin.math.abs

/**
 * 一起听（LightUGCRoom）房间会话与跨端播放状态同步管理器。
 */
object ListenTogetherManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val apiService = MusicApiService()

    private val _currentRoom = MutableStateFlow<ListenTogetherRoomInfo?>(null)
    val currentRoom: StateFlow<ListenTogetherRoomInfo?> = _currentRoom.asStateFlow()

    private val _isJoining = MutableStateFlow(false)
    val isJoining: StateFlow<Boolean> = _isJoining.asStateFlow()

    private var heartbeatJob: Job? = null
    private var lastObservedSongId: Long = 0L

    @Volatile
    private var isSyncingFromRemote: Boolean = false

    private var lastLocalActionTime: Long = 0L
    private var expectedPlayStatus: Int = -1
    private var expectedSongId: Long = 0L
    private var isWaitingForGuestAutoPlay: Boolean = false
    private var lastSyncedPlaylistSongIds: List<Long> = emptyList()
    private var lastObservedMediaVersion: Long = 0L

    init {
        scope.launch {
            PlaybackManager.playlist.collect { newPlaylist ->
                val room = _currentRoom.value ?: return@collect
                if (isSyncingFromRemote) return@collect
                if (newPlaylist.isEmpty()) return@collect

                val newSongIds = newPlaylist.map { it.songId }
                if (newSongIds == lastSyncedPlaylistSongIds) {
                    return@collect
                }
                lastSyncedPlaylistSongIds = newSongIds

                val targetSong = PlaybackManager.currentSong.value ?: newPlaylist.firstOrNull() ?: return@collect
                val targetIdx = newPlaylist.indexOfFirst { it.songId == targetSong.songId }.coerceAtLeast(0)
                ApiLogger.i("ListenTogetherManager", "Detected local playlist change (size=${newPlaylist.size}), syncing via cmd=1")
                handleLocalAction(
                    cmd = 1,
                    songId = targetSong.songId,
                    songTitle = targetSong.name,
                    songIndex = targetIdx,
                    offsetMs = 0L,
                    playlist = newPlaylist,
                )
            }
        }

        PlaybackManager.addPlaybackEventListener(
            object : PlaybackEventListener {
                override fun onPlay() {
                    handleLocalAction(cmd = 2)
                }

                override fun onPause() {
                    handleLocalAction(cmd = 3)
                }

                override fun onSeekTo(positionMs: Long) {
                    handleLocalAction(cmd = 7, offsetMs = positionMs)
                }

                override fun onSongPlay(song: Song) {
                    val room = _currentRoom.value ?: return
                    if (song.songId == lastObservedSongId) {
                        return
                    }
                    lastObservedSongId = song.songId

                    val currentPlaylist = PlaybackManager.playlist.value
                    val currentSongIds = currentPlaylist.map { it.songId }
                    if (currentSongIds != lastSyncedPlaylistSongIds) {
                        lastSyncedPlaylistSongIds = currentSongIds
                        val targetIdx = currentPlaylist.indexOfFirst { it.songId == song.songId }.coerceAtLeast(0)
                        handleLocalAction(
                            cmd = 1,
                            songId = song.songId,
                            songTitle = song.name,
                            songIndex = targetIdx,
                            offsetMs = 0L,
                            playlist = currentPlaylist,
                        )
                    } else {
                        val targetIdx = currentPlaylist.indexOfFirst { it.songId == song.songId }
                        if (targetIdx >= 0) {
                            handleLocalAction(
                                cmd = 4,
                                songId = song.songId,
                                songTitle = song.name,
                                songIndex = targetIdx,
                                offsetMs = 0L,
                            )
                        }
                    }
                }
            },
        )
    }

    /**
     * 将本地用户的主动控制操作同步上报至服务端。
     */
    private fun handleLocalAction(
        cmd: Int,
        songId: Long = 0L,
        songTitle: String = "",
        songIndex: Int = -1,
        offsetMs: Long = -1L,
        playlist: List<Song> = emptyList(),
    ) {
        val room = _currentRoom.value ?: return
        if (isSyncingFromRemote) return

        if (cmd == 2) {
            isWaitingForGuestAutoPlay = false
        }

        val targetSong = PlaybackManager.currentSong.value
        val targetSongId = if (songId > 0L) songId else (targetSong?.songId ?: 0L)
        val targetTitle = songTitle.ifBlank { targetSong?.name.orEmpty() }
        val targetOffset = if (offsetMs >= 0L) offsetMs else PlaybackManager.currentPositionMs.value
        val targetIndex =
            if (songIndex >= 0) {
                songIndex
            } else {
                val list = PlaybackManager.playlist.value
                list.indexOfFirst { it.songId == targetSongId }.coerceAtLeast(0)
            }

        lastLocalActionTime = System.currentTimeMillis()
        ApiLogger.i("ListenTogetherManager", "handleLocalAction cmd=$cmd songId=$targetSongId index=$targetIndex offset=$targetOffset")
        when (cmd) {
            2 -> expectedPlayStatus = 1
            3 -> expectedPlayStatus = 0
            4 -> {
                expectedSongId = targetSongId
                expectedPlayStatus = 1
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (cmd == 2 && targetOffset > 0L) {
                    apiService.sendListenTogetherSongOper(
                        showId = room.showId,
                        cmd = 7,
                        songId = targetSongId,
                        songTitle = targetTitle,
                        songIndex = targetIndex,
                        offsetMs = targetOffset,
                    )
                }
                apiService.sendListenTogetherSongOper(
                    showId = room.showId,
                    cmd = cmd,
                    songId = targetSongId,
                    songTitle = targetTitle,
                    songIndex = targetIndex,
                    offsetMs = targetOffset,
                    playlist = playlist,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ApiLogger.w("ListenTogetherManager", "handleLocalAction send oper error cmd=$cmd", e)
            }
        }
    }

    /**
     * 发起创建一起听房间，创建成功后立即启动后台同步心跳。
     *
     * @param song 当前播放曲目
     * @return 创建结果，包含房间元数据与分享链接
     */
    suspend fun startRoom(song: Song): Result<ListenTogetherRoomInfo> {
        return try {
            val currentPlaylist = PlaybackManager.playlist.value
            val currentOffsetMs = PlaybackManager.currentPositionMs.value
            val currentSongId = song.songId

            withContext(Dispatchers.Main) {
                PlaybackManager.pause()
            }
            isWaitingForGuestAutoPlay = true
            lastLocalActionTime = System.currentTimeMillis()
            expectedPlayStatus = 0
            expectedSongId = currentSongId

            val room =
                apiService.createListenTogetherRoom(
                    song = song,
                    playlist = currentPlaylist,
                    offsetMs = currentOffsetMs,
                    playStatus = 2,
                )
            if (room != null) {
                _currentRoom.value = room
                lastObservedSongId = currentSongId
                lastSyncedPlaylistSongIds = currentPlaylist.map { it.songId }

                val targetIndex = currentPlaylist.indexOfFirst { it.songId == currentSongId }.coerceAtLeast(0)
                scope.launch(Dispatchers.IO) {
                    try {
                        if (currentOffsetMs > 0L) {
                            apiService.sendListenTogetherSongOper(
                                showId = room.showId,
                                cmd = 7,
                                songId = currentSongId,
                                songTitle = song.name,
                                songIndex = targetIndex,
                                offsetMs = currentOffsetMs,
                            )
                        }
                        apiService.sendListenTogetherSongOper(
                            showId = room.showId,
                            cmd = 3,
                            songId = currentSongId,
                            songTitle = song.name,
                            songIndex = targetIndex,
                            offsetMs = currentOffsetMs,
                        )
                    } catch (e: Exception) {
                        ApiLogger.w("ListenTogetherManager", "startRoom send sync oper failed", e)
                    }
                }

                startHeartbeatSync(room.showId)
                Result.success(room)
            } else {
                isWaitingForGuestAutoPlay = false
                expectedPlayStatus = -1
                Result.failure(IllegalStateException("创建一起听房间失败"))
            }
        } catch (e: Exception) {
            isWaitingForGuestAutoPlay = false
            expectedPlayStatus = -1
            if (e is CancellationException) throw e
            ApiLogger.w("ListenTogetherManager", "startRoom failed", e)
            Result.failure(e)
        }
    }

    /**
     * 加入指定的一起听房间，成功后拉取房主播放队列、对齐播放并启动同步心跳。
     *
     * @param showId 房间唯一标识
     * @param encUin 邀请者加密 UIN
     * @param initialSongId 邀请链接携带的初始曲目 ID
     * @return 加入结果
     */
    suspend fun joinRoom(
        showId: String,
        encUin: String = "",
        initialSongId: Long = 0L,
    ): Result<Unit> {
        if (showId.isBlank()) return Result.failure(IllegalArgumentException("房间 ID 不能为空"))
        _isJoining.value = true
        return try {
            val success = apiService.joinListenTogetherRoom(showId, encUin)
            if (success) {
                val room =
                    ListenTogetherRoomInfo(
                        showId = showId,
                        creatorUin = encUin,
                        shareUrl = "https://i.y.qq.com/n2/m/share/listen_together/index.html?showid=$showId&songid=$initialSongId&share_encrypt_uin=$encUin",
                        currentSongId = initialSongId,
                        usersCount = 2,
                        isValid = true,
                    )
                _currentRoom.value = room
                lastObservedSongId = initialSongId

                // 进房后立即拉取房主的完整播放队列 (cmd=0)
                scope.launch(Dispatchers.IO) {
                    try {
                        val state = apiService.heartbeatListenTogetherRoom(showId)
                        val initialOffsetMs = state?.songOffsetMs ?: 0L
                        val isHostPlaying = state?.playStatus == 1

                        val songIds = apiService.getListenTogetherSongList(showId)
                        if (songIds.isNotEmpty()) {
                            val fullSongs = apiService.getSongsBySongIds(songIds)
                            if (fullSongs.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    isSyncingFromRemote = true
                                    try {
                                        val targetIdx = fullSongs.indexOfFirst { it.songId == initialSongId }.coerceAtLeast(0)
                                        PlaybackManager.setPlaylist(
                                            songs = fullSongs,
                                            startIndex = targetIdx,
                                            initialSeekToMs = initialOffsetMs,
                                        )
                                        if (!isHostPlaying) {
                                            PlaybackManager.pause()
                                        }
                                        lastSyncedPlaylistSongIds = fullSongs.map { it.songId }
                                        lastObservedMediaVersion = state?.mediaVersion ?: 0L
                                    } finally {
                                        isSyncingFromRemote = false
                                    }
                                }
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ApiLogger.w("ListenTogetherManager", "Fetch room songList error", e)
                    }

                    // 回退方案：若无法获取全量队列，仅起播当前单曲
                    if (initialSongId > 0L && initialSongId != PlaybackManager.currentSong.value?.songId) {
                        val song = apiService.getSongBySongId(initialSongId)
                        if (song != null) {
                            withContext(Dispatchers.Main) {
                                isSyncingFromRemote = true
                                try {
                                    PlaybackManager.playSong(song)
                                } finally {
                                    isSyncingFromRemote = false
                                }
                            }
                        }
                    }
                }

                startHeartbeatSync(showId)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("加入房间失败，可能房间已关闭"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("ListenTogetherManager", "joinRoom failed", e)
            Result.failure(e)
        } finally {
            _isJoining.value = false
        }
    }

    /**
     * 主动退出当前一起听房间并停止心跳。
     */
    fun leaveRoom() {
        val room = _currentRoom.value ?: return
        isWaitingForGuestAutoPlay = false
        stopHeartbeatSync()
        _currentRoom.value = null
        lastObservedSongId = 0L
        lastLocalActionTime = 0L
        expectedPlayStatus = -1
        expectedSongId = 0L
        lastSyncedPlaylistSongIds = emptyList()
        lastObservedMediaVersion = 0L

        scope.launch(Dispatchers.IO) {
            try {
                apiService.quitListenTogetherRoom(room.showId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ApiLogger.w("ListenTogetherManager", "leaveRoom error", e)
            }
        }
    }

    /**
     * 开启心跳轮询与状态自动对齐任务。
     */
    private fun startHeartbeatSync(showId: String) {
        stopHeartbeatSync()
        heartbeatJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val state = apiService.heartbeatListenTogetherRoom(showId)
                        if (state == null) {
                            delay(1500)
                            continue
                        }

                        ApiLogger.i(
                            "ListenTogetherManager",
                            "HB tick: valid=${state.isRoomValid} users=${state.usersCount} songId=${state.currentSongId} status=${state.playStatus} offset=${state.songOffsetMs} waiting=$isWaitingForGuestAutoPlay",
                        )

                        // 房间已解散或关闭
                        if (!state.isRoomValid) {
                            ApiLogger.i("ListenTogetherManager", "Room $showId has been closed by host/server")
                            _currentRoom.value = null
                            break
                        }

                        // 更新房间成员数
                        _currentRoom.value?.let { current ->
                            val oldUsersCount = current.usersCount
                            if (current.usersCount != state.usersCount) {
                                _currentRoom.value = current.copy(usersCount = state.usersCount)
                            }
                            if (isWaitingForGuestAutoPlay && oldUsersCount < 2 && state.usersCount >= 2) {
                                ApiLogger.i("ListenTogetherManager", "Guest joined, auto-resuming playback (users=${state.usersCount})")
                                isWaitingForGuestAutoPlay = false
                                lastLocalActionTime = System.currentTimeMillis()
                                expectedPlayStatus = 1
                                withContext(Dispatchers.Main) {
                                    PlaybackManager.play()
                                }
                            }
                        }

                        val now = System.currentTimeMillis()
                        val isWithinActionWindow = (now - lastLocalActionTime) < 3000L

                        // 对齐切歌与全量歌单
                        if (state.currentSongId > 0L) {
                            val isMediaVersionChanged = state.mediaVersion > 0L && lastObservedMediaVersion > 0L && state.mediaVersion != lastObservedMediaVersion
                            val isSongChanged = state.currentSongId != lastObservedSongId

                            if (isWithinActionWindow && expectedSongId > 0L && state.currentSongId != expectedSongId) {
                                // 本地处于切歌保护窗，服务端尚未生效，忽略旧歌曲
                            } else {
                                if (state.currentSongId == expectedSongId) {
                                    expectedSongId = 0L
                                }
                                if (isSongChanged || isMediaVersionChanged) {
                                    lastObservedSongId = state.currentSongId
                                    if (state.mediaVersion > 0L) {
                                        lastObservedMediaVersion = state.mediaVersion
                                    }

                                    val currentList = PlaybackManager.playlist.value
                                    val existingSong = currentList.firstOrNull { it.songId == state.currentSongId }

                                    if (existingSong != null && !isMediaVersionChanged) {
                                        if (state.currentSongId != PlaybackManager.currentSong.value?.songId) {
                                            withContext(Dispatchers.Main) {
                                                isSyncingFromRemote = true
                                                try {
                                                    PlaybackManager.playSong(existingSong, seekToMs = state.songOffsetMs)
                                                } finally {
                                                    isSyncingFromRemote = false
                                                }
                                            }
                                        }
                                    } else {
                                        // 歌曲不在本地当前播放队列中，或者服务端歌单版本号已变更：拉取服务端最新全量播放队列 (cmd=0)
                                        ApiLogger.i("ListenTogetherManager", "Remote playlist changed or song missing, syncing full playlist from server (songId=${state.currentSongId})")
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val songIds = apiService.getListenTogetherSongList(showId)
                                                if (songIds.isNotEmpty()) {
                                                    val fullSongs = apiService.getSongsBySongIds(songIds)
                                                    if (fullSongs.isNotEmpty()) {
                                                        withContext(Dispatchers.Main) {
                                                            isSyncingFromRemote = true
                                                            try {
                                                                val targetIdx = fullSongs.indexOfFirst { it.songId == state.currentSongId }.coerceAtLeast(0)
                                                                PlaybackManager.setPlaylist(
                                                                    songs = fullSongs,
                                                                    startIndex = targetIdx,
                                                                    initialSeekToMs = state.songOffsetMs,
                                                                )
                                                                if (state.playStatus != 1) {
                                                                    PlaybackManager.pause()
                                                                }
                                                                lastSyncedPlaylistSongIds = fullSongs.map { it.songId }
                                                            } finally {
                                                                isSyncingFromRemote = false
                                                            }
                                                        }
                                                        return@launch
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                ApiLogger.w("ListenTogetherManager", "Fetch remote playlist error", e)
                                            }

                                            // 回退兜底：如果拉取全量歌单失败，仅起播当前单曲
                                            val targetSong = apiService.getSongBySongId(state.currentSongId)
                                            if (targetSong != null) {
                                                withContext(Dispatchers.Main) {
                                                    isSyncingFromRemote = true
                                                    try {
                                                        PlaybackManager.playSong(targetSong, seekToMs = state.songOffsetMs)
                                                    } finally {
                                                        isSyncingFromRemote = false
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 对齐播放/暂停状态
                        if (isWaitingForGuestAutoPlay) {
                            if (PlaybackManager.isPlaying.value) {
                                withContext(Dispatchers.Main) {
                                    isSyncingFromRemote = true
                                    try {
                                        PlaybackManager.pause()
                                    } finally {
                                        isSyncingFromRemote = false
                                    }
                                }
                            }
                        } else if (isWithinActionWindow && expectedPlayStatus != -1) {
                            val matchesExpected = (expectedPlayStatus == 1 && state.playStatus == 1) ||
                                ((expectedPlayStatus == 0 || expectedPlayStatus == 2) && (state.playStatus == 0 || state.playStatus == 2))
                            if (matchesExpected) {
                                expectedPlayStatus = -1
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isSyncingFromRemote = true
                                try {
                                    if (state.playStatus == 1 && !PlaybackManager.isPlaying.value) {
                                        PlaybackManager.play()
                                    } else if ((state.playStatus == 2 || state.playStatus == 0) && PlaybackManager.isPlaying.value) {
                                        PlaybackManager.pause()
                                    }
                                } finally {
                                    isSyncingFromRemote = false
                                }
                            }
                        }

                        // 进度校准：仅当房间内有 2 人及以上、不在等待好友加入状态、不在本地主动控制窗口内，且本地当前播放的正是服务端当前歌曲时，才进行对齐校准
                        if (state.usersCount >= 2 && !isWaitingForGuestAutoPlay && !isWithinActionWindow) {
                            val currentLocalSong = PlaybackManager.currentSong.value
                            if (currentLocalSong != null && currentLocalSong.songId == state.currentSongId) {
                                if (state.playStatus == 1 && state.songOffsetMs > 0L) {
                                    val currentPos = PlaybackManager.currentPositionMs.value
                                    if (abs(currentPos - state.songOffsetMs) > 3000L) {
                                        withContext(Dispatchers.Main) {
                                            isSyncingFromRemote = true
                                            try {
                                                PlaybackManager.seekTo(state.songOffsetMs)
                                            } finally {
                                                isSyncingFromRemote = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        ApiLogger.w("ListenTogetherManager", "Heartbeat tick error", e)
                    }
                    delay(1500)
                }
            }
    }

    /**
     * 停止心跳同步轮询。
     */
    private fun stopHeartbeatSync() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}

