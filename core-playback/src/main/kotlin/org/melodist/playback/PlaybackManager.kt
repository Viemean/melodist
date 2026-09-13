package org.melodist.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.api.LyricParser
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.addSongToFavorite
import org.melodist.api.deleteSongFromFavorite
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.probeSongQualities
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
import org.melodist.model.Song

enum class PlaybackLoopMode(
    val label: String,
) {
    ListRepeat("列表循环"),
    SingleRepeat("单曲循环"),
    Shuffle("随机播放"),
}

object PlaybackManager {
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
    private val shuffleQueue = ShuffleQueueManager()

    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

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

    private val _favoriteSongMids = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongMids: StateFlow<Set<String>> = _favoriteSongMids.asStateFlow()

    private val _availableTiers = MutableStateFlow<Set<AudioQualityTier>>(emptySet())
    val availableTiers: StateFlow<Set<AudioQualityTier>> = _availableTiers.asStateFlow()

    private var probeJob: Job? = null

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loopMode = MutableStateFlow(PlaybackLoopMode.ListRepeat)
    val loopMode: StateFlow<PlaybackLoopMode> = _loopMode.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isRadioMode = MutableStateFlow(false)
    val isRadioMode: StateFlow<Boolean> = _isRadioMode.asStateFlow()

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
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
                    }
                    Player.STATE_ENDED -> {
                        handleSongEnded()
                    }
                    Player.STATE_BUFFERING -> {
                        _isLoading.value = true
                    }
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _isLoading.value = false
                Log.e("MelodistPlayback", "ExoPlayer playback error: ${error.errorCodeName}, cause: ${error.cause?.message}", error)
                _errorMessage.value = "播放失败: ${error.localizedMessage}"
                scope.launch {
                    delay(2000L)
                    playNext()
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
                                        else -> if (sRate >= 96000) 24 else 16
                                    }
                                if (sRate > 0) {
                                    val detectedTier =
                                        AudioQualityTier.inferFromAudioFormat(
                                            sampleRate = sRate,
                                            bitsPerSample = bitDepth,
                                            channelCount = if (channels > 0) channels else 2,
                                            mimeType = mime,
                                            bitrate = if (bitrate > 0) bitrate else 0,
                                        )
                                    val currentSong = _currentSong.value
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
                val isPassthrough = org.melodist.data.AppSettingsManager.settings.value.enableAudioPassthrough
                val audioCapabilities =
                    if (isPassthrough) {
                        AudioCapabilities.getCapabilities(context)
                    } else {
                        AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                    }
                val builder =
                    DefaultAudioSink
                        .Builder(context)
                        .setAudioCapabilities(audioCapabilities)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                if (isPassthrough) {
                    // 直通模式下清空软件音频处理器
                    builder.setAudioProcessors(emptyArray())
                }
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

        return ExoPlayer
            .Builder(context.applicationContext, createRenderersFactory(context))
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(playerListener)
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
        if (exoPlayer != null) return

        exoPlayer = buildExoPlayer(context)
        startProgressLoop()
    }

    @Suppress("UnusedParameter")
    fun onAudioPassthroughChanged(enabled: Boolean) {
        // 仅更新配置，在下一次起播时生效
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob =
            scope.launch {
                var saveCounter = 0
                while (isActive) {
                    exoPlayer?.let { player ->
                        if (player.isPlaying) {
                            _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                            if (player.duration > 0L) {
                                _durationMs.value = player.duration
                            }
                            saveCounter++
                            if (saveCounter >= 30) {
                                saveCounter = 0
                                savePlaybackProgress(_currentPositionMs.value)
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
                apply()
            }
        } catch (e: Exception) {
            Log.w("MelodistPlayback", "Failed to save playback state", e)
        }
    }

    private fun savePlaybackProgress(posMs: Long) {
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

    fun toggleCurrentSongFavorite() {
        val song = _currentSong.value ?: return
        if (!isSongFavoriteSupported(song)) return
        val mid = song.songMid
        val id = song.songId
        val isFav = _favoriteSongMids.value.contains(mid)
        val updated =
            if (isFav) {
                _favoriteSongMids.value - mid
            } else {
                _favoriteSongMids.value + mid
            }
        _favoriteSongMids.value = updated
        savePlaybackState()

        scope.launch(Dispatchers.IO) {
            try {
                if (isFav) {
                    apiService.deleteSongFromFavorite(id, mid)
                } else {
                    apiService.addSongToFavorite(id, mid)
                }
            } catch (e: Exception) {
                Log.w("MelodistPlayback", "Failed to sync favorite to cloud: $e")
            }
        }
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
    ) {
        _isRadioMode.value = isRadio
        _playlist.value = songs
        if (songs.isNotEmpty() && startIndex in songs.indices) {
            _currentIndex.value = startIndex
            if (!isRadio && _loopMode.value == PlaybackLoopMode.Shuffle) {
                shuffleQueue.reset(songs.size, startIndex, songs)
            }
            playSong(songs[startIndex])
        }
        savePlaybackState()
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

    fun playSong(
        song: Song,
        forceTier: AudioQualityTier? = null,
        seekToMs: Long = 0L,
    ) {
        playJob?.cancel()
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
                val server = org.melodist.data.WebDavManager.getActiveServer()
                val relativeHref = effectiveSong.mediaMid.ifBlank { effectiveSong.localFilePath ?: "" }
                if (server != null && relativeHref.isNotBlank()) {
                    val cachedCover = org.melodist.data.WebDavManager.getSongCoverPath(server.id, relativeHref)
                    effectiveSong = effectiveSong.copy(coverUrl = cachedCover.orEmpty())
                } else {
                    effectiveSong = effectiveSong.copy(coverUrl = "")
                }
            } else if (isCoverInvalid) {
                effectiveSong = effectiveSong.copy(coverUrl = "")
            }
        }
        _currentSong.value = effectiveSong
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
            val actualTier = song.currentTier ?: AudioQualityTier.SQ
            _availableTiers.value = setOf(actualTier)
        } else {
            _availableTiers.value = emptySet()
            probeJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        val probed = apiService.probeSongQualities(song.songMid, song.mediaMid)
                        val available = probed.filter { it.isAvailable }.map { it.tier }.toSet()
                        if (available.isNotEmpty()) {
                            _availableTiers.value = available
                        }
                    } catch (e: Exception) {
                        Log.w("MelodistPlayback", "Probe qualities failed", e)
                    }

                    // 若当前单曲无封面，智能动态拉取单曲专属视觉 MID 并自愈封面
                    if (song.coverUrl.isBlank() && song.songMid.isNotBlank()) {
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
                        val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(directFile))
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
                        val existingCover = org.melodist.data.WebDavManager.getSongCoverPath(server.id, relativeHref)
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
                        val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(localFile))
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
                                .createMediaSource(MediaItem.fromUri(android.net.Uri.parse(streamUrl)))
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
                                val meta = org.melodist.data.WebDavManager.extractPlaybackMetadata(server, song)
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
                            apiService.getLyrics(song.songMid)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }

                val isPassthrough = org.melodist.data.AppSettingsManager.settings.value.enableAudioPassthrough
                val targetTier = forceTier ?: _preferredTier.value
                val urlDeferred =
                    async(Dispatchers.IO) {
                        try {
                            apiService.getPlayUrl(song.songMid, mediaMid = song.mediaMid, preferredTier = targetTier)
                        } catch (e: Exception) {
                            null
                        }
                    }

                val lyricList = lyricDeferred.await()
                _lyrics.value = lyricList

                val playUrlInfo = urlDeferred.await()
                val rawUrl = playUrlInfo?.url
                if (playUrlInfo != null && !rawUrl.isNullOrBlank()) {
                    Log.i(
                        "MelodistPlayback",
                        "Preparing playback with URL: $rawUrl, tier: ${playUrlInfo.tier} (preferred: $targetTier, passthrough: $isPassthrough)",
                    )
                    _currentTier.value = playUrlInfo.tier
                    val player = exoPlayer ?: return@launch
                    val mediaItem = MediaItem.fromUri(rawUrl)
                    if (seekToMs > 0L) {
                        player.setMediaItem(mediaItem, seekToMs)
                    } else {
                        player.setMediaItem(mediaItem)
                    }
                    player.volume = 1.0f
                    player.prepare()
                    player.play()
                    savePlaybackState()
                } else {
                    _isLoading.value = false
                    Log.w("MelodistPlayback", "Failed to obtain playback URL for songMid=${song.songMid}, preferredTier=$targetTier")
                    _errorMessage.value = "无法获取播放直链 (需 VIP 或版权限制)"
                }
            }
    }

    fun togglePlayPause() {
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
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        savePlaybackProgress(positionMs)
    }

    fun setPreferredQualityTier(tier: AudioQualityTier) {
        _preferredTier.value = tier
        org.melodist.data.AppSettingsManager
            .updatePreferredQualityTier(tier)
        savePlaybackState()
        if (_currentSong.value != null && _currentTier.value != tier) {
            switchTier(tier)
        }
    }

    fun switchTier(tier: AudioQualityTier) {
        val current = _currentSong.value ?: return
        val currentPos = exoPlayer?.currentPosition ?: _currentPositionMs.value
        val previousTier = _currentTier.value
        _preferredTier.value = tier
        org.melodist.data.AppSettingsManager
            .updatePreferredQualityTier(tier)
        savePlaybackState()

        playJob?.cancel()
        _isLoading.value = true

        playJob =
            scope.launch {
                val playUrlInfo =
                    withContext(Dispatchers.IO) {
                        try {
                            apiService.getPlayUrl(current.songMid, mediaMid = current.mediaMid, preferredTier = tier)
                        } catch (_: Exception) {
                            null
                        }
                    }

                val rawUrl = playUrlInfo?.url
                if (playUrlInfo != null && !rawUrl.isNullOrBlank()) {
                    _currentTier.value = playUrlInfo.tier
                    _currentSong.value = _currentSong.value?.copy(currentTier = playUrlInfo.tier)
                    val player = exoPlayer ?: return@launch
                    player.volume = 1.0f
                    val mediaItem = MediaItem.fromUri(rawUrl)
                    player.setMediaItem(mediaItem, currentPos)
                    player.prepare()
                    player.play()
                    savePlaybackState()
                } else {
                    _isLoading.value = false
                    _errorMessage.value = "该音质不可用"
                    _preferredTier.value = previousTier
                }
            }
    }

    fun playNext() {
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

    fun cycleLoopMode() {
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
        when (_loopMode.value) {
            PlaybackLoopMode.SingleRepeat -> {
                exoPlayer?.seekTo(0L)
                exoPlayer?.play()
            }
            else -> playNext()
        }
    }

    fun release() {
        savePlaybackState()
        progressJob?.cancel()
        playJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
