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

    init {
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
                    handleLocalAction(
                        cmd = 1,
                        songId = song.songId,
                        songTitle = song.name,
                        offsetMs = 0L,
                        playlist = PlaybackManager.playlist.value,
                    )
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
        offsetMs: Long = -1L,
        playlist: List<Song> = emptyList(),
    ) {
        val room = _currentRoom.value ?: return
        if (isSyncingFromRemote) return

        val targetSong = PlaybackManager.currentSong.value
        val targetSongId = if (songId > 0L) songId else (targetSong?.songId ?: 0L)
        val targetTitle = songTitle.ifBlank { targetSong?.name.orEmpty() }
        val targetOffset = if (offsetMs >= 0L) offsetMs else PlaybackManager.currentPositionMs.value

        scope.launch(Dispatchers.IO) {
            try {
                apiService.sendListenTogetherSongOper(
                    showId = room.showId,
                    cmd = cmd,
                    songId = targetSongId,
                    songTitle = targetTitle,
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
            val room = apiService.createListenTogetherRoom(song, currentPlaylist)
            if (room != null) {
                _currentRoom.value = room
                lastObservedSongId = song.songId
                startHeartbeatSync(room.showId)
                Result.success(room)
            } else {
                Result.failure(IllegalStateException("创建一起听房间失败"))
            }
        } catch (e: Exception) {
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
                        val songIds = apiService.getListenTogetherSongList(showId)
                        if (songIds.isNotEmpty()) {
                            val fullSongs = apiService.getSongsBySongIds(songIds)
                            if (fullSongs.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    isSyncingFromRemote = true
                                    try {
                                        val targetIdx = fullSongs.indexOfFirst { it.songId == initialSongId }.coerceAtLeast(0)
                                        PlaybackManager.setPlaylist(fullSongs, startIndex = targetIdx)
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
        stopHeartbeatSync()
        _currentRoom.value = null
        lastObservedSongId = 0L

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

                        // 房间已解散或关闭
                        if (!state.isRoomValid) {
                            ApiLogger.i("ListenTogetherManager", "Room $showId has been closed by host/server")
                            _currentRoom.value = null
                            break
                        }

                        // 更新房间成员数
                        _currentRoom.value?.let { current ->
                            if (current.usersCount != state.usersCount) {
                                _currentRoom.value = current.copy(usersCount = state.usersCount)
                            }
                        }

                        // 对齐切歌
                        if (state.currentSongId > 0L && state.currentSongId != lastObservedSongId) {
                            lastObservedSongId = state.currentSongId
                            if (state.currentSongId != PlaybackManager.currentSong.value?.songId) {
                                withContext(Dispatchers.Main) {
                                    isSyncingFromRemote = true
                                    try {
                                        val currentList = PlaybackManager.playlist.value
                                        val existingSong = currentList.firstOrNull { it.songId == state.currentSongId }
                                        if (existingSong != null) {
                                            PlaybackManager.playSong(existingSong)
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                val targetSong = apiService.getSongBySongId(state.currentSongId)
                                                if (targetSong != null) {
                                                    withContext(Dispatchers.Main) {
                                                        PlaybackManager.playSong(targetSong)
                                                    }
                                                }
                                            }
                                        }
                                    } finally {
                                        isSyncingFromRemote = false
                                    }
                                }
                            }
                        }

                        // 对齐播放/暂停状态及进度校准
                        withContext(Dispatchers.Main) {
                            isSyncingFromRemote = true
                            try {
                                if (state.playStatus == 1 && !PlaybackManager.isPlaying.value) {
                                    PlaybackManager.play()
                                } else if ((state.playStatus == 2 || state.playStatus == 0) && PlaybackManager.isPlaying.value) {
                                    PlaybackManager.pause()
                                }

                                // 进度校准：播放中且时间偏差大于 3 秒时纠偏
                                if (state.playStatus == 1 && state.songOffsetMs > 0L) {
                                    val currentPos = PlaybackManager.currentPositionMs.value
                                    if (abs(currentPos - state.songOffsetMs) > 3000L) {
                                        PlaybackManager.seekTo(state.songOffsetMs)
                                    }
                                }
                            } finally {
                                isSyncingFromRemote = false
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

