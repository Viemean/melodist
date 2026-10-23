package org.melodist.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.PlaybackCredentialsManager
import org.melodist.api.PlaybackVipTier
import org.melodist.api.QualityResult
import org.melodist.api.UserSession
import org.melodist.api.getPlayUrl
import org.melodist.api.getSongVisualMid
import org.melodist.data.LyricCacheManager
import org.melodist.data.download.DownloadManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
import org.melodist.model.PlaybackSourceContext
import org.melodist.model.QualityOption
import org.melodist.model.Song
import org.melodist.playback.policy.ListenTogetherTrialPolicy
import org.melodist.playback.policy.PlaybackPolicyCoordinator

enum class PlaybackLoopMode(
    val label: String,
) {
    ListRepeat("列表循环"),
    SingleRepeat("单曲循环"),
    Shuffle("随机播放"),
}

interface PlaybackInterceptor {
    fun onInterceptPlaySong(
        song: Song,
        forceTier: AudioQualityTier?,
        seekToMs: Long,
    ): Boolean = false

    fun onInterceptSwitchTier(tier: AudioQualityTier): Boolean = false

    fun onInterceptTogglePlayPause(): Boolean = false

    fun onInterceptPause(): Boolean = false

    fun onInterceptResume(): Boolean = false

    fun onInterceptSeekTo(positionMs: Long): Boolean = false

    fun onInterceptPlayNext(): Boolean = false

    fun onInterceptPlayPrevious(): Boolean = false

    fun onInterceptCycleLoopMode(): Boolean = false
}

/**
 * 播放器核心事件监听器接口。
 */
interface PlaybackEventListener {
    fun onPlay() {}

    fun onPause() {}

    fun onSeekTo(positionMs: Long) {}

    fun onSongPlay(song: Song) {}
}

object PlaybackManager {
    var playbackInterceptor: PlaybackInterceptor? = null
    private val playbackEventListeners = java.util.concurrent.CopyOnWriteArrayList<PlaybackEventListener>()

    fun addPlaybackEventListener(listener: PlaybackEventListener) {
        playbackEventListeners.add(listener)
    }

    fun removePlaybackEventListener(listener: PlaybackEventListener) {
        playbackEventListeners.remove(listener)
    }

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val apiService = MusicApiService()
    private var appContext: Context? = null

    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val exoPlayer: ExoPlayer? get() = playerPipeline.player
    private var playJob: Job? = null
    private var lyricLoadJob: Job? = null

    private val remoteStateHolder = PlaybackRemoteStateHolder()
    val isRemoteActive: StateFlow<Boolean> = remoteStateHolder.isRemoteActive
    val remoteDeviceName: StateFlow<String?> = remoteStateHolder.remoteDeviceName
    val remotePrevSong: StateFlow<Song?> = remoteStateHolder.remotePrevSong
    val remoteNextSong: StateFlow<Song?> = remoteStateHolder.remoteNextSong

    fun setRemoteActive(
        active: Boolean,
        deviceName: String? = null,
    ) {
        remoteStateHolder.setRemoteActive(active, deviceName)
    }

    fun clearRemotePlayback() {
        stopSilentKeepAlive()
        resetPlaybackSpeed()
        if (!remoteStateHolder.isRemoteActive.value) return
        remoteStateHolder.clearRemotePlayback()
        _isPlaying.value = false
        _currentSong.value = null
        _currentPositionMs.value = 0L
        _bufferedPositionMs.value = 0L
        _fileCacheFraction.value = 0f
        _durationMs.value = 0L
    }

    private val favoriteController =
        PlaybackFavoriteController(
            scope = scope,
            apiService = apiService,
            onStateChanged = { savePlaybackState() },
        )
    val favoriteSongMids: StateFlow<Set<String>> = favoriteController.favoriteSongMids
    val songFavoriteToggledEvent: SharedFlow<Pair<Song, Boolean>> = favoriteController.songFavoriteToggledEvent

    private val queueManager =
        PlaybackQueueManager(
            scope = scope,
            apiService = apiService,
            onStateChanged = { savePlaybackState() },
            onPlaySongRequest = { song, forceTier, seekToMs ->
                playSong(song, forceTier = forceTier, seekToMs = seekToMs, isQueueAdvance = true)
            },
            onStopPlaybackRequest = {
                progressTracker.flushActivePlaybackSession()
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                _currentSong.value = null
                _lyrics.value = emptyList()
                _isPlaying.value = false
            },
        )

    private var pendingTrialSongMid: String? = null
    private var pendingTrialTime: Long = 0L
    private var lastNotifiedTrialSongMid: String? = null

    init {
        scope.launch {
            ListenTogetherManager.isBothVip.collect { bothVip ->
                if (!bothVip) {
                    checkAndNotifyListenTogetherTrial(_currentSong.value)
                }
            }
        }
    }

    fun resetListenTogetherTrialState() {
        pendingTrialSongMid = null
        pendingTrialTime = 0L
        lastNotifiedTrialSongMid = null
    }

    private fun checkListenTogetherTrialClick(song: Song): Boolean {
        val now = System.currentTimeMillis()
        return if (pendingTrialSongMid == song.songMid && (now - pendingTrialTime) < 3000L) {
            pendingTrialSongMid = null
            pendingTrialTime = 0L
            true
        } else {
            pendingTrialSongMid = song.songMid
            pendingTrialTime = now
            notifyUser("VIP音乐需要双方开通后播放，再次点击可播放1分钟试听")
            false
        }
    }

    fun checkAndNotifyListenTogetherTrial(song: Song?) {
        val target = song ?: _currentSong.value ?: return
        if (policyCoordinator.isTrialActive(target)) {
            if (lastNotifiedTrialSongMid != target.songMid) {
                lastNotifiedTrialSongMid = target.songMid
                val isSelfVip = UserSession.profileFlow.value.isVip
                if (isSelfVip) {
                    notifyUser("对方不是vip正在播放试听1分钟")
                } else {
                    notifyUser("您不是vip正在播放试听1分钟")
                }
            }
        }
    }

    val playlist: StateFlow<List<Song>> = queueManager.playlist
    val currentIndex: StateFlow<Int> = queueManager.currentIndex
    val loopMode: StateFlow<PlaybackLoopMode> = queueManager.loopMode
    val isRadioMode: StateFlow<Boolean> = queueManager.isRadioMode
    val queueTag: StateFlow<String?> = queueManager.queueTag
    val sourceContext: StateFlow<PlaybackSourceContext?> = queueManager.sourceContext
    val paginationSource: StateFlow<QueuePaginationSource?> = queueManager.paginationSource
    val isLoadingMoreForQueue: StateFlow<Boolean> = queueManager.isLoadingMoreForQueue

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _preferredTier = MutableStateFlow(AudioQualityTier.HiRes)
    val preferredTier: StateFlow<AudioQualityTier> = _preferredTier.asStateFlow()

    private val _currentTier = MutableStateFlow(AudioQualityTier.Standard)
    val currentTier: StateFlow<AudioQualityTier> = _currentTier.asStateFlow()

    private val _currentTrackSpec = MutableStateFlow<AudioTrackSpec?>(null)
    val currentTrackSpec: StateFlow<AudioTrackSpec?> = _currentTrackSpec.asStateFlow()

    val policyCoordinator by lazy {
        PlaybackPolicyCoordinator(
            scope = scope,
            currentRoomFlow = ListenTogetherManager.currentRoom,
            isBothVipFlow = ListenTogetherManager.isBothVip,
            currentSongFlow = _currentSong,
            policies =
                listOf(
                    ListenTogetherTrialPolicy(
                        isInRoomProvider = { ListenTogetherManager.isInRoom },
                        isBothVipProvider = { ListenTogetherManager.isBothVip.value },
                    ),
                ),
        )
    }

    val isListenTogetherTrialActive: StateFlow<Boolean>
        get() = policyCoordinator.isListenTogetherTrialActive

    fun resolveTierBadgeText(
        song: Song? = null,
        tier: AudioQualityTier = _currentTier.value,
    ): String {
        val s = song ?: _currentSong.value ?: return AudioQualityTier.getBadge(tier)
        return policyCoordinator.resolveTierBadgeText(s, tier)
    }

    val qualityCoordinator by lazy { AudioQualityCoordinator(scope, apiService) }
    val lyricsCoordinator by lazy {
        PlaybackLyricsCoordinator(
            scope = scope,
            apiService = apiService,
            currentSongProvider = { _currentSong.value },
            onLyricsUpdated = { lyrics ->
                scope.launch(Dispatchers.Main) {
                    _lyrics.value = lyrics
                    _currentSong.value?.let { current ->
                        updateCurrentMediaMetadata(current)
                    }
                }
            },
            onOffsetCalibrated = { _currentLyricOffsetMs.value = it },
            onLyricsLoadedBroadcast = { _lyricsLoadedFlow.tryEmit(it) },
        )
    }

