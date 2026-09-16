package org.melodist.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.api.LyricParser
import org.melodist.api.MusicApiService
import org.melodist.api.QualityResult
import org.melodist.api.UserSession
import org.melodist.api.addSongToFavorite
import org.melodist.api.deleteSongFromFavorite
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.probeSongQualities
import org.melodist.data.UserLibraryCacheManager
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

    fun onInterceptSwitchTier(
        tier: AudioQualityTier,
    ): Boolean = false

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

    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null
    private var playJob: Job? = null
    private var lyricLoadJob: Job? = null
    private val shuffleQueue = ShuffleQueueManager()

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

    private val _paginationSource = MutableStateFlow<QueuePaginationSource?>(null)
    val paginationSource: StateFlow<QueuePaginationSource?> = _paginationSource.asStateFlow()

    private val _isLoadingMoreForQueue = MutableStateFlow(false)
    val isLoadingMoreForQueue: StateFlow<Boolean> = _isLoadingMoreForQueue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _preferredTier = MutableStateFlow(AudioQualityTier.HiRes)
    val preferredTier: StateFlow<AudioQualityTier> = _preferredTier.asStateFlow()

    private val _currentTier = MutableStateFlow(AudioQualityTier.Standard)
    val currentTier: StateFlow<AudioQualityTier> = _currentTier.asStateFlow()

    private val _currentTrackSpec = MutableStateFlow<AudioTrackSpec?>(null)
    val currentTrackSpec: StateFlow<AudioTrackSpec?> = _currentTrackSpec.asStateFlow()

    private val _favoriteSongMids = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongMids: StateFlow<Set<String>> = _favoriteSongMids.asStateFlow()

    private val _songFavoriteToggledEvent = MutableSharedFlow<Pair<Song, Boolean>>(extraBufferCapacity = 16)
    val songFavoriteToggledEvent: SharedFlow<Pair<Song, Boolean>> = _songFavoriteToggledEvent.asSharedFlow()

    private val _availableTiers = MutableStateFlow<Set<AudioQualityTier>>(emptySet())
    val availableTiers: StateFlow<Set<AudioQualityTier>> = _availableTiers.asStateFlow()

    private val _probedQualityOptions = MutableStateFlow<List<QualityOption>>(emptyList())
    val probedQualityOptions: StateFlow<List<QualityOption>> = _probedQualityOptions.asStateFlow()

    private val _isProbingQuality = MutableStateFlow(false)
    val isProbingQuality: StateFlow<Boolean> = _isProbingQuality.asStateFlow()

    private val _isSwitchingQuality = MutableStateFlow(false)
    val isSwitchingQuality: StateFlow<Boolean> = _isSwitchingQuality.asStateFlow()

    private var switchQualityJob: Job? = null
    private var probeJob: Job? = null
    private var probedSongMid: String? = null

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    private var prefetchedUrlInfo: Pair<String, QualityResult>? = null
    private var prefetchJob: Job? = null

    private val _isRemoteActive = MutableStateFlow(false)
    val isRemoteActive: StateFlow<Boolean> = _isRemoteActive.asStateFlow()

    private val _remoteDeviceName = MutableStateFlow<String?>(null)
    val remoteDeviceName: StateFlow<String?> = _remoteDeviceName.asStateFlow()

    private val _remotePrevSong = MutableStateFlow<Song?>(null)
    val remotePrevSong: StateFlow<Song?> = _remotePrevSong.asStateFlow()

    private val _remoteNextSong = MutableStateFlow<Song?>(null)
    val remoteNextSong: StateFlow<Song?> = _remoteNextSong.asStateFlow()

    fun setRemoteActive(active: Boolean, deviceName: String? = null) {
        _isRemoteActive.value = active
        _remoteDeviceName.value = deviceName
    }

    fun clearRemotePlayback() {
        if (!_isRemoteActive.value) return
        _isRemoteActive.value = false
        _remoteDeviceName.value = null
        _remotePrevSong.value = null
        _remoteNextSong.value = null
        _isPlaying.value = false
        _currentSong.value = null
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    val isLocalPlaybackActive: Boolean
        get() = exoPlayer?.playWhenReady == true && exoPlayer?.playbackState != androidx.media3.common.Player.STATE_IDLE

    fun shouldHoldForeground(): Boolean {
        if (_isRemoteActive.value && _currentSong.value != null) {
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

    private val _loopMode = MutableStateFlow(PlaybackLoopMode.ListRepeat)
    val loopMode: StateFlow<PlaybackLoopMode> = _loopMode.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRadioMode = MutableStateFlow(false)
    val isRadioMode: StateFlow<Boolean> = _isRadioMode.asStateFlow()

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
                if (!playing) {
                    savePlaybackProgress(exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _isLoading.value = false
                        _durationMs.value = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L
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

                if (current != null && fallback != null && !isLocalOrWebDavSong(current)) {
                    Log.w("MelodistPlayback", "Playback failed at tier $curTier, falling back to $fallback")
                    _errorMessage.value = "当前音质播放失败，已自动降级为 ${AudioQualityTier.getBadge(fallback)}"
                    scope.launch {
                        delay(500L)
                        switchTier(fallback)
                    }
                } else {
                    _errorMessage.value = "播放失败: ${error.localizedMessage}"
                    scope.launch {
                        delay(2000L)
                        playNext()
                    }
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
                                            val probedDepth = _probedQualityOptions.value.find { it.tier == currentTier }?.bitDepth ?: 0
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
                                    val probedSize = _probedQualityOptions.value.find { it.tier == _currentTier.value }?.sizeBytes ?: 0L
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

    private fun createRenderersFactory(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                val settings = org.melodist.data.AppSettingsManager.settings.value
                val isExclusive = settings.enableUsbExclusive
                val isPassthrough = settings.enableAudioPassthrough || isExclusive
                @Suppress("DEPRECATION")
                val audioCapabilities =
                    if (isPassthrough) {
                        AudioCapabilities.getCapabilities(context)
                    } else {
                        AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                    }
                @Suppress("DEPRECATION")
                val builder =
                    DefaultAudioSink
                        .Builder(context)
                        .setAudioCapabilities(audioCapabilities)
                        .setAudioProcessors(emptyArray())
                        .setAudioOffloadSupportProvider(
                            androidx.media3.exoplayer.audio
                                .DefaultAudioOffloadSupportProvider(context),
                        ).setEnableFloatOutput(if (isExclusive) true else enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(if (isExclusive) false else enableAudioTrackPlaybackParams)
                return builder.build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
    }

    private fun buildExoPlayer(context: Context): ExoPlayer {
        val audioAttributes =
            AudioAttributes
                .Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                .build()

        val httpDataSourceFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)

        val defaultDataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)
        val cachedDataSourceFactory =
            MelodistCacheManager.buildCacheDataSourceFactory(context.applicationContext, defaultDataSourceFactory)

        val mediaSourceFactory =
            DefaultMediaSourceFactory(context.applicationContext)
                .setDataSourceFactory(cachedDataSourceFactory)

        val isOffload = org.melodist.data.AppSettingsManager.settings.value.enableAudioOffload
        val offloadMode =
            if (isOffload) {
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
        val offloadPreferences =
            androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
                .Builder()
                .setAudioOffloadMode(offloadMode)
                .setIsGaplessSupportRequired(false)
                .setIsSpeedChangeSupportRequired(false)
                .build()

        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    // minBufferMs =
                    15_000,
                    // maxBufferMs =
                    45_000,
                    // bufferForPlaybackMs =
                    500,
                    // bufferForPlaybackAfterRebufferMs =
                    1_500,
                ).setPrioritizeTimeOverSizeThresholds(true)
                .build()

        return ExoPlayer
            .Builder(context.applicationContext, createRenderersFactory(context))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setAudioOffloadPreferences(offloadPreferences)
                        .build()
                addListener(playerListener)
                addAnalyticsListener(
                    object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                        override fun onAudioTrackInitialized(
                            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
                        ) {
                            Log.i(
                                "MelodistPlayback",
                                "AudioTrack initialized: rate=${audioTrackConfig.sampleRate}, enc=${audioTrackConfig.encoding}, ch=${audioTrackConfig.channelConfig}, offload=${audioTrackConfig.offload}",
                            )
                        }
                    },
                )
            }.also {
                updateUsbExclusiveRouting()
            }
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        init(context)
        return exoPlayer ?: synchronized(this) {
            exoPlayer ?: buildExoPlayer(context).also {
                exoPlayer = it
                startProgressLoop()
            }
        }
    }

    fun buildMediaMetadata(song: Song, remoteDeviceName: String? = null): MediaMetadata {
        val albumDesc = if (!remoteDeviceName.isNullOrBlank()) {
            val base = if (song.album.isNotBlank()) song.album else "单曲"
            "$base · 正在 $remoteDeviceName 播放"
        } else {
            song.album
        }

        val builder =
            MediaMetadata
                .Builder()
                .setTitle(song.name)
                .setArtist(song.singer)
                .setDisplayTitle(song.name)
                .setAlbumTitle(albumDesc)

        val coverUrl = song.coverUrl
        if (coverUrl.isNotBlank()) {
            builder.setArtworkUri(android.net.Uri.parse(coverUrl))
            if (coverUrl.startsWith("file://")) {
                try {
                    val file = java.io.File(coverUrl.removePrefix("file://").substringBefore('?'))
                    if (file.exists() && file.length() in 1..(2 * 1024 * 1024)) {
                        builder.setArtworkData(file.readBytes(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return builder.build()
    }

    private fun buildMediaItem(
        uri: android.net.Uri,
        song: Song,
    ): MediaItem =
        MediaItem
            .Builder()
            .setUri(uri)
            .setMediaId(song.songMid)
            .setMediaMetadata(buildMediaMetadata(song))
            .build()

    fun buildMediaItemForSong(song: Song, remoteDeviceName: String? = null): MediaItem {
        val uri = if (song.coverUrl.isNotBlank()) {
            try {
                android.net.Uri.parse(song.coverUrl)
            } catch (_: Exception) {
                android.net.Uri.EMPTY
            }
        } else {
            android.net.Uri.EMPTY
        }
        return MediaItem
            .Builder()
            .setUri(uri)
            .setMediaId(song.songMid)
            .setMediaMetadata(buildMediaMetadata(song, remoteDeviceName))
            .build()
    }

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
            MelodistCacheManager.init(context)
            org.melodist.data.AppSettingsManager.mediaCacheSizeProvider = { MelodistCacheManager.getCacheSizeBytes() }
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
            exoPlayer = buildExoPlayer(context)
            startProgressLoop()
        } else {
            updateUsbExclusiveRouting()
        }
        startPlaybackService(context)
    }

    fun applyAudioOffloadPreferences(enabled: Boolean) {
        val player = exoPlayer ?: return
        val offloadMode =
            if (enabled) {
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
        val offloadPreferences =
            androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
                .Builder()
                .setAudioOffloadMode(offloadMode)
                .setIsGaplessSupportRequired(false)
                .setIsSpeedChangeSupportRequired(false)
                .build()
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
        Log.i("MelodistPlayback", "Applied audio offload preferences: enabled=$enabled")
    }

    fun resetPlayerPipeline() {
        val context = appContext ?: return
        val player = exoPlayer
        val pos = player?.currentPosition ?: 0L
        val wasPlaying = player?.isPlaying == true
        val item = player?.currentMediaItem

        player?.release()
        exoPlayer =
            buildExoPlayer(context).apply {
                if (item != null) {
                    setMediaItem(item, pos)
                    prepare()
                    if (wasPlaying) {
                        play()
                    }
                }
            }
        updateUsbExclusiveRouting()
        Log.i("MelodistPlayback", "Player pipeline reset with updated audio configuration")
    }

    fun reloadAudioPipeline() {
        val player = exoPlayer ?: return
        val pos = player.currentPosition
        val wasPlaying = player.isPlaying
        val item = player.currentMediaItem ?: return
        player.setMediaItem(item, pos)
        player.prepare()
        if (wasPlaying) {
            player.play()
        }
        Log.i("MelodistPlayback", "Audio pipeline reloaded at position: $pos ms (playing: $wasPlaying)")
    }

    fun updateUsbExclusiveRouting(
        targetRate: Int = 0,
        channelCount: Int = 0,
        pcmEncoding: Int = 0,
    ) {
        usbRouter.updateUsbExclusiveRouting(
            context = appContext,
            player = exoPlayer,
            isUsbExclusive = org.melodist.data.AppSettingsManager.settings.value.enableUsbExclusive,
            targetRate = targetRate,
            channelCount = channelCount,
            pcmEncoding = pcmEncoding,
        )
    }

    @Suppress("UnusedParameter")
    fun onAudioPassthroughChanged(enabled: Boolean) {
        // 仅更新配置，在下一次起播时生效
    }

    private fun triggerPrefetchNextSong() {
        if (prefetchJob?.isActive == true) return
        val nextSong = getNextSong() ?: return
        if (nextSong.songMid == prefetchedUrlInfo?.first) return
        if (isLocalOrWebDavSong(nextSong)) return

        prefetchJob =
            scope.launch(Dispatchers.IO) {
                try {
                    val rawTargetTier = _preferredTier.value
                    val targetTier = clampCellularTier(rawTargetTier, nextSong)
                    val playUrlInfo = apiService.getPlayUrl(nextSong.songMid, mediaMid = nextSong.mediaMid, preferredTier = targetTier)
                    if (!playUrlInfo.url.isNullOrBlank()) {
                        prefetchedUrlInfo = Pair(nextSong.songMid, playUrlInfo)
                        Log.i("MelodistPlayback", "Prefetched next song URL: ${nextSong.name}, tier: ${playUrlInfo.tier}")
                    }
                } catch (e: Exception) {
                    Log.w("MelodistPlayback", "Prefetch next song failed", e)
                }
            }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob =
            scope.launch {
                var saveCounter = 0
                var prefetchCounter = 0
                while (isActive) {
                    exoPlayer?.let { player ->
                        if (player.isPlaying) {
                            val pos = player.currentPosition.coerceAtLeast(0L)
                            val dur = player.duration
                            _currentPositionMs.value = pos
                            if (dur > 0L) {
                                _durationMs.value = dur
                            }
                            saveCounter++
                            if (saveCounter >= 250) {
                                saveCounter = 0
                                savePlaybackProgress(_currentPositionMs.value)
                            }
                            prefetchCounter++
                            if (prefetchCounter >= 16) {
                                prefetchCounter = 0
                                if (dur > 20_000L && (dur - pos <= 15_000L || (pos.toDouble() / dur) >= 0.85)) {
                                    triggerPrefetchNextSong()
                                }
                            }
                        }
                    }
                    delay(60L)
                }
            }
    }

    private fun getPrefs() = appContext?.getSharedPreferences("melodist_playback_prefs", Context.MODE_PRIVATE)

    fun savePlaybackState() {
        val prefs = getPrefs() ?: return
        try {
            val currSong = _currentSong.value
            val list = _playlist.value
            val favs = _favoriteSongMids.value
            prefs.edit().apply {
                if (currSong != null) {
                    putString("current_song", jsonHelper.encodeToString(currSong))
                }
                if (list.isNotEmpty()) {
                    putString("playback_queue", jsonHelper.encodeToString(list))
                }
                putInt("current_index", _currentIndex.value)
                putLong("current_position_ms", _currentPositionMs.value)
                putLong("duration_ms", _durationMs.value)
                putString("preferred_tier", _preferredTier.value.name)
                putString("loop_mode", _loopMode.value.name)
                putString("shuffled_indices", shuffleQueue.serialize())
                putInt("shuffled_pointer", shuffleQueue.pointer)
                putString("favorite_song_mids", jsonHelper.encodeToString(favs))
                putBoolean("is_radio_mode", _isRadioMode.value)
                apply()
            }
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to save playback state", e)
        }
    }

    fun savePlaybackProgress(posMs: Long = _currentPositionMs.value) {
        val prefs = getPrefs() ?: return
        try {
            prefs.edit().putLong("current_position_ms", posMs).apply()
        } catch (_: Exception) {
        }
    }

    private fun sanitizeSongCover(song: Song): Song {
        val url = song.coverUrl
        if (url.startsWith("file://")) {
            val path = url.removePrefix("file://")
            val file = java.io.File(path)
            if (!file.exists() || file.length() == 0L) {
                return song.copy(coverUrl = "")
            }
        }
        return song
    }

    private fun restorePlaybackState() {
        val prefs = getPrefs() ?: return
        try {
            val settingsTier = org.melodist.data.AppSettingsManager.settings.value.preferredQualityTier
            _preferredTier.value = settingsTier

            val modeName = prefs.getString("loop_mode", null)
            if (modeName != null) {
                try {
                    _loopMode.value = PlaybackLoopMode.valueOf(modeName)
                } catch (_: Exception) {
                }
            }
            _isRadioMode.value = prefs.getBoolean("is_radio_mode", false)

            val favJson = prefs.getString("favorite_song_mids", null)
            if (!favJson.isNullOrBlank()) {
                try {
                    val favSet = jsonHelper.decodeFromString<Set<String>>(favJson)
                    _favoriteSongMids.value = favSet
                } catch (_: Exception) {
                }
            }

            val queueJson = prefs.getString("playback_queue", null)
            if (!queueJson.isNullOrBlank()) {
                try {
                    val queue = jsonHelper.decodeFromString<List<Song>>(queueJson).map { sanitizeSongCover(it) }
                    _playlist.value = queue
                    val shufStr = prefs.getString("shuffled_indices", null)
                    val shufPointer = prefs.getInt("shuffled_pointer", 0)
                    shuffleQueue.restore(shufStr, shufPointer, queue.size)
                } catch (_: Exception) {
                }
            }

            _currentIndex.value = prefs.getInt("current_index", -1)

            val songJson = prefs.getString("current_song", null)
            if (!songJson.isNullOrBlank()) {
                try {
                    val rawSong = jsonHelper.decodeFromString<Song>(songJson)
                    val song = sanitizeSongCover(rawSong)
                    _currentSong.value = song
                    _currentTier.value = song.currentTier
                } catch (_: Exception) {
                }
            }

            _currentPositionMs.value = prefs.getLong("current_position_ms", 0L)
            _durationMs.value = prefs.getLong("duration_ms", 0L)
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to restore playback state", e)
        }
    }

    fun setFavoriteSongMids(mids: Set<String>) {
        if (mids.isEmpty()) return
        _favoriteSongMids.value = _favoriteSongMids.value + mids
        savePlaybackState()
    }

    fun addFavoriteSongMids(mids: Collection<String>) {
        if (mids.isEmpty()) return
        val validMids = mids.filter { it.isNotBlank() }
        if (validMids.isEmpty()) return
        _favoriteSongMids.value = _favoriteSongMids.value + validMids
        savePlaybackState()
    }

    @Volatile
    private var isSyncingFavorites = false

    /**
     * 同步用户收藏歌曲列表
     */
    fun syncFavoriteSongsAsync(forceRefresh: Boolean = false) {
        if (!UserSession.isLoggedIn) return
        if (isSyncingFavorites) return
        isSyncingFavorites = true

        scope.launch(Dispatchers.IO) {
            try {
                var page = 1
                var hasMore = true
                val allFavMids = mutableSetOf<String>()
                while (hasMore && UserSession.isLoggedIn && page <= 50) {
                    val result = apiService.getFavoriteSongsDetail(page = page, pageSize = 100)
                    if (result.songs.isNotEmpty()) {
                        val mids = result.songs.mapNotNull { it.songMid.takeIf { m -> m.isNotBlank() } }
                        allFavMids.addAll(mids)
                        _favoriteSongMids.value = _favoriteSongMids.value + mids
                        hasMore = result.hasMore && (allFavMids.size < result.total)
                        page++
                    } else {
                        hasMore = false
                    }
                }
                if (allFavMids.isNotEmpty()) {
                    if (forceRefresh) {
                        _favoriteSongMids.value = allFavMids
                    } else {
                        _favoriteSongMids.value = _favoriteSongMids.value + allFavMids
                    }
                    savePlaybackState()
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Failed to sync favorite songs: $e")
            } finally {
                isSyncingFavorites = false
            }
        }
    }

    fun isSongFavoriteSupported(song: Song?): Boolean {
        if (song == null) return false
        // WebDAV 歌曲或本地音乐不支持红心收藏
        if (song.songMid.startsWith("webdav_") || !song.localFilePath.isNullOrBlank()) {
            return false
        }
        return true
    }

    fun isSongFavorite(songMid: String?): Boolean {
        if (songMid == null || songMid.startsWith("webdav_")) return false
        return _favoriteSongMids.value.contains(songMid)
    }

    fun toggleSongFavorite(song: Song) {
        if (!isSongFavoriteSupported(song)) {
            val ctx = appContext
            if (ctx != null) {
                val msg = if (song.songMid.startsWith("webdav_")) {
                    "WebDAV 音乐不支持收藏"
                } else {
                    "本地音乐不支持收藏"
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        val mid = song.songMid
        val id = song.songId
        val isFav = _favoriteSongMids.value.contains(mid)
        val willBeFav = !isFav
        val updated =
            if (isFav) {
                _favoriteSongMids.value - mid
            } else {
                _favoriteSongMids.value + mid
            }
        _favoriteSongMids.value = updated
        savePlaybackState()
        _songFavoriteToggledEvent.tryEmit(song to willBeFav)

        scope.launch(Dispatchers.IO) {
            try {
                if (isFav) {
                    apiService.deleteSongFromFavorite(id, mid)
                } else {
                    apiService.addSongToFavorite(id, mid)
                }
                UserLibraryCacheManager.onFavoriteToggled(song, willBeFav)
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Failed to sync favorite to cloud: $e")
            }
        }
    }

    fun toggleCurrentSongFavorite() {
        val song = _currentSong.value ?: return
        toggleSongFavorite(song)
    }

    @Volatile
    private var isFetchingMoreRadio = false

    private fun checkPrefetchRadioSongs() {
        if (!_isRadioMode.value || isFetchingMoreRadio) return
        val currentList = _playlist.value
        if (_currentIndex.value >= currentList.size - 3) {
            isFetchingMoreRadio = true
            scope.launch(Dispatchers.IO) {
                try {
                    val moreSongs = apiService.getGuessRecommendSongs(count = 15)
                    if (moreSongs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            appendPlaylist(moreSongs)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    isFetchingMoreRadio = false
                }
            }
        }
    }

    fun setPlaylist(
        songs: List<Song>,
        startIndex: Int = 0,
        isRadio: Boolean = false,
        initialSeekToMs: Long = 0L,
        startMuted: Boolean = false,
        forceTier: AudioQualityTier? = null,
        paginationSource: QueuePaginationSource? = null,
    ) {
        _isRadioMode.value = isRadio
        _paginationSource.value = paginationSource
        _playlist.value = songs
        if (songs.isNotEmpty() && startIndex in songs.indices) {
            _currentIndex.value = startIndex
            if (!isRadio && _loopMode.value == PlaybackLoopMode.Shuffle) {
                shuffleQueue.reset(songs.size, startIndex, songs)
            }
            setMuted(startMuted)
            playSong(songs[startIndex], forceTier = forceTier, seekToMs = initialSeekToMs)
        }
        savePlaybackState()
    }

    fun setPaginationSource(source: QueuePaginationSource?) {
        _paginationSource.value = source
    }

    suspend fun loadMoreForQueue(): Boolean {
        val source = _paginationSource.value ?: return false
        if (!source.hasMore || source.isLoadingMore || _isLoadingMoreForQueue.value) return false
        _isLoadingMoreForQueue.value = true
        return try {
            val newSongs = source.loadMore()
            if (newSongs.isNotEmpty()) {
                appendPlaylist(newSongs)
                true
            } else {
                false
            }
        } finally {
            _isLoadingMoreForQueue.value = false
        }
    }

    fun appendPlaylist(newSongs: List<Song>) {
        if (newSongs.isEmpty()) return
        val current = _playlist.value
        val existingMids = current.map { it.songMid }.toSet()
        val toAdd = newSongs.filter { it.songMid.isNotBlank() && !existingMids.contains(it.songMid) }
        if (toAdd.isNotEmpty()) {
            val updated = current + toAdd
            _playlist.value = updated
            if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
                shuffleQueue.syncTo(_currentIndex.value, updated.size, updated)
            }
            savePlaybackState()
        }
    }

    fun insertNextPlay(song: Song) {
        if (song.songMid.isBlank()) return
        val current = _playlist.value.toMutableList()
        if (current.isEmpty()) {
            setPlaylist(listOf(song), startIndex = 0)
            return
        }
        val curIdx = _currentIndex.value
        val insertPos = (curIdx + 1).coerceIn(0, current.size)
        // 若队列中已有该歌曲，且不在当前位置之后，先移除旧位置再插入
        val existingIndex = current.indexOfFirst { it.songMid == song.songMid }
        if (existingIndex != -1) {
            current.removeAt(existingIndex)
            val adjustedPos = if (existingIndex < insertPos) (insertPos - 1).coerceAtLeast(0) else insertPos
            current.add(adjustedPos, song)
        } else {
            current.add(insertPos, song)
        }
        _playlist.value = current
        savePlaybackState()
    }

    fun insertAndPlay(
        song: Song,
        seekToMs: Long = 0L,
    ) {
        if (song.songMid.isBlank()) return
        val current = _playlist.value.toMutableList()
        if (current.isEmpty()) {
            setPlaylist(listOf(song), startIndex = 0, initialSeekToMs = seekToMs)
            return
        }
        val existingIndex = current.indexOfFirst { it.songMid == song.songMid }
        if (existingIndex != -1) {
            _currentIndex.value = existingIndex
            playSong(current[existingIndex], seekToMs = seekToMs)
        } else {
            val curIdx = _currentIndex.value
            val insertPos = (curIdx + 1).coerceIn(0, current.size)
            current.add(insertPos, song)
            _playlist.value = current
            _currentIndex.value = insertPos
            playSong(song, seekToMs = seekToMs)
        }
    }

    fun removeFromPlaylist(index: Int) {
        val list = _playlist.value.toMutableList()
        if (index !in list.indices) return
        val isCurrent = index == _currentIndex.value
        list.removeAt(index)
        _playlist.value = list
        if (list.isEmpty()) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            _currentSong.value = null
            _currentIndex.value = -1
            _lyrics.value = emptyList()
            _isPlaying.value = false
        } else if (isCurrent) {
            val nextIndex = index.coerceAtMost(list.lastIndex)
            _currentIndex.value = nextIndex
            playSong(list[nextIndex])
        } else if (index < _currentIndex.value) {
            _currentIndex.value = _currentIndex.value - 1
        }
        if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
            shuffleQueue.syncTo(_currentIndex.value, list.size, list)
        }
        savePlaybackState()
    }

    fun removeFromPlaylist(songs: List<Song>) {
        if (songs.isEmpty()) return
        val current = _playlist.value.toMutableList()
        val removeMids = songs.map { it.songMid }.toSet()
        val currentPlayingMid = _currentSong.value?.songMid
        val isCurrentRemoved = currentPlayingMid != null && removeMids.contains(currentPlayingMid)

        val remaining = current.filterNot { removeMids.contains(it.songMid) }
        _playlist.value = remaining
        if (remaining.isEmpty()) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            _currentSong.value = null
            _currentIndex.value = -1
            _lyrics.value = emptyList()
            _isPlaying.value = false
        } else if (isCurrentRemoved) {
            val newIndex = 0
            _currentIndex.value = newIndex
            playSong(remaining[newIndex])
        } else {
            val newCurSong = _currentSong.value
            val newIdx = remaining.indexOfFirst { it.songMid == newCurSong?.songMid }
            _currentIndex.value = if (newIdx != -1) newIdx else 0
        }
        if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
            shuffleQueue.syncTo(_currentIndex.value, remaining.size, remaining)
        }
        savePlaybackState()
    }

    fun clearPlaylist() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _paginationSource.value = null
        _playlist.value = emptyList()
        _currentSong.value = null
        _currentIndex.value = -1
        _lyrics.value = emptyList()
        _isPlaying.value = false
        savePlaybackState()
    }

    fun playSong(
        song: Song,
        forceTier: AudioQualityTier? = null,
        seekToMs: Long = 0L,
    ) {
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
            _currentTier.value = forceTier ?: _preferredTier.value
            _isPlaying.value = true
            _isTransitioning.value = false
            savePlaybackState()
            loadLyricsForSong(effectiveSong)
            return
        }
        _currentSong.value = effectiveSong
        _isTransitioning.value = true
        updateCurrentMediaMetadata(effectiveSong)
        org.melodist.data.RecentPlaybackManager
            .recordSong(effectiveSong)
        _currentTrackSpec.value = null
        val list = _playlist.value
        val foundIndex = list.indexOfFirst { it.songMid == song.songMid }
        if (foundIndex != -1) {
            _currentIndex.value = foundIndex
            if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
                shuffleQueue.syncTo(foundIndex, list.size, list)
            }
        }
        checkPrefetchRadioSongs()
        _isLoading.value = true
        _errorMessage.value = null
        _lyrics.value = emptyList()
        _currentPositionMs.value = seekToMs
        probeJob?.cancel()
        val isLocalOrWebDav = song.songMid.startsWith("webdav_") || !song.localFilePath.isNullOrBlank()
        if (isLocalOrWebDav) {
            val actualTier = song.currentTier
            _availableTiers.value = setOf(actualTier)
            val localPath = song.localFilePath
            val localSize =
                if (!localPath.isNullOrBlank()) {
                    try {
                        java.io.File(localPath).length()
                    } catch (_: Exception) {
                        0L
                    }
                } else {
                    0L
                }
            _probedQualityOptions.value =
                listOf(
                    QualityOption(
                        tier = actualTier,
                        format = localPath?.substringAfterLast('.', "")?.uppercase()?.ifBlank { "FLAC" } ?: "FLAC",
                        bitrate = "",
                        sizeBytes = localSize,
                        isAvailable = true,
                    ),
                )
            probedSongMid = song.songMid
        } else {
            _availableTiers.value = emptySet()
            _probedQualityOptions.value = emptyList()
            probedSongMid = null
            launchProbeJob(song, delayMs = 3000L)
            launchCoverHealJob(song)
        }

        playJob =
            scope.launch {
                val isPureLocal = !song.localFilePath.isNullOrBlank() && !song.songMid.startsWith("webdav_")
                if (isPureLocal) {
                    val player = exoPlayer ?: return@launch
                    val directFile = java.io.File(song.localFilePath!!)
                    if (directFile.exists() && directFile.isFile) {
                        val lyricDeferred =
                            async(Dispatchers.IO) {
                                try {
                                    val lrcText =
                                        org.melodist.data.LocalMusicManager
                                            .getSongLyrics(song)
                                    if (!lrcText.isNullOrBlank()) {
                                        LyricParser.parseMergedLyrics(lrcText, null)
                                    } else {
                                        emptyList()
                                    }
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            }

                        _currentTier.value = song.currentTier
                        val mediaItem = buildMediaItem(android.net.Uri.fromFile(directFile), song)
                        if (seekToMs > 0L) {
                            player.setMediaItem(mediaItem, seekToMs)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
                        player.prepare()
                        player.play()
                        savePlaybackState()

                        val baseLyrics = lyricDeferred.await()
                        _lyrics.value = baseLyrics

                        // 后台智能匹配官方逐行歌词与中文双语翻译（切片指纹识别与文本双轨，私有缓存隔离落盘）
                        launch(Dispatchers.IO) {
                            try {
                                val matched = LocalLyricAutoMatcher.matchLyricsAsync(song, directFile, baseLyrics)
                                if (matched != null && matched.isNotEmpty() && _currentSong.value?.songId == song.songId) {
                                    Log.i(
                                        "MelodistPlayback",
                                        "Applied auto-matched official lyrics for local song: ${song.name} (lines=${matched.size})",
                                    )
                                    _lyrics.value = matched
                                }
                            } catch (e: Exception) {
                                Log.w("MelodistPlayback", "Error auto-matching lyrics for local song", e)
                            }
                        }

                        return@launch
                    }
                }

                if (song.songMid.startsWith("webdav_") || !song.localFilePath.isNullOrBlank()) {
                    val server =
                        org.melodist.data.WebDavManager
                            .getActiveServer()
                    val player = exoPlayer ?: return@launch

                    // 异步装载 WebDAV 歌词（内嵌/同名LRC）
                    val lyricDeferred =
                        async(Dispatchers.IO) {
                            try {
                                val lrcText =
                                    org.melodist.data.WebDavManager
                                        .getSongLyrics(song)
                                if (!lrcText.isNullOrBlank()) {
                                    LyricParser.parseMergedLyrics(lrcText, null)
                                } else {
                                    emptyList()
                                }
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }

                    _currentTier.value = AudioQualityTier.SQ
                    val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
                    if (server != null && relativeHref.isNotBlank() && _currentSong.value?.coverUrl.isNullOrBlank()) {
                        val existingCover =
                            org.melodist.data.WebDavManager
                                .getSongCoverPath(server.id, relativeHref)
                        if (!existingCover.isNullOrBlank()) {
                            _currentSong.value = _currentSong.value?.copy(coverUrl = existingCover)
                        }
                    }
                    val localFile =
                        if (server != null && relativeHref.isNotBlank()) {
                            org.melodist.data.WebDavManager
                                .getLocalCacheFile(server.id, relativeHref)
                        } else {
                            null
                        }

                    if (localFile != null && localFile.exists() && localFile.length() > 0L) {
                        Log.i("MelodistPlayback", "Playing WebDAV song from local cache: ${song.name}, file=${localFile.absolutePath}")
                        val mediaItem = buildMediaItem(android.net.Uri.fromFile(localFile), song)
                        if (seekToMs > 0L) {
                            player.setMediaItem(mediaItem, seekToMs)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
                    } else if (server != null && relativeHref.isNotBlank()) {
                        val (streamUrl, authHeader) =
                            org.melodist.data.WebDavManager
                                .resolvePlaybackUrl(server, relativeHref)
                        Log.i(
                            "MelodistPlayback",
                            "Playing WebDAV song from stream: ${song.name}, streamUrl=$streamUrl, hasAuth=${!authHeader.isNullOrBlank()}",
                        )
                        val baseHttpFactory =
                            DefaultHttpDataSource
                                .Factory()
                                .setUserAgent("MelodistTV/1.0 ExoPlayer")
                                .setAllowCrossProtocolRedirects(true)
                        if (!authHeader.isNullOrBlank()) {
                            baseHttpFactory.setDefaultRequestProperties(mapOf("Authorization" to authHeader))
                        }
                        val ctx = appContext ?: return@launch
                        val dataSourceFactory = DefaultDataSource.Factory(ctx, baseHttpFactory)
                        val cachedDataSourceFactory = MelodistCacheManager.buildCacheDataSourceFactory(ctx, dataSourceFactory)
                        val mediaSource =
                            ProgressiveMediaSource
                                .Factory(cachedDataSourceFactory)
                                .createMediaSource(buildMediaItem(android.net.Uri.parse(streamUrl), song))
                        if (seekToMs > 0L) {
                            player.setMediaSource(mediaSource, seekToMs)
                        } else {
                            player.setMediaSource(mediaSource)
                        }
                    }

                    player.prepare()
                    player.play()
                    savePlaybackState()

                    val baseLyrics = lyricDeferred.await()
                    _lyrics.value = baseLyrics

                    // 后台智能匹配官方逐行歌词与中文双语翻译（切片指纹识别与文本双轨，私有缓存隔离落盘）
                    launch(Dispatchers.IO) {
                        try {
                            val matched = LocalLyricAutoMatcher.matchLyricsAsync(song, localFile, baseLyrics)
                            if (matched != null && matched.isNotEmpty() && _currentSong.value?.songId == song.songId) {
                                Log.i(
                                    "MelodistPlayback",
                                    "Applied auto-matched official lyrics for WebDAV song: ${song.name} (lines=${matched.size})",
                                )
                                _lyrics.value = matched
                            }
                        } catch (e: Exception) {
                            Log.w("MelodistPlayback", "Error auto-matching lyrics for WebDAV song", e)
                        }
                    }

                    // 后台异步提取并缓存 WebDAV 内嵌专辑封面与音质信息
                    launch(Dispatchers.IO) {
                        try {
                            if (server != null) {
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
                                        Log.i(
                                            "MelodistPlayback",
                                            "Loaded WebDAV metadata: cover=$versionedCover, tier=$newTier for ${song.name}",
                                        )
                                        withContext(Dispatchers.Main) {
                                            if (_currentSong.value?.songMid == song.songMid) {
                                                val updated =
                                                    _currentSong.value?.copy(
                                                        coverUrl = versionedCover,
                                                        currentTier = newTier ?: _currentSong.value?.currentTier ?: AudioQualityTier.SQ,
                                                    )
                                                _currentSong.value = updated
                                                if (newTier != null) {
                                                    _currentTier.value = newTier
                                                    _availableTiers.value = setOf(newTier)
                                                }
                                                if (updated != null) {
                                                    val currentList = _playlist.value
                                                    val idx = currentList.indexOfFirst { it.songMid == song.songMid }
                                                    if (idx >= 0) {
                                                        val mutable = currentList.toMutableList()
                                                        mutable[idx] = updated
                                                        _playlist.value = mutable
                                                    }
                                                    updateCurrentMediaMetadata(updated)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MelodistPlayback", "Error extracting WebDAV song metadata", e)
                        }
                    }

                    return@launch
                }

                val lyricDeferred =
                    async(Dispatchers.IO) {
                        try {
                            apiService.getLyrics(song.songMid, song.songId, songName = song.name, singer = song.singer)
                        } catch (e: Exception) {
                            Log.w("MelodistPlayback", "Error loading lyrics for ${song.name}", e)
                            emptyList()
                        }
                    }

                val isPassthrough = org.melodist.data.AppSettingsManager.settings.value.enableAudioPassthrough
                val rawTargetTier = forceTier ?: _preferredTier.value
                val targetTier = clampCellularTier(rawTargetTier, song)
                val cachedInfo =
                    if (prefetchedUrlInfo?.first == song.songMid && forceTier == null) {
                        val info = prefetchedUrlInfo?.second
                        prefetchedUrlInfo = null
                        info
                    } else {
                        null
                    }

                val urlDeferred =
                    if (cachedInfo == null) {
                        async(Dispatchers.IO) {
                            try {
                                apiService.getPlayUrl(song.songMid, mediaMid = song.mediaMid, preferredTier = targetTier)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } else {
                        null
                    }

                val lyricList = lyricDeferred.await()
                _lyrics.value = lyricList

                val playUrlInfo = cachedInfo ?: urlDeferred?.await()
                if (playUrlInfo != null && !playUrlInfo.url.isNullOrBlank()) {
                    val rawUrl = playUrlInfo.url
                    Log.i(
                        "MelodistPlayback",
                        "Preparing playback with URL: $rawUrl, tier: ${playUrlInfo.tier} (preferred: $targetTier, passthrough: $isPassthrough, fromPrefetch=${cachedInfo != null})",
                    )
                    _currentTier.value = playUrlInfo.tier
                    val player = exoPlayer ?: return@launch
                    val mediaItem = buildMediaItem(android.net.Uri.parse(rawUrl), song)
                    if (seekToMs > 0L) {
                        player.setMediaItem(mediaItem, seekToMs)
                    } else {
                        player.setMediaItem(mediaItem)
                    }
                    player.volume = if (_isMuted.value) 0f else targetVolume
                    player.prepare()
                    player.play()
                    _isTransitioning.value = false
                    savePlaybackState()
                } else {
                    _isLoading.value = false
                    _isTransitioning.value = false
                    Log.w("MelodistPlayback", "Failed to obtain playback URL for songMid=${song.songMid}, preferredTier=$targetTier")
                    _errorMessage.value = "无法获取播放直链 (需 VIP 或版权限制)"
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
                if (player.currentMediaItem == null && currSong != null) {
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
        if (player.isPlaying) {
            player.pause()
            savePlaybackProgress(player.currentPosition.coerceAtLeast(0L))
        }
    }

    fun play() {
        if (playbackInterceptor?.onInterceptResume() == true) return
        val player = exoPlayer ?: return
        if (!player.isPlaying) {
            val currSong = _currentSong.value
            if (player.currentMediaItem == null && currSong != null) {
                playSong(currSong, seekToMs = _currentPositionMs.value)
            } else {
                if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                    player.prepare()
                }
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
        playJob?.cancel()
        switchQualityJob?.cancel()
        _isSwitchingQuality.value = false
        appContext?.let { startPlaybackService(it) }

        _currentSong.value = song
        _isTransitioning.value = true
        updateCurrentMediaMetadata(song)
        org.melodist.data.RecentPlaybackManager.recordSong(song)
        _currentTrackSpec.value = null

        val list = _playlist.value
        val foundIndex = list.indexOfFirst { it.songMid == song.songMid }
        if (foundIndex != -1) {
            _currentIndex.value = foundIndex
        }

        _isLoading.value = true
        _errorMessage.value = null
        _lyrics.value = emptyList()
        _currentPositionMs.value = seekToMs

        val player = exoPlayer ?: return
        val baseHttpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MelodistTV/1.0 ConnectStream")
            .setAllowCrossProtocolRedirects(true)
        if (headers.isNotEmpty()) {
            baseHttpFactory.setDefaultRequestProperties(headers)
        }
        val ctx = appContext ?: return
        val dataSourceFactory = DefaultDataSource.Factory(ctx, baseHttpFactory)
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
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
    ) {
        val prevLocalSong = _currentSong.value
        if (song != null) {
            _currentSong.value = song
            if (prevLocalSong?.songMid != song.songMid) {
                loadLyricsForSong(song)
            }
            if (currentIndex >= 0 && currentIndex < _playlist.value.size) {
                _currentIndex.value = currentIndex
            } else {
                val idx = _playlist.value.indexOfFirst { it.songMid == song.songMid }
                if (idx >= 0) {
                    _currentIndex.value = idx
                }
            }
        }
        _remotePrevSong.value = prevSong
        _remoteNextSong.value = nextSong
        if (currentTier != null) {
            _currentTier.value = currentTier
        }
        if (availableTiers.isNotEmpty()) {
            _availableTiers.value = availableTiers
        }
        if (!loopModeName.isNullOrBlank()) {
            try {
                _loopMode.value = PlaybackLoopMode.valueOf(loopModeName)
            } catch (_: Throwable) {}
        }
        _isPlaying.value = isPlaying
        _currentPositionMs.value = positionMs
        if (durationMs > 0L) {
            _durationMs.value = durationMs
        }
    }

    fun syncRemoteQueue(queue: List<Song>, currentIndex: Int) {
        if (queue.isNotEmpty()) {
            _playlist.value = queue
            if (currentIndex in queue.indices) {
                _currentIndex.value = currentIndex
            }
        }
    }

    fun loadLyricsForSong(song: Song) {
        lyricLoadJob?.cancel()
        _lyrics.value = emptyList()
        lyricLoadJob = scope.launch(Dispatchers.IO) {
            try {
                if (song.songMid.startsWith("webdav_")) {
                    val lrcText = org.melodist.data.WebDavManager.getSongLyrics(song)
                    val baseLyrics = if (!lrcText.isNullOrBlank()) {
                        LyricParser.parseMergedLyrics(lrcText, null)
                    } else {
                        emptyList()
                    }
                    if (_currentSong.value?.songMid == song.songMid && baseLyrics.isNotEmpty()) {
                        _lyrics.value = baseLyrics
                    }
                    // 智能匹配官方逐行歌词与双语翻译
                    val matched = LocalLyricAutoMatcher.matchLyricsAsync(song, null, baseLyrics)
                    if (matched != null && matched.isNotEmpty() && _currentSong.value?.songMid == song.songMid) {
                        Log.i("MelodistPlayback", "Applied auto-matched lyrics for WebDAV song: ${song.name}")
                        _lyrics.value = matched
                    }
                } else if (!song.localFilePath.isNullOrBlank() || song.isLocal) {
                    val lrcText = org.melodist.data.LocalMusicManager.getSongLyrics(song)
                    val baseLyrics = if (!lrcText.isNullOrBlank()) {
                        LyricParser.parseMergedLyrics(lrcText, null)
                    } else {
                        emptyList()
                    }
                    if (_currentSong.value?.songMid == song.songMid && baseLyrics.isNotEmpty()) {
                        _lyrics.value = baseLyrics
                    }
                    val path = song.localFilePath
                    val directFile = if (!path.isNullOrBlank()) java.io.File(path) else null
                    val validFile = if (directFile != null && directFile.exists() && directFile.isFile) directFile else null
                    val matched = LocalLyricAutoMatcher.matchLyricsAsync(song, validFile, baseLyrics)
                    if (matched != null && matched.isNotEmpty() && _currentSong.value?.songMid == song.songMid) {
                        Log.i("MelodistPlayback", "Applied auto-matched lyrics for local song: ${song.name}")
                        _lyrics.value = matched
                    }
                } else {
                    val onlineLyrics = apiService.getLyrics(
                        song.songMid,
                        song.songId,
                        songName = song.name,
                        singer = song.singer,
                    )
                    if (_currentSong.value?.songMid == song.songMid) {
                        _lyrics.value = onlineLyrics
                    }
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Error loading lyrics for ${song.name}", e)
            }
        }
    }

    fun isCellularNetwork(): Boolean = PlaybackSourceResolver.isCellularNetwork(appContext)

    fun isLocalOrWebDavSong(song: Song?): Boolean = PlaybackSourceResolver.isLocalOrWebDavSong(song)

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
                    val playUrlInfo =
                        withContext(Dispatchers.IO) {
                            try {
                                apiService.getPlayUrl(current.songMid, mediaMid = current.mediaMid, preferredTier = effectiveTier)
                            } catch (e: Exception) {
                                Log.w("MelodistPlayback", "Failed to fetch play url for quality $effectiveTier: ${e.message}", e)
                                null
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
                        val player = exoPlayer ?: return@launch

                        // 在新媒体就绪并即将注入播放器的瞬间，抓取最新实时播放进度，消除位置断层与回跳
                        val livePositionMs =
                            if (isPlayerValid) {
                                player.currentPosition.takeIf { it > 0L } ?: _currentPositionMs.value
                            } else {
                                _currentPositionMs.value
                            }

                        player.volume = if (_isMuted.value) 0f else targetVolume
                        val mediaItem = buildMediaItem(android.net.Uri.parse(rawUrl), current)
                        if (livePositionMs > 0L) {
                            player.setMediaItem(mediaItem, livePositionMs)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
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
        val song = _currentSong.value ?: return
        val isLocalOrWebDav = song.songMid.startsWith("webdav_") || !song.localFilePath.isNullOrBlank()
        if (isLocalOrWebDav) return
        if (probedSongMid == song.songMid && _probedQualityOptions.value.isNotEmpty()) return
        probeJob?.cancel()
        launchProbeJob(song, delayMs = 0L)
    }

    private fun launchProbeJob(
        song: Song,
        delayMs: Long,
    ) {
        probeJob =
            scope.launch(Dispatchers.IO) {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                _isProbingQuality.value = true
                try {
                    val probed = apiService.probeSongQualities(song.songMid, song.mediaMid)
                    val available = probed.filter { it.isAvailable }.map { it.tier }.toSet()
                    if (available.isNotEmpty()) {
                        _availableTiers.value = available
                        _probedQualityOptions.value = probed
                        probedSongMid = song.songMid
                        val curSpec = _currentTrackSpec.value
                        if (curSpec != null && curSpec.bitrateKbps <= 0 && song.durationSeconds > 0) {
                            val curSize = probed.find { it.tier == _currentTier.value }?.sizeBytes ?: 0L
                            if (curSize > 0L) {
                                val calcKbps = ((curSize * 8L) / 1024L / song.durationSeconds).toInt()
                                if (calcKbps > 0) {
                                    _currentTrackSpec.value = curSpec.copy(bitrateKbps = calcKbps)
                                }
                            }
                        }

                        // 自动平滑升级：若当前音质不同于用户偏好音质（如听歌识曲初始加载 HQ），且偏好音质可用，自动异步无缝切换
                        val preferred = _preferredTier.value
                        val effective = clampCellularTier(preferred, song)
                        if (_currentTier.value != effective && available.contains(effective) && _currentSong.value?.songMid == song.songMid) {
                            Log.i("MelodistPlayback", "Auto-upgrading to preferred tier ${AudioQualityTier.getBadge(effective)} after probe for ${song.name}")
                            switchTier(effective)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MelodistPlayback", "Probe qualities failed", e)
                } finally {
                    _isProbingQuality.value = false
                }
            }
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
                        val currentList = _playlist.value
                        val idx = currentList.indexOfFirst { it.songMid == song.songMid }
                        if (idx >= 0) {
                            val mutableList = currentList.toMutableList()
                            mutableList[idx] = healed
                            _playlist.value = mutableList
                        }
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
        val list = _playlist.value
        if (list.isEmpty()) return

        if (_isRadioMode.value) {
            checkPrefetchRadioSongs()
            val nextIndex =
                if (_loopMode.value == PlaybackLoopMode.SingleRepeat) {
                    _currentIndex.value
                } else {
                    _currentIndex.value + 1
                }
            if (nextIndex in list.indices) {
                _currentIndex.value = nextIndex
                playSong(list[nextIndex])
            } else {
                // 已达队尾，等待并获取下一批电台推荐曲目
                scope.launch(Dispatchers.IO) {
                    try {
                        val moreSongs = apiService.getGuessRecommendSongs(count = 15)
                        withContext(Dispatchers.Main) {
                            if (moreSongs.isNotEmpty()) {
                                appendPlaylist(moreSongs)
                                val updated = _playlist.value
                                if (nextIndex in updated.indices) {
                                    _currentIndex.value = nextIndex
                                    playSong(updated[nextIndex])
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return
        }

        val nextIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.SingleRepeat -> _currentIndex.value
                PlaybackLoopMode.Shuffle -> shuffleQueue.next(list)
                PlaybackLoopMode.ListRepeat -> (_currentIndex.value + 1) % list.size
            }
        if (nextIndex in list.indices) {
            _currentIndex.value = nextIndex
            playSong(list[nextIndex])
        }
    }

    fun playPrevious() {
        if (playbackInterceptor?.onInterceptPlayPrevious() == true) {
            return
        }
        val list = _playlist.value
        if (list.isEmpty()) return

        if (_isRadioMode.value) {
            val prevIndex = (_currentIndex.value - 1).coerceAtLeast(0)
            if (prevIndex in list.indices) {
                _currentIndex.value = prevIndex
                playSong(list[prevIndex])
            }
            return
        }

        val prevIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.SingleRepeat -> _currentIndex.value
                PlaybackLoopMode.Shuffle -> shuffleQueue.previous()
                PlaybackLoopMode.ListRepeat -> if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            }
        if (prevIndex in list.indices) {
            _currentIndex.value = prevIndex
            playSong(list[prevIndex])
        }
    }

    /**
     * 获取上一首即将播放的歌曲（用于滑动预览），不推进播放状态
     */
    fun getPreviousSong(): Song? {
        if (_isRemoteActive.value && _remotePrevSong.value != null) {
            return _remotePrevSong.value
        }
        val list = _playlist.value
        if (list.isEmpty()) return null
        if (_isRadioMode.value) {
            val prevIndex = _currentIndex.value - 1
            return if (prevIndex in list.indices) list[prevIndex] else null
        }
        val prevIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.SingleRepeat -> _currentIndex.value
                PlaybackLoopMode.Shuffle -> shuffleQueue.peekPrevious() ?: if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
                PlaybackLoopMode.ListRepeat -> if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            }
        return if (prevIndex in list.indices) list[prevIndex] else null
    }

    /**
     * 获取下一首即将播放的歌曲（用于滑动预览），不推进播放状态
     */
    fun getNextSong(): Song? {
        if (_isRemoteActive.value && _remoteNextSong.value != null) {
            return _remoteNextSong.value
        }
        val list = _playlist.value
        if (list.isEmpty()) return null
        if (_isRadioMode.value) {
            val nextIndex = _currentIndex.value + 1
            return if (nextIndex in list.indices) list[nextIndex] else null
        }
        val nextIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.SingleRepeat -> _currentIndex.value
                PlaybackLoopMode.Shuffle -> shuffleQueue.peekNext() ?: ((_currentIndex.value + 1) % list.size)
                PlaybackLoopMode.ListRepeat -> (_currentIndex.value + 1) % list.size
            }
        return if (nextIndex in list.indices) list[nextIndex] else null
    }

    fun cycleLoopMode() {
        if (playbackInterceptor?.onInterceptCycleLoopMode() == true) {
            return
        }
        if (_isRadioMode.value) return
        val newMode =
            when (_loopMode.value) {
                PlaybackLoopMode.ListRepeat -> PlaybackLoopMode.SingleRepeat
                PlaybackLoopMode.SingleRepeat -> PlaybackLoopMode.Shuffle
                PlaybackLoopMode.Shuffle -> PlaybackLoopMode.ListRepeat
            }
        _loopMode.value = newMode
        if (newMode == PlaybackLoopMode.Shuffle) {
            val list = _playlist.value
            shuffleQueue.reset(list.size, _currentIndex.value, list)
        }
        savePlaybackState()
    }

    private fun handleSongEnded() {
        _isTransitioning.value = true
        when (_loopMode.value) {
            PlaybackLoopMode.SingleRepeat -> {
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
        progressJob?.cancel()
        playJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}

data class AudioTrackSpec(
    val format: String = "",
    val bitDepth: Int = 16,
    val sampleRateHz: Int = 44100,
    val bitrateKbps: Int = 0,
)
