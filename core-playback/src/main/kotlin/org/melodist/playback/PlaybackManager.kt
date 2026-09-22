package org.melodist.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.melodist.api.MusicApiService
import org.melodist.api.QualityResult
import org.melodist.api.UserSession
import org.melodist.api.getPlayUrl
import org.melodist.api.getSongVisualMid
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
import org.melodist.model.QualityOption
import org.melodist.model.Song

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

object PlaybackManager {
    var playbackInterceptor: PlaybackInterceptor? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
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
                playSong(song, forceTier = forceTier, seekToMs = seekToMs)
            },
            onStopPlaybackRequest = {
                exoPlayer?.stop()
                exoPlayer?.clearMediaItems()
                _currentSong.value = null
                _lyrics.value = emptyList()
                _isPlaying.value = false
            },
        )
    val playlist: StateFlow<List<Song>> = queueManager.playlist
    val currentIndex: StateFlow<Int> = queueManager.currentIndex
    val loopMode: StateFlow<PlaybackLoopMode> = queueManager.loopMode
    val isRadioMode: StateFlow<Boolean> = queueManager.isRadioMode
    val queueTag: StateFlow<String?> = queueManager.queueTag
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

    val qualityCoordinator by lazy { AudioQualityCoordinator(scope, apiService) }
    val lyricsCoordinator by lazy {
        PlaybackLyricsCoordinator(
            scope = scope,
            apiService = apiService,
            currentSongProvider = { _currentSong.value },
            onLyricsUpdated = { _lyrics.value = it },
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

    private var prefetchedSongMid: String? = null
    private var prefetchedUrlInfo: Pair<String, QualityResult>? = null
    private var prefetchJob: Job? = null
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
            return _isPlaying.value
        }
        val player = exoPlayer ?: return false
        return player.playWhenReady || _isPlaying.value || _isLoading.value || _isTransitioning.value
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

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
        if (!_isSilentKeepAlive.value) return
        _isSilentKeepAlive.value = false
        val player = exoPlayer ?: return
        player.stop()
        player.clearMediaItems()
        resetPlaybackSpeed()
    }

    val activeMediaId: String?
        get() = exoPlayer?.currentMediaItem?.mediaId

    fun setPlaybackSpeed(speed: Float) {
        val player = exoPlayer ?: return
        if (Math.abs(player.playbackParameters.speed - speed) > 0.005f) {
            player.setPlaybackSpeed(speed)
        }
    }

    fun resetPlaybackSpeed() {
        setPlaybackSpeed(1.0f)
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                // 无缝音质切换期间，忽略 ExoPlayer 内部重加载媒体源引起的短暂 isPlaying=false 抖动
                if (_isSwitchingQuality.value && !playing && exoPlayer?.playWhenReady == true) {
                    return
                }
                _isPlaying.value = playing
                if (playing) {
                    currentSongRetryCount = 0
                    consecutiveErrorCount = 0
                    audioTrackRetryCount = 0
                } else {
                    savePlaybackProgress(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        _durationMs.value = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
                        if (exoPlayer?.isPlaying == true) {
                            currentSongRetryCount = 0
                            consecutiveErrorCount = 0
                            audioTrackRetryCount = 0
                        }
                        if (_isSwitchingQuality.value) {
                            _isSwitchingQuality.value = false
                            _isPlaying.value = exoPlayer?.isPlaying == true
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
                    _errorMessage.value = "当前音质播放失败，已自动降级为 ${AudioQualityTier.getBadge(fallback)}"
                    scope.launch {
                        delay(500L)
                        switchTier(fallback)
                    }
                } else {
                    handlePlaybackFailure("播放失败: ${error.errorCodeName}")
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                val format = group.getTrackFormat(i)
                                val sRate = format.sampleRate
                                val channels = format.channelCount
                                val mime = format.sampleMimeType
                                val bitrate = format.bitrate
                                val bitDepth =
                                    when (format.pcmEncoding) {
                                        C.ENCODING_PCM_24BIT -> 24
                                        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                                        else -> {
                                            val currentTier = _currentTier.value
                                            val probedDepth = probedQualityOptions.value.find { it.tier == currentTier }?.bitDepth ?: 0
                                            when {
                                                probedDepth > 0 -> probedDepth
                                                currentTier == AudioQualityTier.Master || currentTier == AudioQualityTier.HiRes -> 24
                                                sRate >= 88200 -> 24
                                                else -> 16
                                            }
                                        }
                                    }
                                if (sRate > 0) {
                                    val isUsbExclusive = org.melodist.data.AppSettingsManager.settings.value.enableUsbExclusive
                                    if (isUsbExclusive) {
                                        val context = appContext
                                        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                        val usbDevice = usbRouter.findUsbAudioDevice(audioManager)
                                        if (audioManager != null && usbDevice != null) {
                                            val needReload =
                                                usbRouter.configureBitPerfectMixer(
                                                    audioManager = audioManager,
                                                    usbDevice = usbDevice,
                                                    targetRate = sRate,
                                                    channelCount = channels,
                                                    pcmEncoding = format.pcmEncoding,
                                                )
                                            if (needReload) {
                                                reloadAudioPipeline()
                                            }
                                        }
                                    }
                                    val detectedTier =
                                        AudioQualityTier.inferFromAudioFormat(
                                            sampleRate = sRate,
                                            bitsPerSample = bitDepth,
                                            channelCount = if (channels > 0) channels else 2,
                                            mimeType = mime,
                                            bitrate = if (bitrate > 0) bitrate else 0,
                                        )
                                    val formatStr =
                                        when {
                                            mime?.contains("flac") == true -> "Flac"
                                            mime?.contains("mp4a") == true || mime?.contains("aac") == true -> "AAC"
                                            mime?.contains("mpeg") == true || mime?.contains("mp3") == true -> "Mp3"
                                            mime?.contains("eac3-joc") == true || mime?.contains("atmos") == true -> "E-AC3 JOC"
                                            mime?.contains("eac3") == true -> "E-AC3"
                                            mime?.contains("ac3") == true -> "AC3"
                                            mime?.contains("opus") == true -> "Opus"
                                            mime?.contains("ogg") == true -> "OGG"
                                            mime?.contains("wav") == true -> "Wav"
                                            else -> mime?.substringAfterLast('/')?.uppercase() ?: "Flac"
                                        }
                                    val currentSong = _currentSong.value
                                    val probedSize = probedQualityOptions.value.find { it.tier == _currentTier.value }?.sizeBytes ?: 0L
                                    val currentDur = currentSong?.durationSeconds ?: 0
                                    val calcKbps =
                                        if (currentDur > 0 && probedSize > 0L) {
                                            ((probedSize * 8L) / 1024L / currentDur).toInt()
                                        } else {
                                            0
                                        }
                                    val kbps = if (bitrate > 0) bitrate / 1000 else calcKbps
                                    _currentTrackSpec.value =
                                        AudioTrackSpec(
                                            format = formatStr,
                                            bitDepth = bitDepth,
                                            sampleRateHz = sRate,
                                            bitrateKbps = kbps,
                                        )
                                    val currentMid = currentSong?.songMid.orEmpty()
                                    val isLocalOrWebDav =
                                        currentMid.startsWith("webdav_") ||
                                            currentMid.startsWith("local_") ||
                                            !currentSong?.localFilePath.isNullOrBlank()
                                    if (isLocalOrWebDav) {
                                        Log.i(
                                            "MelodistPlayback",
                                            "onTracksChanged auto-detected tier: $detectedTier ($sRate Hz, $bitDepth-bit, $channels ch, $mime, $bitrate bps)",
                                        )
                                        _currentTier.value = detectedTier
                                        _currentSong.value = currentSong?.copy(currentTier = detectedTier)
                                    }
                                }
                            }
                        }
                    }
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
            onTriggerPrefetchNextSongRequest = { triggerPrefetchNextSong() },
        )

    private fun buildExoPlayer(context: Context): ExoPlayer = playerPipeline.buildExoPlayer(context)

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        init(context)
        return playerPipeline.getOrCreatePlayer(context)
    }

    fun buildMediaMetadata(
        song: Song,
        remoteDeviceName: String? = null,
    ): MediaMetadata = PlaybackMediaItemFactory.buildMediaMetadata(song, remoteDeviceName)

    private fun buildMediaItem(
        uri: android.net.Uri,
        song: Song,
        tier: AudioQualityTier? = null,
    ): MediaItem = PlaybackMediaItemFactory.buildMediaItem(uri, song, tier)

    fun buildMediaItemForSong(
        song: Song,
        remoteDeviceName: String? = null,
    ): MediaItem = PlaybackMediaItemFactory.buildMediaItemForSong(song, remoteDeviceName)

    private fun updateCurrentMediaMetadata(song: Song) {
        val player = exoPlayer ?: return
        val currentItem = player.currentMediaItem ?: return
        if (player.currentMediaItemIndex >= 0) {
            val updatedItem = currentItem.buildUpon().setMediaMetadata(buildMediaMetadata(song)).build()
            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
        }
    }

    fun startPlaybackService(context: Context) {
        try {
            val intent = Intent(context.applicationContext, PlaybackService::class.java)
            context.applicationContext.startService(intent)
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to start PlaybackService: $e")
        }
    }

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            val profile = PlaybackProfile.detect(context)
            MelodistCacheManager.init(context, profile)
            org.melodist.data.AppSettingsManager.mediaCacheSizeProvider = { MelodistCacheManager.getCacheSizeBytes() }
            org.melodist.data.AppSettingsManager.mediaQuotaProvider = { MelodistCacheManager.activeCacheQuotaBytes }
            org.melodist.data.AppSettingsManager.cachedTrackCountProvider = { MelodistCacheManager.getCachedKeyCount() }
            org.melodist.data.AppSettingsManager.mediaCacheClearAction = { MelodistCacheManager.clearAllCache() }
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
        org.melodist.data.AppSettingsManager.onAudioOffloadChangedListener = { enabled ->
            applyAudioOffloadPreferences(enabled)
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

    fun applyAudioOffloadPreferences(enabled: Boolean) = playerPipeline.applyAudioOffloadPreferences(enabled)

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

    private fun triggerPrefetchNextSong() {
        if (prefetchJob?.isActive == true) return
        val nextSong = getNextSong() ?: return
        val nextMid = nextSong.songMid
        if (nextMid == prefetchedSongMid) return
        prefetchedSongMid = nextMid

        val ctx = appContext ?: return
        prefetchJob =
            scope.launch(Dispatchers.IO) {
                // 1. 预取封面至 Coil 磁盘缓存
                val coverUrl = nextSong.coverUrl
                if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                    try {
                        val imageLoader = SingletonImageLoader.get(ctx)
                        val req =
                            ImageRequest
                                .Builder(ctx)
                                .data(coverUrl)
                                .build()
                        imageLoader.enqueue(req)
                        Log.d("MelodistPlayback", "Prefetched next song cover: ${nextSong.name}")
                    } catch (e: Exception) {
                        Log.w("MelodistPlayback", "Failed to prefetch cover for ${nextSong.name}", e)
                    }
                }

                // 2. 本地纯文件歌曲或 WebDAV 歌曲：无需预取在线播放数据
                val isPureLocal = !nextSong.localFilePath.isNullOrBlank() && !nextSong.songMid.startsWith("webdav_")
                val isWebDav = nextSong.isWebDav || nextSong.songMid.startsWith("webdav_")
                if (isPureLocal || isWebDav) return@launch

                // 3. 在线歌曲：若本地已有完整缓存，直接跳过网络预取
                val rawTargetTier = _preferredTier.value
                val targetTier = clampCellularTier(rawTargetTier, nextSong)
                val higherStereoTier = MelodistCacheManager.findHigherOrEqualStereoCachedTier(nextSong.songMid, targetTier)
                if (higherStereoTier != null) {
                    Log.d("MelodistPlayback", "Next song already cached locally ($higherStereoTier) for ${nextSong.name}, skip prefetch")
                    return@launch
                }

                // 4. 在线歌曲：仅预取下一曲播放直链 URL
                try {
                    val playUrlInfo = apiService.getPlayUrl(nextSong.songMid, mediaMid = nextSong.mediaMid, preferredTier = targetTier)
                    val playUrl = playUrlInfo.url
                    if (!playUrl.isNullOrBlank()) {
                        prefetchedUrlInfo = Pair(nextSong.songMid, playUrlInfo)
                        Log.i("MelodistPlayback", "Prefetched next song URL: ${nextSong.name}, tier: ${playUrlInfo.tier}")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("MelodistPlayback", "Prefetch next song failed for ${nextSong.name}", e)
                }
            }
    }

    private fun startProgressLoop() = progressTracker.start()

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
    ) {
        setMuted(startMuted)
        queueManager.setPlaylist(
            songs = songs,
            startIndex = startIndex,
            isRadio = isRadio,
            initialSeekToMs = initialSeekToMs,
            forceTier = forceTier,
            paginationSource = paginationSource,
            queueTag = queueTag,
        )
    }

    fun setPaginationSource(source: QueuePaginationSource?) = queueManager.setPaginationSource(source)

    suspend fun loadMoreForQueue(): Boolean = queueManager.loadMoreForQueue()

    fun appendPlaylist(
        newSongs: List<Song>,
        targetTag: String? = null,
    ) = queueManager.appendPlaylist(newSongs, targetTag)

    fun insertNextPlay(song: Song) = queueManager.insertNextPlay(song)

    fun insertAndPlay(
        song: Song,
        seekToMs: Long = 0L,
    ) = queueManager.insertAndPlay(song, seekToMs)

    fun removeFromPlaylist(index: Int) = queueManager.removeFromPlaylist(index)

    fun removeFromPlaylist(songs: List<Song>) = queueManager.removeFromPlaylist(songs, _currentSong.value?.songMid)

    fun clearPlaylist() = queueManager.clearPlaylist()

    fun playSong(
        song: Song,
        forceTier: AudioQualityTier? = null,
        seekToMs: Long = 0L,
    ) {
        stopSilentKeepAlive()
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
            if (prefetchedSongMid != song.songMid) {
                prefetchJob?.cancel()
                prefetchedSongMid = null
                prefetchedUrlInfo = null
            }
        }
        playJob?.cancel()
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = false
        appContext?.let { startPlaybackService(it) }

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
            return
        }

        exoPlayer?.pause()
        _currentSong.value = effectiveSong
        _currentPositionMs.value = seekToMs
        _bufferedPositionMs.value = seekToMs
        _durationMs.value = if (effectiveSong.durationSeconds > 0) effectiveSong.durationSeconds * 1000L else 0L
        _isTransitioning.value = true

        val targetTierInit = forceTier ?: _preferredTier.value
        val initialCachedTier =
            MelodistCacheManager.findHigherOrEqualStereoCachedTier(effectiveSong.songMid, targetTierInit)
                ?: if (MelodistCacheManager.isSongTierCached(effectiveSong.songMid, targetTierInit)) targetTierInit else null
        val isDirectCachedInit =
            initialCachedTier != null ||
                effectiveSong.isLocal ||
                effectiveSong.songMid.startsWith("local_") ||
                !effectiveSong.localFilePath.isNullOrBlank()
        _isCurrentTrackFromCache.value = isDirectCachedInit
        _fileCacheFraction.value = if (isDirectCachedInit) 1f else 0f
        if (initialCachedTier != null) {
            _currentTier.value = initialCachedTier
        } else if (forceTier != null) {
            _currentTier.value = forceTier
        }

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
        _lyrics.value = emptyList()
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
        if (!PlaybackSourceResolver.isLocalOrWebDavSong(song)) {
            launchProbeJob(song, delayMs = 3000L)
            launchCoverHealJob(song)
        }

        playJob =
            scope.launch {
                val player = exoPlayer ?: return@launch
                val ctx = appContext ?: return@launch
                val rawTargetTier = forceTier ?: _preferredTier.value
                val targetTier = clampCellularTier(rawTargetTier, song)

                when (
                    val target =
                        PlaybackMediaLoader.resolveTarget(
                            context = ctx,
                            song = song,
                            targetTier = targetTier,
                            apiService = apiService,
                            isSongFavorite = { isSongFavorite(it) },
                            metadataBuilder = { buildMediaMetadata(it) },
                            prefetchedUrlInfo = if (forceTier == null) prefetchedUrlInfo else null,
                        )
                ) {
                    is PlaybackTargetResult.LocalFile -> {
                        _currentTier.value = target.actualTier
                        _isCurrentTrackFromCache.value = true
                        _fileCacheFraction.value = 1f
                        if (seekToMs > 0L) {
                            player.setMediaItem(target.mediaItem, seekToMs)
                        } else {
                            player.setMediaItem(target.mediaItem)
                        }
                        player.prepare()
                        player.play()
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)

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
                            } catch (_: Exception) {
                            }
                        }
                    }
                    is PlaybackTargetResult.LocalStream -> {
                        _currentTier.value = target.actualTier
                        _isCurrentTrackFromCache.value = false
                        if (seekToMs > 0L) {
                            player.setMediaSource(target.mediaSource, seekToMs)
                        } else {
                            player.setMediaSource(target.mediaSource)
                        }
                        player.prepare()
                        player.play()
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)
                    }
                    is PlaybackTargetResult.WebDavLocalCache -> {
                        _currentTier.value = target.actualTier
                        _isCurrentTrackFromCache.value = true
                        _fileCacheFraction.value = 1f
                        if (seekToMs > 0L) {
                            player.setMediaItem(target.mediaItem, seekToMs)
                        } else {
                            player.setMediaItem(target.mediaItem)
                        }
                        player.prepare()
                        player.play()
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)
                        triggerWebDavMetadataAndCoverHeal(song)
                    }
                    is PlaybackTargetResult.WebDavStream -> {
                        _currentTier.value = target.actualTier
                        _isCurrentTrackFromCache.value = false
                        if (seekToMs > 0L) {
                            player.setMediaSource(target.mediaSource, seekToMs)
                        } else {
                            player.setMediaSource(target.mediaSource)
                        }
                        player.prepare()
                        player.play()
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)
                        triggerWebDavMetadataAndCoverHeal(song)
                    }
                    is PlaybackTargetResult.Online -> {
                        _currentTier.value = target.actualTier
                        _isCurrentTrackFromCache.value = target.isDirectCached
                        _fileCacheFraction.value = target.fileCacheFraction
                        if (seekToMs > 0L) {
                            player.setMediaSource(target.mediaSource, seekToMs)
                        } else {
                            player.setMediaSource(target.mediaSource)
                        }
                        player.volume = if (_isMuted.value) 0f else targetVolume
                        player.prepare()
                        player.play()
                        _isTransitioning.value = false
                        savePlaybackState()
                        loadLyricsForSong(song)
                    }
                    is PlaybackTargetResult.Failure -> {
                        handlePlaybackFailure(target.message, target.allowRetry)
                    }
                }
            }
    }

    private fun triggerWebDavMetadataAndCoverHeal(song: Song) {
        scope.launch(Dispatchers.IO) {
            try {
                val server =
                    org.melodist.data.WebDavManager
                        .getActiveServer() ?: return@launch
                val meta =
                    org.melodist.data.WebDavManager
                        .extractPlaybackMetadata(server, song)
                if (_currentSong.value?.songMid == song.songMid) {
                    val currentCover = _currentSong.value?.coverUrl.orEmpty()
                    val currentCoverFile =
                        if (currentCover.startsWith("file://")) {
                            java.io.File(currentCover.removePrefix("file://").substringBefore('?'))
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
                    if (!effectiveNewCover.isNullOrBlank() || newTier != null) {
                        val versionedCover =
                            if (!effectiveNewCover.isNullOrBlank()) {
                                val clean = effectiveNewCover.substringBefore('?')
                                "$clean?t=${System.currentTimeMillis()}"
                            } else {
                                _currentSong.value?.coverUrl.orEmpty()
                            }
                        withContext(Dispatchers.Main) {
                            if (_currentSong.value?.songMid == song.songMid) {
                                val updated =
                                    _currentSong.value?.copy(
                                        coverUrl = versionedCover,
                                        rawCoverUrl = meta.rawCoverUrl ?: _currentSong.value?.rawCoverUrl.orEmpty(),
                                        currentTier = newTier ?: _currentSong.value?.currentTier ?: AudioQualityTier.SQ,
                                    )
                                _currentSong.value = updated
                                if (newTier != null) {
                                    _currentTier.value = newTier
                                    qualityCoordinator.updateAvailableTiers(setOf(newTier))
                                }
                                if (updated != null) {
                                    queueManager.updateSongInPlaylist(updated)
                                    updateCurrentMediaMetadata(updated)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Error extracting WebDAV song metadata", e)
            } finally {
                prefetchAdjacentWebDavCovers()
            }
        }
    }

    fun togglePlayPause() {
        if (playbackInterceptor?.onInterceptTogglePlayPause() == true) return
        val player = exoPlayer ?: return
        val currSong = _currentSong.value

        if (player.currentMediaItem == null && currSong != null) {
            playSong(currSong, seekToMs = _currentPositionMs.value)
            return
        }

        if (player.isPlaying) {
            player.pause()
            savePlaybackProgress(player.currentPosition.coerceAtLeast(0L))
        } else {
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                if (currSong != null) {
                    playSong(currSong, seekToMs = _currentPositionMs.value)
                    return
                }
                player.prepare()
            }
            player.play()
            appContext?.let { startPlaybackService(it) }
        }
    }

    fun pause() {
        if (playbackInterceptor?.onInterceptPause() == true) return
        val player = exoPlayer ?: return
        player.pause()
        savePlaybackProgress(player.currentPosition.coerceAtLeast(0L))
    }

    fun play() {
        if (playbackInterceptor?.onInterceptResume() == true) return
        val player = exoPlayer ?: return
        if (!player.isPlaying) {
            val currSong = _currentSong.value
            if (player.playbackState == androidx.media3.common.Player.STATE_IDLE && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else if (player.currentMediaItem == null && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else {
                player.play()
                appContext?.let { startPlaybackService(it) }
            }
        }
    }

    private var targetVolume = 1f

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        targetVolume = clamped
        exoPlayer?.volume = if (_isMuted.value) 0f else clamped
    }

    fun playCustomStream(
        song: Song,
        streamUrl: String,
        headers: Map<String, String> = emptyMap(),
        seekToMs: Long = 0L,
    ) {
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
        appContext?.let { startPlaybackService(it) }

        _currentSong.value = song
        _isTransitioning.value = true
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
                .Factory(dataSourceFactory)
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

    fun seekTo(positionMs: Long) {
        if (playbackInterceptor?.onInterceptSeekTo(positionMs) == true) return
        exoPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        savePlaybackProgress(positionMs)
    }

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
            } catch (_: Throwable) {
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

    fun syncRemoteQueue(
        queue: List<Song>,
        currentIndex: Int,
    ) {
        if (queue.isNotEmpty()) {
            queueManager.syncRemoteQueue(queue, currentIndex)
        }
    }

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

    fun switchTier(tier: AudioQualityTier) {
        val current = _currentSong.value ?: return
        val effectiveTier = clampCellularTier(tier, current)
        if (effectiveTier != tier) {
            _errorMessage.value = "当前为移动网络，已限制为上限音质：${AudioQualityTier.getBadge(effectiveTier)}"
        }
        val previousTier = _currentTier.value
        _preferredTier.value = effectiveTier
        org.melodist.data.AppSettingsManager
            .updatePreferredQualityTier(effectiveTier)
        savePlaybackState()

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

        // 异步平滑切换：保持当前音质继续发声不断流，在后台预加载新音质
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

                        // 在新媒体就绪并即将注入播放器的瞬间，抓取最新实时播放进度，消除位置断层与回跳
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
                            switchTier(fallback)
                        } else {
                            _errorMessage.value = "该音质不可用，已恢复为 ${AudioQualityTier.getBadge(previousTier)}"
                            _preferredTier.value = previousTier
                            if (_currentTier.value != previousTier && !isPlayerValid) {
                                switchTier(previousTier)
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
        qualityCoordinator.ensureQualityProbed(
            song = _currentSong.value,
            currentSongMidProvider = { _currentSong.value?.songMid },
            currentTierProvider = { _currentTier.value },
            preferredTierProvider = { _preferredTier.value },
            context = appContext,
            onAutoUpgrade = { switchTier(it) },
        )
    }

    private fun launchProbeJob(
        song: Song,
        delayMs: Long,
    ) {
        qualityCoordinator.launchProbeJob(
            song = song,
            delayMs = delayMs,
            currentSongMidProvider = { _currentSong.value?.songMid },
            currentTierProvider = { _currentTier.value },
            preferredTierProvider = { _preferredTier.value },
            context = appContext,
            onTrackSpecBitrateCalculated = { bitrate ->
                val curSpec = _currentTrackSpec.value
                if (curSpec != null && curSpec.bitrateKbps <= 0) {
                    _currentTrackSpec.value = curSpec.copy(bitrateKbps = bitrate)
                }
            },
            onAutoUpgrade = { switchTier(it) },
        )
    }

    private fun launchCoverHealJob(song: Song) {
        if (song.coverUrl.isNotBlank() || song.songMid.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val vsMid = apiService.getSongVisualMid(song.songMid)
                if (!vsMid.isNullOrBlank()) {
                    val singleCover = MusicApiService.getSingleCoverUrl(vsMid)
                    val healed = song.copy(visualMid = vsMid, coverUrl = singleCover)
                    withContext(Dispatchers.Main) {
                        if (_currentSong.value?.songMid == song.songMid) {
                            _currentSong.value = healed
                        }
                        queueManager.updateSongInPlaylist(healed)
                    }
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Self-heal single cover failed", e)
            }
        }
    }

    fun playNext() {
        if (playbackInterceptor?.onInterceptPlayNext() == true) {
            return
        }
        queueManager.playNext()
    }

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

    fun cycleLoopMode() {
        if (playbackInterceptor?.onInterceptCycleLoopMode() == true) {
            return
        }
        queueManager.cycleLoopMode()
        prefetchAdjacentWebDavCovers()
    }

    private val prefetchingTargetMids =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /**
     * 方案 B：后台静默预加载相邻曲目（优先下一首）的原始大图与歌曲信息，
     * 使切歌时无需等待现场提取，实现 4K 原画秒开。
     */
    fun prefetchAdjacentCoversAndMetadata() {
        val next = getNextSong()
        val prev = getPreviousSong()
        val targets = listOfNotNull(next, prev).distinctBy { it.songMid }
        if (targets.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            for (target in targets) {
                if (!prefetchingTargetMids.add(target.songMid)) continue
                try {
                    val isWebDav = target.isWebDav || target.songMid.startsWith("webdav_")
                    val isLocal = target.isLocal || !target.localFilePath.isNullOrBlank() || target.songMid.startsWith("local_")

                    if (isWebDav) {
                        val server =
                            org.melodist.data.WebDavManager
                                .getActiveServer() ?: continue
                        val relativeHref = target.mediaMid.ifBlank { target.localFilePath ?: "" }
                        if (relativeHref.isBlank()) continue

                        val existingRaw =
                            org.melodist.data.WebDavManager
                                .getSongRawCoverPath(server.id, relativeHref)
                        if (existingRaw != null) {
                            withContext(Dispatchers.Main) {
                                queueManager.updateSongInPlaylist(target.copy(rawCoverUrl = existingRaw))
                            }
                            continue
                        }

                        val meta =
                            org.melodist.data.WebDavManager
                                .extractPlaybackMetadata(server, target)
                        val versionedCover = meta.coverUrl?.let { "${it.substringBefore('?')}?t=${System.currentTimeMillis()}" }
                        withContext(Dispatchers.Main) {
                            val updated =
                                target.copy(
                                    coverUrl = versionedCover ?: target.coverUrl,
                                    rawCoverUrl = meta.rawCoverUrl ?: target.rawCoverUrl,
                                    currentTier = meta.inferredTier ?: target.currentTier,
                                )
                            queueManager.updateSongInPlaylist(updated)
                        }
                    } else if (isLocal) {
                        val rawCover =
                            org.melodist.data.LocalMusicManager
                                .ensureRawCover(target)
                        if (!rawCover.isNullOrBlank() && rawCover != target.rawCoverUrl) {
                            withContext(Dispatchers.Main) {
                                queueManager.updateSongInPlaylist(target.copy(rawCoverUrl = rawCover))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MelodistPlayback", "Error prefetching cover and metadata for ${target.name}", e)
                } finally {
                    prefetchingTargetMids.remove(target.songMid)
                }
            }
            appContext?.let { ctx ->
                CoverMemoryManager.trimMemoryWindow(
                    context = ctx,
                    prevSong = prev,
                    currSong = _currentSong.value,
                    nextSong = next,
                )
            }
        }
    }

    fun prefetchAdjacentWebDavCovers() = prefetchAdjacentCoversAndMetadata()

    private fun handleSongEnded() {
        _isTransitioning.value = true
        when {
            !queueManager.isRadioMode.value && !queueManager.hasPendingNextPlay && queueManager.loopMode.value == PlaybackLoopMode.SingleRepeat -> {
                _currentPositionMs.value = 0L
                _bufferedPositionMs.value = 0L
                exoPlayer?.seekTo(0L)
                exoPlayer?.play()
                _isTransitioning.value = false
            }
            else -> playNext()
        }
    }

    fun release() {
        appContext?.let { usbRouter.unregister(it) }
        savePlaybackState()
        progressTracker.stop()
        playJob?.cancel()
        prefetchJob?.cancel()
        prefetchedSongMid = null
        prefetchedUrlInfo = null
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