    val availableTiers: StateFlow<Set<AudioQualityTier>> get() = qualityCoordinator.availableTiers
    val probedQualityOptions: StateFlow<List<QualityOption>> get() = qualityCoordinator.probedQualityOptions
    val isProbingQuality: StateFlow<Boolean> get() = qualityCoordinator.isProbingQuality

    private val _isSwitchingQuality = MutableStateFlow(false)
    val isSwitchingQuality: StateFlow<Boolean> = _isSwitchingQuality.asStateFlow()

    private var switchQualityJob: Job? = null

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _currentLyricOffsetMs = MutableStateFlow(0L)
    val currentLyricOffsetMs: StateFlow<Long> = _currentLyricOffsetMs.asStateFlow()

    private val _isCurrentTrackFromCache = MutableStateFlow(false)
    val isCurrentTrackFromCache: StateFlow<Boolean> = _isCurrentTrackFromCache.asStateFlow()

    private val _fileCacheFraction = MutableStateFlow(0f)
    val fileCacheFraction: StateFlow<Float> = _fileCacheFraction.asStateFlow()

    private val _lyricsLoadedFlow = MutableSharedFlow<Pair<Song, List<LyricLine>>>(extraBufferCapacity = 8)
    val lyricsLoadedFlow: SharedFlow<Pair<Song, List<LyricLine>>> = _lyricsLoadedFlow.asSharedFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private var currentSongRetryCount = 0
    private var consecutiveErrorCount = 0
    private var audioTrackRetryCount = 0
    private var lastCustomStreamArgs: Triple<Song, String, Map<String, String>>? = null

    private fun handlePlaybackFailure(
        errorMsg: String,
        allowCurrentSongRetry: Boolean = true,
    ) {
        _isLoading.value = false
        _isTransitioning.value = false
        val curr = _currentSong.value

        if (allowCurrentSongRetry && curr != null && currentSongRetryCount < 2) {
            currentSongRetryCount++
            Log.w("MelodistPlayback", "Playback failure for ${curr.name} (retry $currentSongRetryCount/2): $errorMsg")
            _errorMessage.value = "网络加载波动，正在等待重试 ($currentSongRetryCount/2)..."
            _isLoading.value = true
            scope.launch {
                delay(3000L)
                val targetSong = _currentSong.value
                if (targetSong?.songMid == curr.songMid) {
                    val custom = lastCustomStreamArgs
                    if (custom != null && custom.first.songMid == curr.songMid) {
                        playCustomStream(custom.first, custom.second, custom.third, seekToMs = _currentPositionMs.value)
                    } else {
                        playSong(curr, forceTier = _currentTier.value, seekToMs = _currentPositionMs.value)
                    }
                }
            }
            return
        }

        currentSongRetryCount = 0
        audioTrackRetryCount = 0
        consecutiveErrorCount++
        if (consecutiveErrorCount >= 3) {
            Log.w("MelodistPlayback", "Continuous playback failure reached limit (3), stopping playback.")
            _errorMessage.value = "$errorMsg (多次尝试失败已停止)"
            _isPlaying.value = false
            exoPlayer?.stop()
            consecutiveErrorCount = 0
        } else {
            _errorMessage.value = "$errorMsg (即将尝试下一首 $consecutiveErrorCount/3)"
            if (remoteStateHolder.isRemoteActive.value) {
                Log.w("MelodistPlayback", "Remote playback is active, skip local error auto-advance.")
                return
            }
            if (ListenTogetherManager.isInRoom) {
                Log.w("MelodistPlayback", "In listen together room, skip local error auto-advance.")
                return
            }
            scope.launch {
                delay(3000L)
                playNext()
            }
        }
    }

    val isLocalPlaybackActive: Boolean
        get() = exoPlayer?.playWhenReady == true && exoPlayer?.playbackState != androidx.media3.common.Player.STATE_IDLE

