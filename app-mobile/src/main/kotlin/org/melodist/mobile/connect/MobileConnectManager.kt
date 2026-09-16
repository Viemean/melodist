package org.melodist.mobile.connect

import android.content.Context
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
import kotlinx.serialization.json.Json
import org.melodist.core.connect.client.MobileConnectClient
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.core.connect.discovery.ConnectNsdHelper
import org.melodist.core.connect.model.AudioSourceDescriptor
import org.melodist.core.connect.model.AudioSourceType
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.DeviceType
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.core.connect.model.QrPairData
import org.melodist.core.connect.model.RemoteControlMode
import org.melodist.core.connect.storage.ConnectStorageManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.playback.PlaybackInterceptor
import org.melodist.playback.PlaybackManager

object MobileConnectManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var storageManager: ConnectStorageManager? = null
    private var connectClient: MobileConnectClient? = null
    private var nsdHelper: ConnectNsdHelper? = null
    private var streamServer: LocalAudioStreamServer? = null
    private var userManuallyDisconnected = false
    private var autoConnectJob: Job? = null
    private var isSyncingFromTv = false
    private var autoConnectFailureCount = 0
    private const val MAX_AUTO_CONNECT_FAILURES = 6
    private val AUTO_CONNECT_BACKOFF_DELAYS = longArrayOf(3000L, 6000L, 12000L, 20000L, 35000L, 60000L)

    val connectionState: StateFlow<MobileConnectionState>
        get() = connectClient?.connectionState ?: MutableStateFlow(MobileConnectionState.Disconnected)

    private val _tvPlayerState = MutableStateFlow<PlayerStateEvent?>(null)
    val tvPlayerState: StateFlow<PlayerStateEvent?> = _tvPlayerState.asStateFlow()

    val discoveredDevices: StateFlow<List<ConnectDevice>>
        get() = nsdHelper?.discoveredDevices ?: MutableStateFlow(emptyList())

    val pairedDevices: StateFlow<List<ConnectDevice>>
        get() = storageManager?.pairedDevicesFlow ?: MutableStateFlow(emptyList())

    val localMute: StateFlow<Boolean>
        get() = storageManager?.localMuteFlow ?: MutableStateFlow(false)

    val tvOfflineProxy: StateFlow<Boolean>
        get() = storageManager?.tvOfflineProxyFlow ?: MutableStateFlow(false)

    val remoteControlMode: StateFlow<RemoteControlMode>
        get() = storageManager?.remoteControlModeFlow ?: MutableStateFlow(RemoteControlMode.BROWSE)

    private var appContext: Context? = null

    val isTvOnline: Boolean
        get() = connectionState.value is MobileConnectionState.Paired

    fun init(context: Context) {
        if (storageManager != null) return
        appContext = context.applicationContext
        val storage = ConnectStorageManager(context, DeviceType.MOBILE)
        storageManager = storage

        // 默认连接模式为浏览模式
        storage.setRemoteControlMode(RemoteControlMode.BROWSE)

        val client = MobileConnectClient(storage)
        connectClient = client

        val nsd = ConnectNsdHelper(context)
        nsdHelper = nsd
        nsd.startDiscovery()

        val server = LocalAudioStreamServer(context)
        streamServer = server
        server.start()

        setupPlaybackInterceptor()

        scope.launch {
            client.connectionState.collect { state ->
                if (state is MobileConnectionState.Paired) {
                    // 连接成功时，确保默认连接模式是浏览模式
                    setRemoteControlMode(RemoteControlMode.BROWSE)
                    PlaybackManager.setVolume(if (localMute.value) 0f else 1f)
                } else {
                    if (PlaybackManager.isRemoteActive.value) {
                        PlaybackManager.setRemoteActive(false, null)
                        PlaybackManager.clearRemotePlayback()
                    }
                    if (localMute.value) {
                        setLocalMute(false)
                    }
                }
            }
        }

        scope.launch {
            client.playerState.collect { state ->
                val resolvedState = if (state != null) {
                    val resolvedSong = state.currentSong?.let { resolveWebDavCoverLocally(it) }
                    val resolvedPrev = state.prevSong?.let { resolveWebDavCoverLocally(it) }
                    val resolvedNext = state.nextSong?.let { resolveWebDavCoverLocally(it) }
                    state.copy(
                        currentSong = resolvedSong,
                        prevSong = resolvedPrev,
                        nextSong = resolvedNext,
                    )
                } else {
                    null
                }
                _tvPlayerState.value = resolvedState
                if (remoteControlMode.value == RemoteControlMode.TAKEOVER && isTvOnline) {
                    if (resolvedState != null) {
                        val tvSong = resolvedState.currentSong
                        val isPlaying = resolvedState.isPlaying
                        val tvPos = resolvedState.positionMs

                        if (localMute.value) {
                            // 开启本地静音：手机本地静音且不发声，仅同步 UI 与系统媒体控制通知
                            if (PlaybackManager.isPlaying.value && !PlaybackManager.isRemoteActive.value) {
                                isSyncingFromTv = true
                                try {
                                    PlaybackManager.pause()
                                } finally {
                                    isSyncingFromTv = false
                                }
                            }
                            PlaybackManager.setVolume(0f)
                            val pairedName = (connectionState.value as? MobileConnectionState.Paired)?.targetDevice?.name
                            appContext?.let { PlaybackManager.startPlaybackService(it) }
                            PlaybackManager.setRemoteActive(true, pairedName)
                            PlaybackManager.syncRemotePlaybackState(
                                song = tvSong,
                                isPlaying = isPlaying,
                                positionMs = tvPos,
                                durationMs = resolvedState.durationMs,
                                currentIndex = resolvedState.currentIndex,
                                loopModeName = resolvedState.loopMode,
                                prevSong = resolvedState.prevSong,
                                nextSong = resolvedState.nextSong,
                                currentTier = resolvedState.currentTier,
                                availableTiers = resolvedState.availableTiers,
                            )
                        } else {
                            // 关闭本地静音：手机端跟随 TV 发声
                            PlaybackManager.setRemoteActive(false, null)
                            PlaybackManager.setVolume(1f)
                            if (tvSong != null) {
                                val localSong = PlaybackManager.currentSong.value
                                val isSameSong = localSong?.songMid == tvSong.songMid
                                if (!isSameSong) {
                                    isSyncingFromTv = true
                                    try {
                                        PlaybackManager.playSong(tvSong, seekToMs = tvPos)
                                    } finally {
                                        isSyncingFromTv = false
                                    }
                                } else {
                                    if (isPlaying && !PlaybackManager.isPlaying.value) {
                                        isSyncingFromTv = true
                                        try {
                                            PlaybackManager.play()
                                        } finally {
                                            isSyncingFromTv = false
                                        }
                                    } else if (!isPlaying && PlaybackManager.isPlaying.value) {
                                        isSyncingFromTv = true
                                        try {
                                            PlaybackManager.pause()
                                        } finally {
                                            isSyncingFromTv = false
                                        }
                                    }
                                    val localPos = PlaybackManager.currentPositionMs.value
                                    if (Math.abs(localPos - tvPos) > 2500L) {
                                        isSyncingFromTv = true
                                        try {
                                            PlaybackManager.seekTo(tvPos)
                                        } finally {
                                            isSyncingFromTv = false
                                        }
                                    }
                                }
                            }
                        }
                        val curMid = tvSong?.songMid.orEmpty()
                        if (curMid.isNotBlank() && !curMid.startsWith("webdav_")) {
                            val localFav = PlaybackManager.isSongFavorite(curMid)
                            if (localFav != resolvedState.isFavorite) {
                                PlaybackManager.setSongFavoriteState(curMid, resolvedState.isFavorite)
                            }
                        }
                    } else {
                        // TV 暂无播放曲目
                        if (PlaybackManager.isRemoteActive.value && localMute.value) {
                            PlaybackManager.clearRemotePlayback()
                        }
                    }
                }
            }
        }

        scope.launch {
            client.queueState.collect { qState ->
                if (qState != null && remoteControlMode.value == RemoteControlMode.TAKEOVER && isTvOnline) {
                    val resolvedQueue = qState.queue.map { resolveWebDavCoverLocally(it) }
                    PlaybackManager.syncRemoteQueue(resolvedQueue, qState.currentIndex)
                }
            }
        }

        scope.launch {
            PlaybackManager.songFavoriteToggledEvent.collect { (song, isFav) ->
                if (remoteControlMode.value == RemoteControlMode.TAKEOVER && isTvOnline) {
                    connectClient?.toggleFavorite(song = song, songMid = song.songMid, isFavorite = isFav)
                }
            }
        }

        startAutoConnectLoop()
    }

    private suspend fun isPortReachable(host: String, port: Int, timeoutMs: Int = 800): Boolean =
        withContext(Dispatchers.IO) {
            if (host.isBlank() || port <= 0) return@withContext false
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    fun resetAndRetryAutoConnect() {
        userManuallyDisconnected = false
        autoConnectFailureCount = 0
        startAutoConnectLoop()
    }

    private fun startAutoConnectLoop() {
        autoConnectJob?.cancel()
        autoConnectJob = scope.launch {
            // 1. 监听 NSD 发现的设备：当监听到最近成功连接的设备或已配对设备时，主动发起连接
            launch {
                nsdHelper?.discoveredDevices?.collect { devices ->
                    if (userManuallyDisconnected) return@collect
                    val state = connectionState.value
                    if (state is MobileConnectionState.Connected || state is MobileConnectionState.Paired || state is MobileConnectionState.Connecting) {
                        return@collect
                    }
                    val lastDevice = storageManager?.getLastConnectedDevice()
                    val matched = if (lastDevice != null) {
                        devices.find { it.id == lastDevice.id }
                    } else {
                        devices.find { storageManager?.isDevicePaired(it.id) == true }
                    }
                    if (matched != null) {
                        autoConnectFailureCount = 0
                        android.util.Log.i("MobileConnectManager", "Auto-connecting to discovered device: ${matched.name} (${matched.host}:${matched.port})")
                        connectClient?.connect(matched)
                    }
                }
            }

            // 2. 自适应探测重连：带轻量 TCP 探测、指数退避与失败上限停止
            var isFirstCheck = true
            while (isActive) {
                if (isFirstCheck) {
                    delay(1500L)
                    isFirstCheck = false
                }
                if (userManuallyDisconnected) {
                    delay(5000L)
                    continue
                }
                val state = connectionState.value
                if (state is MobileConnectionState.Connected || state is MobileConnectionState.Paired || state is MobileConnectionState.Connecting) {
                    autoConnectFailureCount = 0
                    delay(6000L)
                    continue
                }

                // 达到最大失败次数后停止无休止探测，避免能耗与网络开销
                if (autoConnectFailureCount >= MAX_AUTO_CONNECT_FAILURES) {
                    android.util.Log.i("MobileConnectManager", "Auto-connect stopped after reaching maximum failure attempts ($autoConnectFailureCount).")
                    break
                }

                val lastDevice = storageManager?.getLastConnectedDevice()
                if (lastDevice == null || lastDevice.host.isBlank() || lastDevice.port <= 0) {
                    delay(6000L)
                    continue
                }

                // 轻量 TCP 端口探测（超时 800ms），在线才尝试建立 WebSocket 握手
                val reachable = isPortReachable(lastDevice.host, lastDevice.port, timeoutMs = 800)
                if (reachable) {
                    android.util.Log.i("MobileConnectManager", "Device port reachable, auto-connecting: ${lastDevice.name} (${lastDevice.host}:${lastDevice.port})")
                    connectClient?.connect(lastDevice)
                    // 等待连接握手结果
                    delay(4000L)
                } else {
                    autoConnectFailureCount++
                    val backoff = AUTO_CONNECT_BACKOFF_DELAYS.getOrElse(autoConnectFailureCount - 1) { 60000L }
                    android.util.Log.d("MobileConnectManager", "Device ${lastDevice.host}:${lastDevice.port} unreachable, probeFailure=$autoConnectFailureCount, nextBackoff=${backoff}ms")
                    delay(backoff)
                }
            }
        }
    }

    fun startDiscovery() {
        nsdHelper?.startDiscovery()
        if (!userManuallyDisconnected && autoConnectJob?.isActive != true && connectionState.value !is MobileConnectionState.Paired) {
            resetAndRetryAutoConnect()
        }
    }

    fun stopDiscovery() {
        nsdHelper?.stopDiscovery()
    }

    fun connectTo(device: ConnectDevice, pinCode: String = "") {
        userManuallyDisconnected = false
        connectClient?.connect(device, pinCode)
    }

    fun connectByQrJson(qrJson: String): Boolean {
        return try {
            val data = json.decodeFromString<QrPairData>(qrJson)
            val device = ConnectDevice(
                id = data.deviceId,
                name = data.deviceName,
                type = DeviceType.TV,
                host = data.host,
                port = data.port,
                token = data.token,
            )
            connectTo(device, data.pinCode)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disconnect() {
        userManuallyDisconnected = true
        connectClient?.disconnect()
        _tvPlayerState.value = null
        if (PlaybackManager.isRemoteActive.value) {
            PlaybackManager.setRemoteActive(false, null)
            PlaybackManager.clearRemotePlayback()
        }
        if (localMute.value) {
            setLocalMute(false)
        }
    }

    fun setLocalMute(enabled: Boolean) {
        storageManager?.setLocalMute(enabled)
        PlaybackManager.setVolume(if (enabled) 0f else 1f)
        if (remoteControlMode.value == RemoteControlMode.TAKEOVER) {
            if (enabled) {
                // 开启静音：暂停本地音频发声，但绝不影响 TV
                isSyncingFromTv = true
                try {
                    PlaybackManager.pause()
                } finally {
                    isSyncingFromTv = false
                }
                val pairedName = (connectionState.value as? MobileConnectionState.Paired)?.targetDevice?.name
                appContext?.let { PlaybackManager.startPlaybackService(it) }
                PlaybackManager.setRemoteActive(true, pairedName)
                val state = tvPlayerState.value
                if (state != null) {
                    PlaybackManager.syncRemotePlaybackState(
                        song = state.currentSong,
                        isPlaying = state.isPlaying,
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                    )
                }
            } else {
                PlaybackManager.setRemoteActive(false, null)
                // 关闭静音：若 TV 正在播放，立即驱动本地播放发声
                val state = tvPlayerState.value
                val tvSong = state?.currentSong
                if (state?.isPlaying == true && tvSong != null) {
                    isSyncingFromTv = true
                    try {
                        PlaybackManager.playSong(tvSong, seekToMs = state.positionMs)
                    } finally {
                        isSyncingFromTv = false
                    }
                }
            }
        }
    }

    fun setTvOfflineProxy(enabled: Boolean) {
        storageManager?.setTvOfflineProxy(enabled)
    }

    fun setRemoteControlMode(mode: RemoteControlMode) {
        storageManager?.setRemoteControlMode(mode)
        if (mode != RemoteControlMode.TAKEOVER) {
            if (PlaybackManager.isRemoteActive.value) {
                PlaybackManager.setRemoteActive(false, null)
                PlaybackManager.clearRemotePlayback()
            }
        } else if (isTvOnline && localMute.value) {
            val pairedName = (connectionState.value as? MobileConnectionState.Paired)?.targetDevice?.name
            appContext?.let { PlaybackManager.startPlaybackService(it) }
            PlaybackManager.setRemoteActive(true, pairedName)
            tvPlayerState.value?.let { state ->
                PlaybackManager.syncRemotePlaybackState(
                    song = state.currentSong,
                    isPlaying = state.isPlaying,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                )
            }
        }
    }

    fun removePairedDevice(deviceId: String) {
        storageManager?.removePairedDevice(deviceId)
    }

    fun relayCurrentPlaybackToTv() {
        val song = PlaybackManager.currentSong.value ?: return
        val pos = PlaybackManager.currentPositionMs.value
        playOnTv(song, startPositionMs = pos)
    }

    fun playOnTv(song: Song, startPositionMs: Long = 0L, forceTier: AudioQualityTier? = null) {
        scope.launch(Dispatchers.Main) {
            val audioSource = withContext(Dispatchers.IO) {
                resolveAudioSource(song)
            }
            val currentPlaylist = PlaybackManager.playlist.value
            val idx = currentPlaylist.indexOfFirst { it.songMid == song.songMid }.coerceAtLeast(0)
            val effectiveTier = forceTier ?: PlaybackManager.preferredTier.value
            val effectiveSong = prepareSongForTv(song)
            val preparedQueue = currentPlaylist.map { prepareSongForTv(it) }
            connectClient?.playSong(
                song = effectiveSong,
                queue = preparedQueue,
                index = idx,
                startPositionMs = startPositionMs,
                audioSource = audioSource,
                qualityTier = effectiveTier,
            )
            // 仅在浏览模式开启本地静音时，接力到 TV 暂停本地音频
            if (localMute.value && remoteControlMode.value == RemoteControlMode.BROWSE) {
                PlaybackManager.pause()
            }
        }
    }

    fun enqueueNextOnTv(song: Song) {
        scope.launch(Dispatchers.IO) {
            val audioSource = resolveAudioSource(song)
            val effectiveSong = prepareSongForTv(song)
            connectClient?.enqueueNext(effectiveSong, audioSource)
        }
    }

    fun tvPause() = connectClient?.pause()
    fun tvResume() = connectClient?.resume()
    fun tvNext() = connectClient?.next()
    fun tvPrev() = connectClient?.previous()
    fun tvSeekTo(posMs: Long) = connectClient?.seekTo(posMs)
    fun tvSetVolume(vol: Float) = connectClient?.setVolume(vol)
    fun tvSwitchTier(tier: AudioQualityTier) = connectClient?.switchTier(tier)
    fun triggerTvAod() = connectClient?.triggerAod()
    fun tvCycleLoopMode() = connectClient?.cycleLoopMode()
    fun tvOpenPlayer() = connectClient?.openPlayer()

    private var lastGestureSendTime = 0L

    fun sendGestureSwipe(
        state: org.melodist.core.connect.model.GestureSwipeState,
        fraction: Float = 0f,
        targetFraction: Float = 0f,
        durationMs: Long = 200L,
    ) {
        if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) return
        val now = System.currentTimeMillis()
        if (state == org.melodist.core.connect.model.GestureSwipeState.DRAGGING) {
            if (now - lastGestureSendTime < 20L) return
            lastGestureSendTime = now
        } else {
            lastGestureSendTime = 0L
        }
        connectClient?.sendGestureSwipe(state, fraction, targetFraction, durationMs)
    }

    private fun setupPlaybackInterceptor() {
        PlaybackManager.playbackInterceptor = object : PlaybackInterceptor {
            override fun onInterceptPlaySong(
                song: Song,
                forceTier: AudioQualityTier?,
                seekToMs: Long,
            ): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                playOnTv(song, startPositionMs = seekToMs, forceTier = forceTier)
                return true
            }

            override fun onInterceptSwitchTier(tier: AudioQualityTier): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                connectClient?.switchTier(tier)
                return true
            }

            override fun onInterceptTogglePlayPause(): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                val isPlaying = tvPlayerState.value?.isPlaying == true
                if (isPlaying) {
                    tvPause()
                } else {
                    tvResume()
                }
                return true
            }

            override fun onInterceptPause(): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                tvPause()
                return true
            }

            override fun onInterceptResume(): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                tvResume()
                return true
            }

            override fun onInterceptSeekTo(positionMs: Long): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                tvSeekTo(positionMs)
                return true
            }

            override fun onInterceptPlayNext(): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                tvNext()
                return true
            }

            override fun onInterceptPlayPrevious(): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                tvPrev()
                return true
            }

            override fun onInterceptCycleLoopMode(): Boolean {
                if (isSyncingFromTv) return false
                if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                    return false
                }
                tvCycleLoopMode()
                return true
            }
        }
    }

    private fun prepareSongForTv(song: Song): Song {
        val server = streamServer ?: return song
        var updated = song
        val coverUrl = song.coverUrl
        if (coverUrl.isNotBlank() && (coverUrl.startsWith("file://") || coverUrl.startsWith("/"))) {
            updated = updated.copy(coverUrl = server.buildLocalCoverUrl(coverUrl))
        }
        return updated
    }

    private fun resolveWebDavCoverLocally(song: Song): Song {
        if (!song.isWebDav) return song
        val coverUrl = song.coverUrl
        if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
            return song
        }
        if (coverUrl.startsWith("file://")) {
            val f = java.io.File(coverUrl.removePrefix("file://"))
            if (f.exists() && f.length() > 0L) return song
        }
        val serverId = song.songMid.removePrefix("webdav_").substringBeforeLast('_')
        val href = song.mediaMid.ifBlank { song.localFilePath.orEmpty() }
        val servers = org.melodist.data.WebDavManager.getServers()
        val server = servers.find { it.id == serverId }
            ?: org.melodist.data.WebDavManager.getActiveServer()
            ?: servers.firstOrNull()
            ?: return song
        if (href.isNotBlank()) {
            val cachedCover = org.melodist.data.WebDavManager.getSongCoverPath(server.id, href)
            if (!cachedCover.isNullOrBlank()) {
                return song.copy(coverUrl = cachedCover)
            }
        }
        return song
    }

    private fun resolveAudioSource(song: Song): AudioSourceDescriptor {
        val isOfflineMode = tvOfflineProxy.value
        val server = streamServer

        val filePath = song.localFilePath
        if (song.isLocal && !filePath.isNullOrBlank() && server != null) {
            return AudioSourceDescriptor(
                sourceType = AudioSourceType.STREAM_PROXY,
                streamUrl = server.buildLocalAudioStreamUrl(filePath),
            )
        }

        if (song.isWebDav && server != null) {
            val serverId = song.songMid.removePrefix("webdav_").substringBeforeLast('_')
            val href = song.mediaMid.ifBlank { song.localFilePath.orEmpty() }
            if (serverId.isNotBlank() && href.isNotBlank()) {
                return AudioSourceDescriptor(
                    sourceType = AudioSourceType.STREAM_PROXY,
                    streamUrl = server.buildWebDavStreamUrl(serverId, href),
                )
            }
        }

        if (isOfflineMode && server != null) {
            // TV 离线模式下，在线曲目走手机中转
            return AudioSourceDescriptor(
                sourceType = AudioSourceType.DIRECT_API,
            )
        }

        return AudioSourceDescriptor(sourceType = AudioSourceType.DIRECT_API)
    }
}