    fun shouldHoldForeground(): Boolean {
        if (remoteStateHolder.isRemoteActive.value && _currentSong.value != null) {
            return _isPlaying.value || _isSilentKeepAlive.value
        }
        val player = exoPlayer ?: return false
        return player.playWhenReady || _isPlaying.value || _isLoading.value || _isTransitioning.value
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /**
     * 设置播放器静音状态。
     *
     * @param muted true 为静音，false 为解除静音
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        exoPlayer?.volume = if (muted) 0f else 1f
    }

    private val _isSilentKeepAlive = MutableStateFlow(false)
    val isSilentKeepAlive: StateFlow<Boolean> = _isSilentKeepAlive.asStateFlow()

    fun startSilentKeepAlive() {
        val player = exoPlayer ?: return
        if (_isSilentKeepAlive.value && player.isPlaying) {
            return
        }
        _isSilentKeepAlive.value = true
        player.repeatMode = Player.REPEAT_MODE_ALL
        val silenceSource = SilenceMediaSource(86_400_000_000L)
        player.setMediaSource(silenceSource)
        player.prepare()
        player.play()
    }

    fun pauseSilentKeepAlive() {
        if (!_isSilentKeepAlive.value) return
        exoPlayer?.pause()
    }

    fun stopSilentKeepAlive() {
        if (!_isSilentKeepAlive.value) {
            exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
            return
        }
        _isSilentKeepAlive.value = false
        val player = exoPlayer ?: return
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.stop()
        player.clearMediaItems()
        resetPlaybackSpeed()
    }

    val activeMediaId: String?
        get() = exoPlayer?.currentMediaItem?.mediaId

    val actualAudioPositionMs: Long
        get() = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _currentPositionMs.value

    val actualAudioIsPlaying: Boolean
        get() = exoPlayer?.isPlaying == true || (exoPlayer?.playWhenReady == true && exoPlayer?.playbackState != androidx.media3.common.Player.STATE_ENDED)

    /**
     * 设置播放器回放倍速。
     *
     * @param speed 目标倍速（如 1.0f、1.25f、1.5f）
     */
    fun setPlaybackSpeed(speed: Float) {
        val player = exoPlayer ?: return
        if (Math.abs(player.playbackParameters.speed - speed) > 0.005f) {
            player.setPlaybackSpeed(speed)
        }
    }

    /**
     * 重置播放器回放倍速至标准 1.0x。
     */
    fun resetPlaybackSpeed() {
        setPlaybackSpeed(1.0f)
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun notifyUser(message: String) {
        _errorMessage.value = message
        appContext?.let { ctx ->
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast
                        .makeText(ctx, message, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Failed to dispatch user toast notification", e)
            }
        }
    }

    val playbackSnapshot: StateFlow<PlaybackSnapshot> =
        combine(
            listOf<Flow<Any?>>(
                _currentSong,
                _isPlaying,
                _currentPositionMs,
                _durationMs,
                _bufferedPositionMs,
                queueManager.loopMode,
                _currentTier,
                queueManager.isRadioMode,
                queueManager.playlist,
                queueManager.currentIndex,
                _isLoading,
            ),
        ) { values ->
            val song = values[0] as? Song
            val playing = values[1] as Boolean
            val pos = values[2] as Long
            val dur = values[3] as Long
            val buf = values[4] as Long
            val loop = values[5] as PlaybackLoopMode
            val tier = values[6] as AudioQualityTier
            val radio = values[7] as Boolean

            @Suppress("UNCHECKED_CAST")
            val pl = values[8] as List<Song>
            val idx = values[9] as Int
            val loading = values[10] as Boolean
            PlaybackSnapshot(
                currentSong = song,
                isPlaying = playing,
                currentPositionMs = pos,
                durationMs = dur,
                bufferedPositionMs = buf,
                loopMode = loop,
                currentTier = tier,
                isRadioMode = radio,
                queueSize = pl.size,
                currentIndex = idx,
                isLoading = loading,
            )
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = PlaybackSnapshot(),
        )

    private val usbRouter =
        UsbAudioRouter(
            onDeviceStateChanged = { needReload ->
                val isUsbExclusive = org.melodist.data.AppSettingsManager.settings.value.enableUsbExclusive
                updateUsbExclusiveRouting()
                if (needReload && isUsbExclusive && exoPlayer?.isPlaying == true) {
                    reloadAudioPipeline()
                }
            },
        )
    val activeUsbDeviceName: StateFlow<String?> = usbRouter.activeUsbDeviceName

    private val audioTrackResolver by lazy {
        PlaybackAudioTrackResolver(
            usbRouter = usbRouter,
            getContext = { appContext },
            onReloadAudioPipelineRequest = { reloadAudioPipeline() },
        )
    }

    private val probeHealer by lazy {
        PlaybackProbeHealer(
            scope = scope,
            apiService = apiService,
            qualityCoordinator = qualityCoordinator,
            queueManager = queueManager,
            lyricsCoordinator = lyricsCoordinator,
            getContext = { appContext },
            getCurrentSong = { _currentSong.value },
            setCurrentSong = { _currentSong.value = it },
            getCurrentTier = { _currentTier.value },
            setCurrentTier = { _currentTier.value = it },
            getPreferredTier = { _preferredTier.value },
            getCurrentTrackSpec = { _currentTrackSpec.value },
            setCurrentTrackSpec = { _currentTrackSpec.value = it },
            onSwitchTierRequest = { switchTier(it) },
            onUpdateMediaMetadataRequest = { updateCurrentMediaMetadata(it) },
            onPrefetchAdjacentCoversRequest = { preloadCoordinator.prefetchAdjacentCoversAndMetadata() },
        )
    }

    private val preloadCoordinator by lazy {
        PlaybackPreloadCoordinator(
            scope = scope,
            apiService = apiService,
            queueManager = queueManager,
            lyricsCoordinator = lyricsCoordinator,
            getContext = { appContext },
            getCurrentSong = { _currentSong.value },
            getPreferredTier = { _preferredTier.value },
            clampCellularTier = { tier, song -> clampCellularTier(tier, song) },
            getPreviousSong = { getPreviousSong() },
            getNextSong = { getNextSong() },
        )
    }

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // 音质切换期间，忽略 ExoPlayer 内部重新加载媒体源引起的短暂 isPlaying=false 状态
                if (_isSwitchingQuality.value && !playing && exoPlayer?.playWhenReady == true) {
                    return
                }
                _isPlaying.value = playing
                if (playing) {
                    currentSongRetryCount = 0
                    consecutiveErrorCount = 0
                    audioTrackRetryCount = 0
                } else {
                    progressTracker.flushActivePlaybackSession()
                    savePlaybackProgress(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                try {
                    val current = _currentSong.value ?: return
                    val enriched = PlaybackMetadataCoordinator.enrichFromExoMetadata(current, mediaMetadata) ?: return

                    Log.i("MelodistPlayback", "Enriched metadata from ExoPlayer: ${enriched.name} - ${enriched.singer} (${enriched.album})")
                    _currentSong.value = enriched
                    queueManager.updateSongInPlaylist(enriched)
                    updateCurrentMediaMetadata(enriched)
                    org.melodist.data.RecentPlaybackManager
                        .recordSong(enriched)
                    savePlaybackState()

                    if (enriched.name != current.name || enriched.singer != current.singer) {
                        lyricsCoordinator.loadLyricsForSong(enriched)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("MelodistPlayback", "Failed to apply enriched metadata from ExoPlayer", e)
                }
            }

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int,
            ) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
                    if (queueManager.loopMode.value != PlaybackLoopMode.SingleRepeat) {
                        handleSongEnded()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        _durationMs.value = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                        if (_lyrics.value.isNotEmpty()) {
                            _currentSong.value?.let { current ->
                                legacyMetadataSyncCallback?.invoke(current, _lyrics.value)
                            }
                        }
                        if (exoPlayer?.isPlaying == true) {
                            currentSongRetryCount = 0
                            consecutiveErrorCount = 0
                            audioTrackRetryCount = 0
                        }
                        if (_isSwitchingQuality.value) {
                            _isSwitchingQuality.value = false
                            _isPlaying.value = exoPlayer?.isPlaying == true
                        }
                        if (remoteStateHolder.isRemoteActive.value && !_isSilentKeepAlive.value) {
                            val estimatedTvPos = remoteStateHolder.getEstimatedPositionMs(_durationMs.value)
                            val playerPos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                            if (estimatedTvPos != null && estimatedTvPos > 250L && Math.abs(estimatedTvPos - playerPos) > 250L) {
                                exoPlayer?.seekTo(estimatedTvPos)
                            }
                        }
                    }

                    Player.STATE_ENDED -> {
                        _isSwitchingQuality.value = false
                        handleSongEnded()
                    }

                    Player.STATE_BUFFERING -> {
                        if (!_isSwitchingQuality.value) {
                            _isLoading.value = true
                        }
                    }

                    Player.STATE_IDLE -> {
                        _isSwitchingQuality.value = false
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isLoading.value = false
                Log.e("MelodistPlayback", "ExoPlayer playback error: ${error.errorCodeName}, cause: ${error.cause?.message}", error)
                val current = _currentSong.value
                val curTier = _currentTier.value
                val fallback = getFallbackTier(curTier)

                val isAudioTrackError =
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                        error.cause is AudioSink.InitializationException ||
                        error.cause is AudioSink.WriteException

                if (isAudioTrackError && audioTrackRetryCount < 1) {
                    audioTrackRetryCount++
                    Log.w("MelodistPlayback", "AudioTrack/AudioSink error encountered (${error.errorCodeName}). Resetting audio pipeline and retrying...")
                    _errorMessage.value = "音频驱动重置中，正在重试播放..."
                    scope.launch {
                        delay(300L)
                        resetPlayerPipeline(restoreMediaItem = false)
                        if (current != null) {
                            val custom = lastCustomStreamArgs
                            if (custom != null && custom.first.songMid == current.songMid) {
                                playCustomStream(custom.first, custom.second, custom.third, seekToMs = _currentPositionMs.value)
                            } else {
                                playSong(current, forceTier = curTier, seekToMs = _currentPositionMs.value)
                            }
                        }
                    }
                    return
                }

                val isDummyUriFailure =
                    current != null &&
                        (
                            exoPlayer
                                ?.currentMediaItem
                                ?.localConfiguration
                                ?.uri
                                ?.host == "cache.melodist.internal"
                        )
                if (isDummyUriFailure) {
                    Log.w("MelodistPlayback", "Local cache playback failed for ${current.name}, evicting cache and falling back to network")
                    MelodistCacheManager.evictIncompleteCacheAsync(current.songMid, curTier)
                    scope.launch {
                        delay(200L)
                        playSong(current, forceTier = curTier, seekToMs = _currentPositionMs.value)
                    }
                    return
                }

                if (current != null && fallback != null && !isLocalOrWebDavSong(current)) {
                    Log.w("MelodistPlayback", "Playback failed at tier $curTier, falling back to $fallback")
                    qualityCoordinator.recordTierFailureForSong(current.songMid, curTier)
                    _errorMessage.value = "当前音质播放失败，已自动降级为 ${AudioQualityTier.getBadge(fallback)}"
                    scope.launch {
                        delay(500L)
                        switchTier(fallback, isAutoFallback = true)
                    }
                } else {
                    handlePlaybackFailure("播放失败: ${error.errorCodeName}")
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val resolved =
                    audioTrackResolver.resolveSelectedAudioTrack(
                        tracks = tracks,
                        currentSong = _currentSong.value,
                        currentTier = _currentTier.value,
                        probedOptions = probedQualityOptions.value,
                    ) ?: return

                _currentTrackSpec.value = resolved.trackSpec
                if (resolved.shouldApplyTierToSong) {
                    val currentSong = _currentSong.value
                    Log.i(
                        "MelodistPlayback",
                        "onTracksChanged auto-detected tier: ${resolved.detectedTier}",
                    )
                    _currentTier.value = resolved.detectedTier
                    _currentSong.value = currentSong?.copy(currentTier = resolved.detectedTier)
                }
            }
        }

    private val playerPipeline: PlaybackExoPlayerPipeline =
        PlaybackExoPlayerPipeline(
            getContext = { appContext },
            usbRouter = usbRouter,
            playerListener = playerListener,
            onPlayerCreated = {
                progressTracker.start()
            },
        )

    private val analyticsReporter =
        PlaybackAnalyticsReporter(
            scope = scope,
            apiService = apiService,
            getContext = { appContext },
            getSourceContext = { queueManager.sourceContext.value },
            getCurrentSong = { _currentSong.value },
        )

    private val progressTracker: PlaybackProgressTracker =
        PlaybackProgressTracker(
            scope = scope,
            getPlayer = { exoPlayer },
            isRemoteActive = { remoteStateHolder.isRemoteActive.value },
            getEstimatedRemotePositionMs = { dur -> remoteStateHolder.getEstimatedPositionMs(dur) },
            getCurrentSong = { _currentSong.value },
            getCurrentTier = { _currentTier.value },
            isCurrentTrackFromCache = { _isCurrentTrackFromCache.value },
            getDurationMs = { _durationMs.value },
            isSongFavorite = { mid -> isSongFavorite(mid) },
            onPositionUpdated = { pos -> _currentPositionMs.value = pos },
            onDurationUpdated = { dur -> _durationMs.value = dur },
            onBufferedPositionUpdated = { buf -> _bufferedPositionMs.value = maxOf(_bufferedPositionMs.value, buf) },
            onFileCacheFractionUpdated = { frac -> _fileCacheFraction.value = frac },
            onTrackFromCacheConfirmed = { _isCurrentTrackFromCache.value = true },
            onSavePlaybackProgressRequest = { pos -> savePlaybackProgress(pos) },
            onTriggerPrefetchNextSongRequest = { preloadCoordinator.triggerPrefetchNextSong() },
            onSongActivePlaybackQualified = { song -> analyticsReporter.onSongActivePlaybackQualified(song) },
            onContextActivePlaybackQualified = { analyticsReporter.onContextActivePlaybackQualified() },
            onSongPlaybackSessionFinished = { song, playedMs -> analyticsReporter.onSongPlaybackSessionFinished(song, playedMs) },
        )

    private fun buildExoPlayer(context: Context): ExoPlayer = playerPipeline.buildExoPlayer(context)

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        init(context)
        return playerPipeline.getOrCreatePlayer(context)
    }

    fun buildMediaMetadata(
        song: Song,
        remoteDeviceName: String? = null,
        lyrics: List<LyricLine> = _lyrics.value,
    ): MediaMetadata = PlaybackMediaItemFactory.buildMediaMetadata(song, remoteDeviceName, lyrics)

    private fun buildMediaItem(
        uri: android.net.Uri,
        song: Song,
        tier: AudioQualityTier? = null,
        lyrics: List<LyricLine> = _lyrics.value,
    ): MediaItem = PlaybackMediaItemFactory.buildMediaItem(uri, song, tier, lyrics)

    fun buildMediaItemForSong(
        song: Song,
        remoteDeviceName: String? = null,
        lyrics: List<LyricLine> = _lyrics.value,
    ): MediaItem = PlaybackMediaItemFactory.buildMediaItemForSong(song, remoteDeviceName, lyrics)

    var legacyMetadataSyncCallback: ((song: Song, lyrics: List<LyricLine>) -> Unit)? = null

    fun updateCurrentMediaMetadata(song: Song) {
        val player = exoPlayer ?: return
        val currentItem = player.currentMediaItem
        if (currentItem != null && currentItem.mediaId == song.songMid && player.currentMediaItemIndex >= 0) {
            val updatedItem = currentItem.buildUpon().setMediaMetadata(buildMediaMetadata(song, lyrics = _lyrics.value)).build()
            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
        legacyMetadataSyncCallback?.invoke(song, _lyrics.value)
    }

    /**
     * 启动后台播放服务。
     *
     * @param context 上下文对象
     * @param foreground 是否以显式前台服务形式启动
     */
    fun startPlaybackService(
        context: Context,
        foreground: Boolean = false,
    ) {
        try {
            val intent = Intent(context.applicationContext, PlaybackService::class.java)
            if (foreground) {
                intent.putExtra(PlaybackService.EXTRA_START_FOREGROUND, true)
                androidx.core.content.ContextCompat
                    .startForegroundService(context.applicationContext, intent)
            } else {
                try {
                    context.applicationContext.startService(intent)
                } catch (_: IllegalStateException) {
                    intent.putExtra(PlaybackService.EXTRA_START_FOREGROUND, true)
                    androidx.core.content.ContextCompat
                        .startForegroundService(context.applicationContext, intent)
                }
            }
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to start PlaybackService: $e")
        }
    }

    /**
     * 初始化播放管理器单例运行环境，绑定缓存配额提供者并恢复历史持久化状态。
     *
     * @param context 应用程序上下文
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            val profile = PlaybackProfile.detect(context)
            MelodistCacheManager.init(context, profile)
            org.melodist.data.AppSettingsManager.mediaCacheSizeProvider = { MelodistCacheManager.getCacheSizeBytes() }
            org.melodist.data.AppSettingsManager.mediaQuotaProvider = { MelodistCacheManager.activeCacheQuotaBytes }
            org.melodist.data.AppSettingsManager.cachedTrackCountProvider = { MelodistCacheManager.getCachedKeyCount() }
            org.melodist.data.AppSettingsManager.mediaCacheClearAction = { MelodistCacheManager.clearAllCache() }
            DownloadManager.cacheExporter = MelodistCacheManager
            LyricCacheManager.init(context)
            LocalLyricAutoMatcher.init(context)
            restorePlaybackState()
            if (UserSession.isLoggedIn) {
                syncFavoriteSongsAsync()
            }
        }
        usbRouter.register(context)
        org.melodist.data.AppSettingsManager.onUsbExclusiveChangedListener = {
            resetPlayerPipeline()
        }
        if (exoPlayer == null) {
            playerPipeline.buildExoPlayer(context)
            _isPlaying.value = false
            progressTracker.start()
        } else {
            updateUsbExclusiveRouting()
        }
        startPlaybackService(context)
    }

    fun resetPlayerPipeline(restoreMediaItem: Boolean = true) = playerPipeline.resetPlayerPipeline(restoreMediaItem)

    fun reloadAudioPipeline() = playerPipeline.reloadAudioPipeline()

    fun updateUsbExclusiveRouting(
        targetRate: Int = 0,
        channelCount: Int = 0,
        pcmEncoding: Int = 0,
    ) = playerPipeline.updateUsbExclusiveRouting(targetRate, channelCount, pcmEncoding)

    @Suppress("UnusedParameter")
    fun onAudioPassthroughChanged(enabled: Boolean) {
        // 仅更新配置，在下一次起播时生效
    }


    private fun startProgressLoop() = progressTracker.start()

    /**
     * 将当前播放队列、曲目索引与循环模式持久化保存至本地存储。
     */
    fun savePlaybackState() {
        PlaybackStateStorage.savePlaybackState(
            context = appContext,
            currentSong = _currentSong.value,
            playlist = queueManager.playlist.value,
            favoriteSongMids = favoriteController.favoriteSongMids.value,
            currentIndex = queueManager.currentIndex.value,
            currentPositionMs = _currentPositionMs.value,
            durationMs = _durationMs.value,
            preferredTier = _preferredTier.value,
            loopMode = queueManager.loopMode.value,
            shuffledIndices = queueManager.shuffleQueue.serialize(),
            shuffledPointer = queueManager.shuffleQueue.pointer,
            isRadioMode = queueManager.isRadioMode.value,
        )
    }

    fun savePlaybackProgress(posMs: Long = _currentPositionMs.value) {
        PlaybackStateStorage.savePlaybackProgress(appContext, posMs)
    }

    private fun restorePlaybackState() {
        val restored = PlaybackStateStorage.restorePlaybackState(appContext)
        restored.preferredTier?.let { _preferredTier.value = it }
        favoriteController.restoreFavorites(restored.favoriteSongMids)
        queueManager.restoreState(
            playlist = restored.playlist,
            currentIndex = restored.currentIndex,
            loopMode = restored.loopMode,
            isRadioMode = restored.isRadioMode,
            shuffledIndices = restored.shuffledIndices,
            shuffledPointer = restored.shuffledPointer,
        )
        if (restored.currentSong != null) {
            _currentSong.value = restored.currentSong
            _currentTier.value = restored.currentSong.currentTier
            val cached = LyricCacheManager.getLyrics(restored.currentSong.songMid).orEmpty()
            _lyrics.value = cached
            loadLyricsForSong(restored.currentSong)
        }
        _currentPositionMs.value = restored.currentPositionMs
        _durationMs.value = restored.durationMs
    }

    fun setFavoriteSongMids(mids: Set<String>) = favoriteController.setFavoriteSongMids(mids)

    fun addFavoriteSongMids(mids: Collection<String>) = favoriteController.addFavoriteSongMids(mids)

    fun syncFavoriteSongsAsync(forceRefresh: Boolean = false) = favoriteController.syncFavoriteSongsAsync(forceRefresh)

    fun isSongFavoriteSupported(song: Song?): Boolean = favoriteController.isSongFavoriteSupported(song)

    fun isSongFavorite(songMid: String?): Boolean = favoriteController.isSongFavorite(songMid)

    fun setSongFavoriteState(
        songMid: String,
        isFav: Boolean,
    ) = favoriteController.setSongFavoriteState(songMid, isFav, _currentSong.value)

    fun toggleSongFavorite(song: Song) = favoriteController.toggleSongFavorite(song, appContext)

    fun toggleCurrentSongFavorite() {
        val song = _currentSong.value ?: return
        toggleSongFavorite(song)
    }

    fun setPlaylist(
        songs: List<Song>,
        startIndex: Int = 0,
        isRadio: Boolean = false,
        initialSeekToMs: Long = 0L,
        startMuted: Boolean = false,
        forceTier: AudioQualityTier? = null,
        paginationSource: QueuePaginationSource? = null,
        queueTag: String? = null,
        sourceContext: PlaybackSourceContext? = null,
    ) {
        if (ListenTogetherManager.isInRoom) {
            val targetSong = songs.getOrNull(startIndex) ?: songs.firstOrNull()
            if (targetSong != null) {
                if (isLocalOrWebDavSong(targetSong)) {
                    notifyUser("请退出一起听后再播放哦")
                    return
                }
                if (targetSong.isVip && !ListenTogetherManager.isBothVip.value && !ListenTogetherManager.isSyncingRemote) {
                    if (!checkListenTogetherTrialClick(targetSong)) {
                        return
                    }
                }
            }
        }
        analyticsReporter.resetContextReportedState()
        setMuted(startMuted)
        queueManager.setPlaylist(
            songs = songs,
            startIndex = startIndex,
            isRadio = isRadio,
            initialSeekToMs = initialSeekToMs,
            forceTier = forceTier,
            paginationSource = paginationSource,
            queueTag = queueTag,
            sourceContext = sourceContext,
        )
    }

    fun setPaginationSource(source: QueuePaginationSource?) = queueManager.setPaginationSource(source)

    suspend fun loadMoreForQueue(): Boolean = queueManager.loadMoreForQueue()

    fun appendPlaylist(
        newSongs: List<Song>,
        targetTag: String? = null,
    ) {
        if (ListenTogetherManager.isInRoom) {
            val filtered = newSongs.filterNot { isLocalOrWebDavSong(it) }
            if (filtered.isEmpty() && newSongs.isNotEmpty()) {
                notifyUser("请退出一起听后再播放哦")
                return
            }
            queueManager.appendPlaylist(filtered, targetTag)
            return
        }
        queueManager.appendPlaylist(newSongs, targetTag)
    }

    fun insertNextPlay(song: Song) {
        if (ListenTogetherManager.isInRoom && isLocalOrWebDavSong(song)) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        queueManager.insertNextPlay(song)
    }

    fun insertAndPlay(
        song: Song,
        seekToMs: Long = 0L,
    ) {
        if (ListenTogetherManager.isInRoom) {
            if (isLocalOrWebDavSong(song)) {
                notifyUser("请退出一起听后再播放哦")
                return
            }
            if (song.isVip && !ListenTogetherManager.isBothVip.value && !ListenTogetherManager.isSyncingRemote) {
                if (!checkListenTogetherTrialClick(song)) {
                    return
                }
            }
        }
        queueManager.insertAndPlay(song, seekToMs)
    }

    fun removeFromPlaylist(index: Int) = queueManager.removeFromPlaylist(index)

    fun removeFromPlaylist(songs: List<Song>) = queueManager.removeFromPlaylist(songs, _currentSong.value?.songMid)

    val isSimilarRecommendActive: StateFlow<Boolean> = queueManager.isSimilarRecommendActive
    private val _showQueueSheetFlow = MutableStateFlow(false)
    val showQueueSheetFlow: StateFlow<Boolean> = _showQueueSheetFlow.asStateFlow()

    fun requestOpenQueueSheet() {
        _showQueueSheetFlow.value = true
    }

    fun consumeOpenQueueSheet() {
        _showQueueSheetFlow.value = false
    }

    /**
     * 以指定歌曲为种子激活相似歌曲推荐队列并开始播放。
     *
     * @param anchorSong 作为相似推荐基准的种子歌曲实体
     * @param openQueue 激活后是否触发打开播放队列抽屉面板
     * @return 推荐列表拉取成功并完成激活返回 true，推荐结果为空返回 false
     */
    suspend fun activateSimilarRecommend(
        anchorSong: Song,
        openQueue: Boolean = true,
    ): Boolean {
        val currentPlaying = _currentSong.value
        val result = queueManager.activateSimilarRecommend(anchorSong)
        if (result.isNotEmpty()) {
            if (currentPlaying?.songMid != anchorSong.songMid) {
                playSong(anchorSong)
            }
            if (openQueue) {
                requestOpenQueueSheet()
            }
            return true
        }
        return false
    }

    fun restorePlaylistFromSimilar(): Boolean = queueManager.restorePlaylistFromSimilar()

    fun clearPlaylist() {
        analyticsReporter.resetContextReportedState()
        queueManager.clearPlaylist()
    }

    /**
     * 起播指定歌曲，支持从指定断点续播、本地/WebDAV/在线多源加载以及音质级联降级。
     *
     * @param song 目标曲目实体
     * @param forceTier 强制指定的音质级别；为 null 时采用用户全局首选音质偏好
     * @param seekToMs 起播起始跳转时间戳（毫秒），默认 0L
     */
    fun playSong(
        song: Song,
        forceTier: AudioQualityTier? = null,
        seekToMs: Long = 0L,
        isQueueAdvance: Boolean = false,
    ) {
        val isLocalOrWebDav = isLocalOrWebDavSong(song)
        if (ListenTogetherManager.isInRoom && isLocalOrWebDav) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        val isInTogether = ListenTogetherManager.isInRoom
        val isBothVip = ListenTogetherManager.isBothVip.value
        val isSyncing = ListenTogetherManager.isSyncingRemote

        if (!isLocalOrWebDav && song.isVip) {
            if (isInTogether && !isBothVip) {
                if (!isSyncing && !isQueueAdvance) {
                    if (!checkListenTogetherTrialClick(song)) {
                        return
                    }
                }
                checkAndNotifyListenTogetherTrial(song)
            } else if (!isInTogether) {
                val effectiveVipTier = PlaybackCredentialsManager.getEffectiveVipTier()
                if (effectiveVipTier == PlaybackVipTier.NONE) {
                    notifyUser("需要开通会员后才可以播放哦")
                    return
                }
            } else {
                lastNotifiedTrialSongMid = null
            }
        } else {
            lastNotifiedTrialSongMid = null
        }

        stopSilentKeepAlive()
        exoPlayer?.repeatMode = Player.REPEAT_MODE_OFF
        lastCustomStreamArgs = null
        val prevSong = _currentSong.value
        val prevPos = _currentPositionMs.value
        val prevDur = _durationMs.value
        val prevTier = _currentTier.value
        if (prevSong != null && prevSong.songMid != song.songMid) {
            MelodistCacheManager.handleTrackSwitchEviction(prevSong.songMid, prevPos, prevDur, prevTier)
        }
        MelodistCacheManager.onNewSongStarted(song.songMid)
        if (_currentSong.value?.songMid != song.songMid) {
            currentSongRetryCount = 0
            audioTrackRetryCount = 0
            preloadCoordinator.onSongChanged(song.songMid)
        }
        playJob?.cancel()
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = false

        var effectiveSong = song
        val coverFile =
            if (effectiveSong.coverUrl.startsWith("file://")) {
                java.io.File(effectiveSong.coverUrl.removePrefix("file://").substringBefore('?'))
            } else {
                null
            }
        val isCoverInvalid = coverFile != null && (!coverFile.exists() || coverFile.length() == 0L)
        if (effectiveSong.coverUrl.isBlank() || isCoverInvalid) {
            if (effectiveSong.songMid.startsWith("webdav_")) {
                val server =
                    org.melodist.data.WebDavManager
                        .getActiveServer()
                val relativeHref = effectiveSong.mediaMid.ifBlank { effectiveSong.localFilePath ?: "" }
                if (server != null && relativeHref.isNotBlank()) {
                    val cachedCover =
                        org.melodist.data.WebDavManager
                            .getSongCoverPath(server.id, relativeHref)
                    effectiveSong = effectiveSong.copy(coverUrl = cachedCover.orEmpty())
                } else {
                    effectiveSong = effectiveSong.copy(coverUrl = "")
                }
            } else if (isCoverInvalid) {
                effectiveSong = effectiveSong.copy(coverUrl = "")
            }
        }

        val initialLyrics = LyricCacheManager.getLyrics(effectiveSong.songMid).orEmpty()
        _lyrics.value = initialLyrics
        loadLyricsForSong(effectiveSong)
        prefetchAdjacentCoversAndMetadata()

        if (playbackInterceptor?.onInterceptPlaySong(effectiveSong, forceTier, seekToMs) == true) {
            exoPlayer?.pause()
            _currentSong.value = effectiveSong
            _currentPositionMs.value = seekToMs
            _bufferedPositionMs.value = seekToMs
            _durationMs.value = if (effectiveSong.durationSeconds > 0) effectiveSong.durationSeconds * 1000L else 0L
            _currentTier.value = forceTier ?: _preferredTier.value
            _isPlaying.value = true
            _isTransitioning.value = false
            savePlaybackState()
            loadLyricsForSong(effectiveSong)
            appContext?.let { startPlaybackService(it) }
            return
        }

        exoPlayer?.pause()
        _currentSong.value = effectiveSong
        playbackEventListeners.forEach { it.onSongPlay(effectiveSong) }
        _currentPositionMs.value = seekToMs
        _bufferedPositionMs.value = seekToMs
        _durationMs.value = policyCoordinator.resolveDurationMs(effectiveSong)
        _isTransitioning.value = true
        appContext?.let { startPlaybackService(it) }

        val rawTargetTier = forceTier ?: _preferredTier.value
        val targetTierInit = clampCellularTier(rawTargetTier, effectiveSong)
        val completedDownloadTier =
            org.melodist.data.download.DownloadManager
                .getCompletedDownload(effectiveSong.songMid)
                ?.second
        val shouldBypassCache = policyCoordinator.shouldBypassCache(effectiveSong)
        val initialCachedTier =
            if (shouldBypassCache) {
                null
            } else {
                completedDownloadTier
                    ?: MelodistCacheManager.findHigherOrEqualStereoCachedTier(effectiveSong.songMid, targetTierInit)
                    ?: if (MelodistCacheManager.isSongTierCached(effectiveSong.songMid, targetTierInit)) targetTierInit else null
            }
        val isDirectCachedInit =
            !shouldBypassCache &&
                (
                    initialCachedTier != null ||
                        effectiveSong.isLocal ||
                        effectiveSong.songMid.startsWith("local_") ||
                        !effectiveSong.localFilePath.isNullOrBlank()
                )
        _isCurrentTrackFromCache.value = isDirectCachedInit
        _fileCacheFraction.value = if (isDirectCachedInit) 1f else 0f
        val policyTier = policyCoordinator.resolveInitialTier(effectiveSong, targetTierInit)
        _currentTier.value = policyTier ?: (initialCachedTier ?: targetTierInit)

        updateCurrentMediaMetadata(effectiveSong)
        org.melodist.data.RecentPlaybackManager
            .recordSong(effectiveSong)
        _currentTrackSpec.value = null

        val list = queueManager.playlist.value
        val foundIndex = list.indexOfFirst { it.songMid == song.songMid }
        if (foundIndex != -1) {
            queueManager.setCurrentIndex(foundIndex)
            if (!queueManager.isRadioMode.value && queueManager.loopMode.value == PlaybackLoopMode.Shuffle) {
                queueManager.shuffleQueue.syncTo(foundIndex, list.size, list)
            }
        }
        queueManager.checkPrefetchRadioSongs()

        _isLoading.value = true
        _errorMessage.value = null
        _currentPositionMs.value = seekToMs

        appContext?.let { ctx ->
            CoverMemoryManager.trimMemoryWindow(
                context = ctx,
                prevSong = getPreviousSong(),
                currSong = song,
                nextSong = getNextSong(),
            )
        }

        qualityCoordinator.resetForSong(song)
        if (!PlaybackSourceResolver.isPureLocalOrWebDav(song)) {
            if (policyCoordinator.isQualityProbeAllowed(song)) {
                probeHealer.launchProbeJob(song, delayMs = 3000L)
            }
            probeHealer.launchCoverHealJob(song)
        }

        playJob =
            scope.launch {
                val player = exoPlayer ?: return@launch
                val ctx = appContext ?: return@launch
                val rawTargetTier = forceTier ?: _preferredTier.value
                val clampedTier = clampCellularTier(rawTargetTier, song)
                val policyTier = policyCoordinator.resolveInitialTier(song, clampedTier)
                val resolvedTargetTier = policyTier ?: clampedTier
                val isForceTrial = policyCoordinator.isForceTrial(song)
                val bypassPrefetch = policyCoordinator.shouldBypassPrefetch(song)

                val target =
                    PlaybackMediaLoader.resolveTarget(
                        context = ctx,
                        song = song,
                        targetTier = resolvedTargetTier,
                        apiService = apiService,
                        isSongFavorite = { isSongFavorite(it) },
                        metadataBuilder = { buildMediaMetadata(it) },
                        prefetchedUrlInfo = if (forceTier == null && !bypassPrefetch) preloadCoordinator.prefetchedUrlInfo else null,
                        forceTrial = isForceTrial,
                    )

                PlaybackPlayerBinder.bindAndPlay(
                    player = player,
                    target = target,
                    seekToMs = seekToMs,
                    isMuted = _isMuted.value,
                    targetVolume = targetVolume,
                    onPlaybackStarted = { actualTier, isCache, fraction ->
                        _currentTier.value = actualTier
                        _isCurrentTrackFromCache.value = isCache
                        _fileCacheFraction.value = fraction
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)
                        if (preloadCoordinator.prefetchedUrlInfo?.first == song.songMid) {
                            preloadCoordinator.clearPrefetchedUrlInfo()
                        }
                    },
                    onWebDavPostAction = {
                        probeHealer.triggerWebDavMetadataAndCoverHeal(song)
                    },
                    onLocalFilePostAction = {
                        launch(Dispatchers.IO) {
                            try {
                                val rawCover =
                                    org.melodist.data.LocalMusicManager
                                        .ensureRawCover(song)
                                if (!rawCover.isNullOrBlank() && _currentSong.value?.songId == song.songId) {
                                    withContext(Dispatchers.Main) {
                                        val current = _currentSong.value
                                        if (current != null && current.songId == song.songId && current.rawCoverUrl != rawCover) {
                                            val updated =
                                                current.copy(
                                                    rawCoverUrl = rawCover,
                                                    coverUrl = if (current.coverUrl.isBlank()) rawCover else current.coverUrl,
                                                )
                                            _currentSong.value = updated
                                            queueManager.updateSongInPlaylist(updated)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("MelodistPlayback", "Failed to update song details with raw cover", e)
                            }
                        }
                    },
                    onFailure = { msg, retry ->
                        handlePlaybackFailure(msg, retry)
                    },
                )
            }
    }



    /**
     * 反转当前回放状态（正在播放则暂停，已暂停则恢复播放）。
     */
    fun togglePlayPause() {
        if (playbackInterceptor?.onInterceptTogglePlayPause() == true) return
        val player = exoPlayer ?: return
        val currSong = _currentSong.value

        if (player.currentMediaItem == null && currSong != null) {
            playSong(currSong, seekToMs = _currentPositionMs.value)
            return
        }

        if (player.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    /**
     * 暂停当前音频回放并同步状态。
     */
    fun pause() {
        if (playbackInterceptor?.onInterceptPause() == true) return
        val player = exoPlayer ?: return
        player.pause()
        progressTracker.flushActivePlaybackSession()
        savePlaybackProgress(player.currentPosition.coerceAtLeast(0L))
        playbackEventListeners.forEach { it.onPause() }
    }

    /**
     * 结算并触发当前正在追踪的曲目播放有效时长上报。
     */
    fun flushActivePlaybackSession() {
        progressTracker.flushActivePlaybackSession()
    }

    /**
     * 恢复或启动当前音频回放。
     */
    fun play() {
        if (playbackInterceptor?.onInterceptResume() == true) return
        val currSong = _currentSong.value
        if (currSong != null && ListenTogetherManager.isInRoom && isLocalOrWebDavSong(currSong)) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        val player = exoPlayer ?: return
        if (!player.isPlaying) {
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else if (player.currentMediaItem == null && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else {
                player.play()
                appContext?.let { startPlaybackService(it) }
            }
        }
        playbackEventListeners.forEach { it.onPlay() }
    }

    private var targetVolume = 1f

    /**
     * 设置播放器音量大小。
     *
     * @param volume 音量比例（0.0f ~ 1.0f）
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        targetVolume = clamped
        exoPlayer?.volume = if (_isMuted.value) 0f else clamped
    }

    /**
     * 播放自定义直链音频流（支持 WebDAV 或外部流式播放）。
     *
     * @param song 曲目元数据对象
     * @param streamUrl 音频媒体资源 URL
     * @param headers 自定义 HTTP 请求头映射
     * @param seekToMs 起播跳转位置（毫秒）
     */
    fun playCustomStream(
        song: Song,
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
        seekToMs: Long = 0L,
    ) {
        if (ListenTogetherManager.isInRoom && isLocalOrWebDavSong(song)) {
            notifyUser("请退出一起听后再播放哦")
            return
        }
        lastCustomStreamArgs = Triple(song, streamUrl, headers)
        MelodistCacheManager.onNewSongStarted(song.songMid)
        if (_currentSong.value?.songMid != song.songMid) {
            currentSongRetryCount = 0
            audioTrackRetryCount = 0
        }
        consecutiveErrorCount = 0
        playJob?.cancel()
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = false

        _currentSong.value = song
        _isTransitioning.value = true
        appContext?.let { startPlaybackService(it) }
        updateCurrentMediaMetadata(song)
        org.melodist.data.RecentPlaybackManager
            .recordSong(song)
        _currentTrackSpec.value = null

        val list = queueManager.playlist.value
        val foundIndex = list.indexOfFirst { it.songMid == song.songMid }
        if (foundIndex != -1) {
            queueManager.setCurrentIndex(foundIndex)
        }

        _isLoading.value = true
        _errorMessage.value = null
        _lyrics.value = emptyList()
        _currentPositionMs.value = seekToMs
        if (song.isWebDav || song.songMid.startsWith("webdav_")) {
            prefetchAdjacentWebDavCovers()
        }

        val player = exoPlayer ?: return
        val baseHttpFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent("MelodistTV/1.0 ConnectStream")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)
        if (headers.isNotEmpty()) {
            baseHttpFactory.setDefaultRequestProperties(headers)
        }
        val ctx = appContext ?: return
        val dataSourceFactory = DefaultDataSource.Factory(ctx, baseHttpFactory)
        val mediaSource =
            ProgressiveMediaSource
                .Factory(dataSourceFactory, PlaybackEngineFactory.createExtractorsFactory())
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                .createMediaSource(buildMediaItem(android.net.Uri.parse(streamUrl), song))

        if (seekToMs > 0L) {
            player.setMediaSource(mediaSource, seekToMs)
        } else {
            player.setMediaSource(mediaSource)
        }
        player.volume = if (_isMuted.value) 0f else targetVolume
        player.prepare()
        player.play()
        _isTransitioning.value = false
        _isLoading.value = false
        savePlaybackState()
        loadLyricsForSong(song)
    }

    /**
     * 跳转至当前曲目的指定播放时间位置。
     *
     * @param positionMs 目标绝对时间戳（毫秒）
     */
    fun seekTo(positionMs: Long) {
        if (playbackInterceptor?.onInterceptSeekTo(positionMs) == true) return
        exoPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        savePlaybackProgress(positionMs)
        playbackEventListeners.forEach { it.onSeekTo(positionMs) }
    }

    /**
     * 同步远端设备推送的实时播放状态（曲目、播放状态、进度、时长与队列拓扑）。
     *
     * @param song 远端当前播放曲目
     * @param isPlaying 远端播放状态
     * @param positionMs 远端播放进度（毫秒）
     * @param durationMs 远端音频总时长（毫秒）
     * @param currentIndex 远端当前索引
     * @param loopModeName 循环模式名称
     * @param prevSong 远端上一首曲目
     * @param nextSong 远端下一首曲目
     * @param currentTier 当前音质级别
     * @param availableTiers 可选音质级别集合
     * @param isRadioMode 是否处于电台模式
     */
    fun syncRemotePlaybackState(
        song: Song?,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        currentIndex: Int = -1,
        loopModeName: String? = null,
        prevSong: Song? = null,
        nextSong: Song? = null,
        currentTier: AudioQualityTier? = null,
        availableTiers: Set<AudioQualityTier> = emptySet(),
        isRadioMode: Boolean = false,
        lyricOffsetMs: Long = 0L,
    ) {
        val prevLocalSong = _currentSong.value
        if (song != null) {
            _currentSong.value = song
            if (lyricOffsetMs != 0L) {
                _currentLyricOffsetMs.value = lyricOffsetMs
            } else if (org.melodist.data.LyricCacheManager
                    .hasLyricOffsetRecord(song.songMid)
            ) {
                _currentLyricOffsetMs.value =
                    org.melodist.data.LyricCacheManager
                        .getLyricOffsetMs(song.songMid)
            }
            if (prevLocalSong?.songMid != song.songMid) {
                loadLyricsForSong(song)
            }
            if (currentIndex >= 0 && currentIndex < queueManager.playlist.value.size) {
                queueManager.setCurrentIndex(currentIndex)
            } else {
                val idx = queueManager.playlist.value.indexOfFirst { it.songMid == song.songMid }
                if (idx >= 0) {
                    queueManager.setCurrentIndex(idx)
                }
            }
        }
        remoteStateHolder.setRemotePrevSong(prevSong)
        remoteStateHolder.setRemoteNextSong(nextSong)
        queueManager.setRadioMode(isRadioMode)
        if (currentTier != null) {
            _currentTier.value = currentTier
        }
        if (availableTiers.isNotEmpty()) {
            qualityCoordinator.updateAvailableTiers(availableTiers)
        }
        if (!loopModeName.isNullOrBlank()) {
            try {
                queueManager.setLoopMode(PlaybackLoopMode.valueOf(loopModeName))
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w("PlaybackLoopMode", "Operation failed", e)
            }
        }
        if (song != null) {
            _isPlaying.value = isPlaying
            _currentPositionMs.value = positionMs
            remoteStateHolder.updateSyncTimeline(positionMs, isPlaying)
            if (durationMs > 0L) {
                _durationMs.value = durationMs
            }
        }
    }

    /**
     * 接管模式下触发切歌时的本地乐观曲目推进
     */
    fun optimisticSwitchRemoteTrack(
        targetSong: Song,
        isNext: Boolean,
    ) {
        if (!remoteStateHolder.isRemoteActive.value) return
        val current = _currentSong.value
        _currentSong.value = targetSong
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        loadLyricsForSong(targetSong)
        val list = queueManager.playlist.value
        val idx = list.indexOfFirst { it.songMid == targetSong.songMid }
        if (idx >= 0) {
            queueManager.setCurrentIndex(idx)
            val newPrev = if (isNext) current else (if (idx - 1 in list.indices) list[idx - 1] else null)
            val newNext = if (!isNext) current else (if (idx + 1 in list.indices) list[idx + 1] else null)
            remoteStateHolder.setRemotePrevSong(newPrev)
            remoteStateHolder.setRemoteNextSong(newNext)
        } else {
            if (isNext) {
                remoteStateHolder.setRemotePrevSong(current)
                remoteStateHolder.setRemoteNextSong(null)
            } else {
                remoteStateHolder.setRemotePrevSong(null)
                remoteStateHolder.setRemoteNextSong(current)
            }
        }
    }

    /**
     * 同步远端设备推送的播放队列与当前曲目索引。
     *
     * @param queue 远端播放队列
     * @param currentIndex 远端当前索引
     */
    fun syncRemoteQueue(
        queue: List<Song>,
        currentIndex: Int,
    ) {
        if (queue.isNotEmpty()) {
            queueManager.syncRemoteQueue(queue, currentIndex)
        }
    }

    /**
     * 异步为指定歌曲拉取并解析在线或本地匹配歌词。
     *
     * @param song 目标曲目
     */
    fun loadLyricsForSong(song: Song) {
        lyricsCoordinator.loadLyricsForSong(song)
    }

    fun setExternalLyrics(
        songMid: String,
        lyrics: List<LyricLine>,
    ) {
        lyricsCoordinator.setExternalLyrics(
            songMid = songMid,
            lyrics = lyrics,
            currentLyrics = _lyrics.value,
            isCurrentSong = _currentSong.value?.songMid == songMid,
        )
    }

    fun isCellularNetwork(): Boolean = PlaybackSourceResolver.isCellularNetwork(appContext)

    fun isLocalOrWebDavSong(song: Song?): Boolean {
        if (song == null) return false
        if (lastCustomStreamArgs != null && _currentSong.value?.songMid == song.songMid) {
            return true
        }
        return PlaybackSourceResolver.isLocalOrWebDavSong(song)
    }

    fun isPureLocalOrWebDavSong(song: Song?): Boolean {
        if (song == null) return false
        if (lastCustomStreamArgs != null && _currentSong.value?.songMid == song.songMid) {
            return true
        }
        return PlaybackSourceResolver.isPureLocalOrWebDav(song)
    }

    fun getAudioQualityRank(tier: AudioQualityTier): Int = PlaybackSourceResolver.getAudioQualityRank(tier)

    fun clampCellularTier(
        requestedTier: AudioQualityTier,
        song: Song?,
    ): AudioQualityTier =
        PlaybackSourceResolver.clampCellularTier(
            requestedTier = requestedTier,
            song = song,
            context = appContext,
            cellularLimit = org.melodist.data.AppSettingsManager.settings.value.cellularQualityTier,
        )

    fun setPreferredQualityTier(tier: AudioQualityTier) {
        _preferredTier.value = tier
        org.melodist.data.AppSettingsManager
            .updatePreferredQualityTier(tier)
        savePlaybackState()
        val current = _currentSong.value
        if (current != null) {
            val effectiveTier = clampCellularTier(tier, current)
            if (_currentTier.value != effectiveTier) {
                switchTier(effectiveTier)
            }
        }
    }

    fun getFallbackTier(current: AudioQualityTier): AudioQualityTier? = PlaybackSourceResolver.getFallbackTier(current)

    /**
     * 切换当前播放歌曲的音质级别并续播。
     *
     * @param tier 目标音质级别
     * @param isAutoFallback 是否因播放故障引发的自动降级（为 true 时不覆盖用户全局首选音质偏好）
     */
    fun switchTier(
        tier: AudioQualityTier,
        isAutoFallback: Boolean = false,
    ) {
        val current = _currentSong.value ?: return
        val interceptionReason = policyCoordinator.evaluateSwitchTierInterception(current, tier)
        if (interceptionReason != null) {
            if (!isAutoFallback) {
                notifyUser(interceptionReason)
            }
            return
        }
        val effectiveTier = clampCellularTier(tier, current)
        if (effectiveTier != tier) {
            _errorMessage.value = "当前为移动网络，已限制为上限音质：${AudioQualityTier.getBadge(effectiveTier)}"
        }
        val previousTier = _currentTier.value
        if (!isAutoFallback) {
            _preferredTier.value = effectiveTier
            org.melodist.data.AppSettingsManager
                .updatePreferredQualityTier(effectiveTier)
            savePlaybackState()
        }

        if (playbackInterceptor?.onInterceptSwitchTier(effectiveTier) == true) {
            _currentTier.value = effectiveTier
            _isSwitchingQuality.value = false
            return
        }

        if (previousTier == effectiveTier && exoPlayer?.currentMediaItem != null) {
            _isSwitchingQuality.value = false
            return
        }

        val isPlayerPlaying = exoPlayer?.isPlaying == true
        val isPlayerValid = exoPlayer?.currentMediaItem != null

        // 保持当前音频流，后台预加载新音质
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = true

        switchQualityJob =
            scope.launch {
                try {
                    val isTargetFullyCached = MelodistCacheManager.isSongTierFullyCached(current.songMid, effectiveTier)
                    val playUrlInfo =
                        if (isTargetFullyCached) {
                            Log.i("MelodistPlayback", "Reusing local complete cache for tier switch: ${current.name} ($effectiveTier)")
                            val dummyUri = "https://cache.melodist.internal/${current.songMid}?tier=${effectiveTier.name}"
                            org.melodist.api.QualityResult(
                                url = dummyUri,
                                tier = effectiveTier,
                                badge = AudioQualityTier.getBadge(effectiveTier),
                            )
                        } else {
                            withContext(Dispatchers.IO) {
                                try {
                                    apiService.getPlayUrl(current.songMid, mediaMid = current.mediaMid, preferredTier = effectiveTier)
                                } catch (e: Exception) {
                                    Log.w("MelodistPlayback", "Failed to fetch play url for quality $effectiveTier: ${e.message}", e)
                                    null
                                }
                            }
                        }

                    // 防竞态检查：若返回时已切歌，则丢弃本次结果
                    if (_currentSong.value?.songMid != current.songMid) {
                        return@launch
                    }

                    val rawUrl = playUrlInfo?.url
                    if (playUrlInfo != null && !rawUrl.isNullOrBlank()) {
                        _currentTier.value = playUrlInfo.tier
                        _currentSong.value = _currentSong.value?.copy(currentTier = playUrlInfo.tier)
                        val isTargetFullyCached = MelodistCacheManager.isSongTierFullyCached(current.songMid, playUrlInfo.tier)
                        _isCurrentTrackFromCache.value = isTargetFullyCached
                        _fileCacheFraction.value = if (isTargetFullyCached) 1f else 0f
                        val player = exoPlayer ?: return@launch

                        // 在新媒体注入播放器前抓取实时播放进度，保持播放位置连续
                        val livePositionMs =
                            if (isPlayerValid) {
                                player.currentPosition.takeIf { it > 0L } ?: _currentPositionMs.value
                            } else {
                                _currentPositionMs.value
                            }

                        player.volume = if (_isMuted.value) 0f else targetVolume
                        val mediaItem = buildMediaItem(android.net.Uri.parse(rawUrl), current, playUrlInfo.tier)
                        if (livePositionMs > 0L) {
                            player.setMediaItem(mediaItem, livePositionMs)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
                        _bufferedPositionMs.value = livePositionMs
                        player.prepare()
                        if (isPlayerPlaying || player.playWhenReady) {
                            player.play()
                        }
                        savePlaybackState()
                        Log.i("MelodistPlayback", "Seamlessly switched to tier ${playUrlInfo.tier} at position ${livePositionMs}ms")
                    } else {
                        _isSwitchingQuality.value = false
                        val fallback = getFallbackTier(effectiveTier)
                        if (fallback != null && fallback != previousTier) {
                            _errorMessage.value = "该音质不可用，已自动降级为 ${AudioQualityTier.getBadge(fallback)}"
                            switchTier(fallback, isAutoFallback = true)
                        } else {
                            _errorMessage.value = "该音质不可用，已恢复为 ${AudioQualityTier.getBadge(previousTier)}"
                            _preferredTier.value = previousTier
                            if (_currentTier.value != previousTier && !isPlayerValid) {
                                switchTier(previousTier, isAutoFallback = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    _isSwitchingQuality.value = false
                    Log.e("MelodistPlayback", "Unexpected error during seamless quality switch to $effectiveTier", e)
                }
            }
    }

    fun ensureQualityProbed() {
        val current = _currentSong.value ?: return
        if (!policyCoordinator.isQualityProbeAllowed(current)) return
        qualityCoordinator.ensureQualityProbed(
            song = _currentSong.value,
            currentSongMidProvider = { _currentSong.value?.songMid },
            currentTierProvider = { _currentTier.value },
            preferredTierProvider = { _preferredTier.value },
            context = appContext,
            onAutoUpgrade = { switchTier(it) },
        )
    }


    /**
     * 播放队列中的下一首曲目。
     */
    fun playNext() {
        if (playbackInterceptor?.onInterceptPlayNext() == true) {
            return
        }
        queueManager.playNext()
    }

    /**
     * 播放队列中的上一首曲目。
     */
    fun playPrevious() {
        if (playbackInterceptor?.onInterceptPlayPrevious() == true) {
            return
        }
        queueManager.playPrevious()
    }

    /**
     * 获取上一首即将播放的歌曲（用于滑动预览），不推进播放状态
     */
    fun getPreviousSong(): Song? = queueManager.getPreviousSong(remoteStateHolder.isRemoteActive.value, remoteStateHolder.remotePrevSong.value)

    /**
     * 获取下一首即将播放的歌曲（用于滑动预览），不推进播放状态
     */
    fun getNextSong(): Song? = queueManager.getNextSong(remoteStateHolder.isRemoteActive.value, remoteStateHolder.remoteNextSong.value)

    /**
     * 顺次循环切换播放模式（顺序循环 -> 单曲循环 -> 随机播放）。
     */
    fun cycleLoopMode() {
        if (playbackInterceptor?.onInterceptCycleLoopMode() == true) {
            return
        }
        queueManager.cycleLoopMode()
        prefetchAdjacentWebDavCovers()
    }

    /**
     * 设置播放队列的循环模式。
     *
     * @param mode 目标循环模式
     */
    fun setLoopMode(mode: PlaybackLoopMode) {
        queueManager.setLoopMode(mode)
        prefetchAdjacentWebDavCovers()
    }

    /**
     * 后台预加载相邻曲目（优先下一首）的原始封面图与歌曲信息。
     */
    fun prefetchAdjacentCoversAndMetadata() = preloadCoordinator.prefetchAdjacentCoversAndMetadata()

    fun prefetchAdjacentWebDavCovers() = preloadCoordinator.prefetchAdjacentCoversAndMetadata()

    private fun handleSongEnded() {
        if (remoteStateHolder.isRemoteActive.value) {
            Log.d("MelodistPlayback", "Remote playback is active, skip local song ended auto-advance.")
            return
        }
        _isTransitioning.value = true
        when {
            !queueManager.isRadioMode.value && !queueManager.hasPendingNextPlay && queueManager.loopMode.value == PlaybackLoopMode.SingleRepeat -> {
                progressTracker.flushActivePlaybackSession()
                _currentPositionMs.value = 0L
                _bufferedPositionMs.value = 0L
                exoPlayer?.seekTo(0L)
                exoPlayer?.play()
                _isTransitioning.value = false
            }

            else -> {
                playNext()
            }
        }
    }

    /**
     * 释放底层 ExoPlayer 播放器管线、注销硬件音频监听并取消活跃协程任务。
     */
    fun release() {
        appContext?.let { usbRouter.unregister(it) }
        savePlaybackState()
        progressTracker.stop()
        playJob?.cancel()
        preloadCoordinator.release()
        playerPipeline.release()
        _isPlaying.value = false
        _isLoading.value = false
        _isSwitchingQuality.value = false
    }
}

data class AudioTrackSpec(
    val format: String = "",
    val bitDepth: Int = 16,
    val sampleRateHz: Int = 44100,
    val bitrateKbps: Int = 0,
)
