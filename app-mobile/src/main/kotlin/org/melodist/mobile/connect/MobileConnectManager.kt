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
    private val json =
        Json {
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

    private val _connectionState = MutableStateFlow<MobileConnectionState>(MobileConnectionState.Disconnected)
    val connectionState: StateFlow<MobileConnectionState> = _connectionState.asStateFlow()

    private val _tvPlayerState = MutableStateFlow<PlayerStateEvent?>(null)
    val tvPlayerState: StateFlow<PlayerStateEvent?> = _tvPlayerState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ConnectDevice>> = _discoveredDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val pairedDevices: StateFlow<List<ConnectDevice>> = _pairedDevices.asStateFlow()

    private val _localMute = MutableStateFlow<Boolean>(false)
    val localMute: StateFlow<Boolean> = _localMute.asStateFlow()

    private val _tvOfflineProxy = MutableStateFlow<Boolean>(false)
    val tvOfflineProxy: StateFlow<Boolean> = _tvOfflineProxy.asStateFlow()

    private val _remoteControlMode = MutableStateFlow<RemoteControlMode>(RemoteControlMode.BROWSE)
    val remoteControlMode: StateFlow<RemoteControlMode> = _remoteControlMode.asStateFlow()

    private var appContext: Context? = null

    val isTvOnline: Boolean
        get() = connectionState.value is MobileConnectionState.Paired

    fun init(context: Context) {
        if (storageManager != null) return
        val app = context.applicationContext
        appContext = app
        val storage = ConnectStorageManager(app, DeviceType.MOBILE)
        storageManager = storage

        // 默认连接模式为浏览模式
        storage.setRemoteControlMode(RemoteControlMode.BROWSE)

        val client = MobileConnectClient(storage)
        connectClient = client

        val nsd = ConnectNsdHelper(app)
        nsdHelper = nsd
        nsd.startDiscovery()

        val server = LocalAudioStreamServer(app)
        streamServer = server
        server.start()

        setupPlaybackInterceptor()

        scope.launch {
            client.connectionState.collect { _connectionState.value = it }
        }
        scope.launch {
            nsd.discoveredDevices.collect { _discoveredDevices.value = it }
        }
        scope.launch {
            storage.pairedDevicesFlow.collect { _pairedDevices.value = it }
        }
        scope.launch {
            storage.localMuteFlow.collect { _localMute.value = it }
        }
        scope.launch {
            storage.tvOfflineProxyFlow.collect { _tvOfflineProxy.value = it }
        }
        scope.launch {
            storage.remoteControlModeFlow.collect { _remoteControlMode.value = it }
        }

        scope.launch {
            client.connectionState.collect { state ->
                if (state is MobileConnectionState.Paired) {
                    if (remoteControlMode.value == RemoteControlMode.TAKEOVER) {
                        if (PlaybackManager.isPlaying.value && !PlaybackManager.isRemoteActive.value) {
                            isSyncingFromTv = true
                            try {
                                PlaybackManager.pause()
                            } finally {
                                isSyncingFromTv = false
                            }
                        }
                        if (localMute.value) {
                            PlaybackManager.setVolume(0f)
                        } else {
                            PlaybackManager.setVolume(1f)
                        }
                    } else {
                        PlaybackManager.setVolume(1f)
                    }
                } else {
                    if (PlaybackManager.isRemoteActive.value) {
                        PlaybackManager.setRemoteActive(false, null)
                        PlaybackManager.clearRemotePlayback()
                    }
                    PlaybackManager.setVolume(1f)
                }
            }
        }

        scope.launch {
            client.playerState.collect { state ->
                val resolvedState =
                    if (state != null) {
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

                        val shouldSyncToLocalPlayer = isPlaying || PlaybackManager.isRemoteActive.value
                        if (shouldSyncToLocalPlayer) {
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
                                isRadioMode = resolvedState.isRadioMode,
                                lyricOffsetMs = resolvedState.lyricOffsetMs,
                            )

                            if (localMute.value) {
                                // 开启本地静音：保持静音保活且不发声，仅同步 UI 与系统媒体控制通知
                                PlaybackManager.resetPlaybackSpeed()
                                PlaybackManager.setVolume(1f)
                                if (isPlaying && tvSong != null) {
                                    PlaybackManager.startSilentKeepAlive()
                                } else {
                                    PlaybackManager.pauseSilentKeepAlive()
                                }
                            } else {
                                // 关闭本地静音：手机端跟随 TV 发声
                                PlaybackManager.stopSilentKeepAlive()
                                PlaybackManager.setVolume(1f)
                                if (tvSong != null) {
                                    val isSameSong = PlaybackManager.activeMediaId == tvSong.songMid
                                    if (!isSameSong) {
                                        isSyncingFromTv = true
                                        try {
                                            PlaybackManager.resetPlaybackSpeed()
                                            PlaybackManager.playSong(tvSong, seekToMs = tvPos)
                                            if (!isPlaying) {
                                                PlaybackManager.pause()
                                            }
                                        } finally {
                                            isSyncingFromTv = false
                                        }
                                    } else {
                                        if (isPlaying) {
                                            if (!PlaybackManager.actualAudioIsPlaying) {
                                                isSyncingFromTv = true
                                                try {
                                                    PlaybackManager.play()
                                                } finally {
                                                    isSyncingFromTv = false
                                                }
                                            }
                                        } else {
                                            PlaybackManager.resetPlaybackSpeed()
                                            if (PlaybackManager.actualAudioIsPlaying) {
                                                isSyncingFromTv = true
                                                try {
                                                    PlaybackManager.pause()
                                                } finally {
                                                    isSyncingFromTv = false
                                                }
                                            }
                                        }
                                        if (isPlaying && !PlaybackManager.isTransitioning.value) {
                                            val localPos = PlaybackManager.actualAudioPositionMs
                                            val diffMs = tvPos - localPos // 正数: 手机落后于 TV; 负数: 手机超前于 TV
                                            when {
                                                Math.abs(diffMs) >= 1800L -> {
                                                    // 偏差过大（超过 1.8 秒），执行硬 Seek 并恢复标准倍速
                                                    isSyncingFromTv = true
                                                    try {
                                                        PlaybackManager.resetPlaybackSpeed()
                                                        PlaybackManager.seekTo(tvPos)
                                                    } finally {
                                                        isSyncingFromTv = false
                                                    }
                                                }
                                                diffMs > 600L -> {
                                                    // 手机落后 600ms ~ 1800ms：快速快进 1.12x 追赶
                                                    PlaybackManager.setPlaybackSpeed(1.12f)
                                                }
                                                diffMs > 250L -> {
                                                    // 手机落后 250ms ~ 600ms：中度快进 1.08x 追赶
                                                    PlaybackManager.setPlaybackSpeed(1.08f)
                                                }
                                                diffMs > 45L -> {
                                                    // 手机落后 45ms ~ 250ms：轻度快进 1.04x 追赶
                                                    PlaybackManager.setPlaybackSpeed(1.04f)
                                                }
                                                diffMs < -600L -> {
                                                    // 手机超前 600ms ~ 1800ms：快速拉平 0.84x 等待 TV
                                                    PlaybackManager.setPlaybackSpeed(0.84f)
                                                }
                                                diffMs < -250L -> {
                                                    // 手机超前 250ms ~ 600ms：中度等待 0.90x 等待 TV
                                                    PlaybackManager.setPlaybackSpeed(0.90f)
                                                }
                                                diffMs < -45L -> {
                                                    // 手机超前 45ms ~ 250ms：轻度等待 0.96x 等待 TV
                                                    PlaybackManager.setPlaybackSpeed(0.96f)
                                                }
                                                else -> {
                                                    // 时差已收敛在人耳容忍窗口（45ms）内，恢复 1.0x 正常倍速
                                                    PlaybackManager.resetPlaybackSpeed()
                                                }
                                            }
                                        } else {
                                            PlaybackManager.resetPlaybackSpeed()
                                        }
                                    }
                                } else {
                                    PlaybackManager.resetPlaybackSpeed()
                                }
                            }
                            val curMid = tvSong?.songMid.orEmpty()
                            if (curMid.isNotBlank() && !curMid.startsWith("webdav_")) {
                                val localFav = PlaybackManager.isSongFavorite(curMid)
                                if (localFav != resolvedState.isFavorite) {
                                    PlaybackManager.setSongFavoriteState(curMid, resolvedState.isFavorite)
                                }
                            }
                        }
                    } else {
                        // TV 暂无播放曲目
                        if (PlaybackManager.isRemoteActive.value) {
                            PlaybackManager.clearRemotePlayback()
                        }
                    }
                }
            }
        }

        scope.launch {
            client.queueState.collect { qState ->
                if (qState != null && remoteControlMode.value == RemoteControlMode.TAKEOVER && isTvOnline) {
                    if (PlaybackManager.isRemoteActive.value || tvPlayerState.value?.isPlaying == true) {
                        val resolvedQueue = qState.queue.map { resolveWebDavCoverLocally(it) }
                        PlaybackManager.syncRemoteQueue(resolvedQueue, qState.currentIndex)
                    }
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

        scope.launch {
            client.lyricsSyncFlow.collect { payload ->
                PlaybackManager.setExternalLyrics(payload.songMid, payload.lyrics)
            }
        }

        scope.launch {
            PlaybackManager.lyricsLoadedFlow.collect { (song, lyrics) ->
                val c = connectClient ?: return@collect
                if (!org.melodist.data.LyricCacheManager
                        .isRemoteSynced(song.songMid) &&
                    isTvOnline
                ) {
                    c.sendLyricsSync(
                        org.melodist.core.connect.model.LyricsSyncPayload(
                            songMid = song.songMid,
                            title = song.name,
                            singer = song.singer,
                            lyrics = lyrics,
                            sourceDeviceId = storageManager?.getOrCreateLocalDevice()?.id.orEmpty(),
                        ),
                    )
                }
            }
        }

        scope.launch {
            client.commandsFlow.collect { cmd ->
                when (cmd) {
                    is org.melodist.core.connect.client.MobileIncomingCommand.Next -> {
                        tvNext()
                    }
                    is org.melodist.core.connect.client.MobileIncomingCommand.Previous -> {
                        tvPrev()
                    }
                    is org.melodist.core.connect.client.MobileIncomingCommand.PlaySong -> {
                        val targetSong = cmd.song ?: return@collect
                        playOnTv(targetSong)
                    }
                    is org.melodist.core.connect.client.MobileIncomingCommand.CycleLoopMode -> {
                        PlaybackManager.cycleLoopMode()
                    }
                    is org.melodist.core.connect.client.MobileIncomingCommand.Pause -> {
                        PlaybackManager.pause()
                    }
                    is org.melodist.core.connect.client.MobileIncomingCommand.Resume -> {
                        PlaybackManager.play()
                    }
                }
            }
        }

        startAutoConnectLoop()
    }

    private suspend fun isPortReachable(
        host: String,
        port: Int,
        timeoutMs: Int = 500,
    ): Boolean =
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

    private suspend fun findReachablePort(
        host: String,
        basePort: Int = 8765,
        timeoutMs: Int = 400,
    ): Int? =
        withContext(Dispatchers.IO) {
            if (host.isBlank()) return@withContext null
            if (isPortReachable(host, basePort, timeoutMs)) {
                return@withContext basePort
            }
            for (p in (basePort + 1)..(basePort + 10)) {
                if (isPortReachable(host, p, timeoutMs)) {
                    return@withContext p
                }
            }
            null
        }

    fun resetAndRetryAutoConnect() {
        userManuallyDisconnected = false
        autoConnectFailureCount = 0
        startAutoConnectLoop()
    }

    private fun startAutoConnectLoop() {
        autoConnectJob?.cancel()
        autoConnectJob =
            scope.launch {
                // 1. 监听 NSD 发现的设备：当监听到最近成功连接的设备或已配对设备时，主动发起连接
                launch {
                    nsdHelper?.discoveredDevices?.collect { devices ->
                        if (userManuallyDisconnected) return@collect
                        val state = connectionState.value
                        if (state is MobileConnectionState.Connected || state is MobileConnectionState.Paired || state is MobileConnectionState.Connecting) {
                            return@collect
                        }
                        val lastDevice = storageManager?.getLastConnectedDevice()
                        val matched =
                            if (lastDevice != null) {
                                devices.find { it.id == lastDevice.id }
                            } else {
                                devices.find { storageManager?.isDevicePaired(it.id) == true }
                            }
                        if (matched != null) {
                            autoConnectFailureCount = 0
                            android.util.Log.i(
                                "MobileConnectManager",
                                "Auto-connecting to discovered device: ${matched.name} (${matched.host}:${matched.port})",
                            )
                            connectClient?.connect(matched)
                        }
                    }
                }

                // 2. 自适应探测重连：带轻量 TCP 端口多候选探测、指数退避与失败上限停止
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

                    // 多端口探测（8765..8775），在线才尝试建立 WebSocket 握手
                    val startPort = if (lastDevice.port in 8765..8775) 8765 else lastDevice.port
                    val reachablePort = findReachablePort(lastDevice.host, startPort, timeoutMs = 400)
                    if (reachablePort != null) {
                        val target = if (reachablePort != lastDevice.port) lastDevice.copy(port = reachablePort) else lastDevice
                        android.util.Log.i("MobileConnectManager", "Device port reachable ($reachablePort), auto-connecting: ${target.name}")
                        connectClient?.connect(target)
                        // 等待连接握手结果
                        delay(4000L)
                    } else {
                        autoConnectFailureCount++
                        val backoff = AUTO_CONNECT_BACKOFF_DELAYS.getOrElse(autoConnectFailureCount - 1) { 60000L }
                        android.util.Log.d(
                            "MobileConnectManager",
                            "Device ${lastDevice.host} unreachable on ports $startPort..${startPort + 10}, probeFailure=$autoConnectFailureCount, nextBackoff=${backoff}ms",
                        )
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

    fun connectTo(
        device: ConnectDevice,
        pinCode: String = "",
    ) {
        userManuallyDisconnected = false
        scope.launch {
            val startPort = if (device.port in 8765..8775) 8765 else device.port
            val reachablePort = findReachablePort(device.host, startPort, timeoutMs = 400)
            val effectiveDevice = if (reachablePort != null) device.copy(port = reachablePort) else device
            connectClient?.connect(effectiveDevice, pinCode)
        }
    }

    fun connectByQrJson(qrJson: String): Boolean =
        try {
            val data = json.decodeFromString<QrPairData>(qrJson)
            val device =
                ConnectDevice(
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

    fun disconnect() {
        userManuallyDisconnected = true
        connectClient?.disconnect()
        _tvPlayerState.value = null
        if (PlaybackManager.isRemoteActive.value) {
            PlaybackManager.setRemoteActive(false, null)
            PlaybackManager.clearRemotePlayback()
        }
        PlaybackManager.setVolume(1f)
    }

    fun setLocalMute(enabled: Boolean) {
        storageManager?.setLocalMute(enabled)
        if (remoteControlMode.value == RemoteControlMode.TAKEOVER) {
            PlaybackManager.setVolume(1f)
            if (enabled) {
                // 开启静音：停止真实音频流，若 TV 处于播放状态则启动静音保活
                isSyncingFromTv = true
                try {
                    val state = tvPlayerState.value
                    if (PlaybackManager.isRemoteActive.value && state?.isPlaying == true && state.currentSong != null) {
                        PlaybackManager.startSilentKeepAlive()
                    } else {
                        PlaybackManager.stopSilentKeepAlive()
                        PlaybackManager.pause()
                    }
                } finally {
                    isSyncingFromTv = false
                }
            } else {
                // 关闭静音：停止静音保活，若 TV 处于播放状态则驱动本地发声
                PlaybackManager.stopSilentKeepAlive()
                PlaybackManager.resetPlaybackSpeed()
                val state = tvPlayerState.value
                val tvSong = state?.currentSong
                if (PlaybackManager.isRemoteActive.value && state?.isPlaying == true && tvSong != null) {
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
            PlaybackManager.setVolume(1f)
        } else if (isTvOnline) {
            // 用户切换至接管模式：若手机端正在播放，暂停本地播放进度，保留当前播放位置
            if (PlaybackManager.isPlaying.value && !PlaybackManager.isRemoteActive.value) {
                isSyncingFromTv = true
                try {
                    PlaybackManager.pause()
                } finally {
                    isSyncingFromTv = false
                }
            }
            if (localMute.value) {
                PlaybackManager.setVolume(0f)
            } else {
                PlaybackManager.setVolume(1f)
            }
            val state = tvPlayerState.value
            // 仅在 TV 正在播放时，进入接管模式才立即接管本地播放器；若 TV 处于暂停，保留手机本地当前曲目
            if (state?.isPlaying == true) {
                val pairedName = (connectionState.value as? MobileConnectionState.Paired)?.targetDevice?.name
                appContext?.let { PlaybackManager.startPlaybackService(it) }
                PlaybackManager.setRemoteActive(true, pairedName)
                PlaybackManager.syncRemotePlaybackState(
                    song = state.currentSong,
                    isPlaying = state.isPlaying,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    currentIndex = state.currentIndex,
                    loopModeName = state.loopMode,
                    prevSong = state.prevSong,
                    nextSong = state.nextSong,
                    currentTier = state.currentTier,
                    availableTiers = state.availableTiers,
                    isRadioMode = state.isRadioMode,
                    lyricOffsetMs = state.lyricOffsetMs,
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

    fun playOnTv(
        song: Song,
        startPositionMs: Long = 0L,
        forceTier: AudioQualityTier? = null,
    ) {
        scope.launch(Dispatchers.Main) {
            val currentPlaylist = PlaybackManager.playlist.value
            val idx = currentPlaylist.indexOfFirst { it.songMid == song.songMid }.coerceAtLeast(0)
            val effectiveTier = forceTier ?: PlaybackManager.preferredTier.value

            val (audioSource, effectiveSong, preparedQueue) =
                withContext(Dispatchers.IO) {
                    val src = resolveAudioSource(song)
                    val effSong = prepareSongForTv(song)
                    val prepQueue = currentPlaylist.map { prepareSongForTv(it) }
                    Triple(src, effSong, prepQueue)
                }

            connectClient?.playSong(
                song = effectiveSong,
                queue = preparedQueue,
                index = idx,
                startPositionMs = startPositionMs,
                audioSource = audioSource,
                qualityTier = effectiveTier,
            )
            if (remoteControlMode.value == RemoteControlMode.TAKEOVER) {
                val pairedName = (connectionState.value as? MobileConnectionState.Paired)?.targetDevice?.name
                appContext?.let { PlaybackManager.startPlaybackService(it) }
                PlaybackManager.setRemoteActive(true, pairedName)
                isSyncingFromTv = true
                try {
                    PlaybackManager.pause()
                } finally {
                    isSyncingFromTv = false
                }
            } else if (localMute.value && remoteControlMode.value == RemoteControlMode.BROWSE) {
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

    fun tvTogglePlayPause() {
        if (tvPlayerState.value?.isPlaying == true) {
            tvPause()
        } else {
            tvResume()
        }
    }

    fun tvNext() = connectClient?.next()

    fun tvPrev() = connectClient?.previous()

    fun tvPlayPrevious() = tvPrev()

    fun tvPlayNext() = tvNext()

    fun tvSeekTo(positionMs: Long) = connectClient?.seekTo(positionMs)

    fun tvSetVolume(volume: Float) = connectClient?.setVolume(volume)

    fun tvCycleLoopMode() = connectClient?.cycleLoopMode()

    fun tvSwitchTier(tier: AudioQualityTier) = connectClient?.switchTier(tier)

    fun tvOpenPlayer() = connectClient?.openPlayer()

    fun triggerTvAod() = connectClient?.triggerAod()

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

    private var lastLyricsScrollSendTime = 0L

    fun sendLyricsScroll(
        lineIndex: Int,
        isUserScrolling: Boolean,
    ) {
        if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) return
        val now = System.currentTimeMillis()
        if (isUserScrolling) {
            if (now - lastLyricsScrollSendTime < 30L) return
            lastLyricsScrollSendTime = now
        } else {
            lastLyricsScrollSendTime = 0L
        }
        connectClient?.syncLyricsScroll(lineIndex, isUserScrolling)
    }

    private fun setupPlaybackInterceptor() {
        PlaybackManager.playbackInterceptor =
            object : PlaybackInterceptor {
                override fun onInterceptPlaySong(
                    song: Song,
                    forceTier: AudioQualityTier?,
                    seekToMs: Long,
                ): Boolean {
                    if (isSyncingFromTv) return false
                    if (!isTvOnline || remoteControlMode.value != RemoteControlMode.TAKEOVER) {
                        return false
                    }
                    val localSong = PlaybackManager.currentSong.value
                    val effectiveSeekMs =
                        if (seekToMs > 0L) {
                            seekToMs
                        } else if (!PlaybackManager.isRemoteActive.value && localSong?.songMid == song.songMid) {
                            PlaybackManager.currentPositionMs.value
                        } else {
                            0L
                        }
                    playOnTv(song, startPositionMs = effectiveSeekMs, forceTier = forceTier)
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
                    if (PlaybackManager.isRemoteActive.value) {
                        val isPlaying = tvPlayerState.value?.isPlaying == true
                        if (isPlaying) {
                            tvPause()
                        } else {
                            tvResume()
                        }
                    } else {
                        val localSong = PlaybackManager.currentSong.value
                        if (localSong != null) {
                            val localPos = PlaybackManager.currentPositionMs.value
                            playOnTv(localSong, startPositionMs = localPos)
                        } else {
                            val tvSong = tvPlayerState.value?.currentSong
                            if (tvSong != null) {
                                tvResume()
                            }
                        }
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
                    if (PlaybackManager.isRemoteActive.value) {
                        tvResume()
                    } else {
                        val localSong = PlaybackManager.currentSong.value
                        if (localSong != null) {
                            val localPos = PlaybackManager.currentPositionMs.value
                            playOnTv(localSong, startPositionMs = localPos)
                        } else {
                            tvResume()
                        }
                    }
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
        val server = streamServer ?: return resolveWebDavCoverLocally(song)
        var updated = resolveWebDavCoverLocally(song)
        val coverUrl = updated.coverUrl
        if (coverUrl.isNotBlank() && (coverUrl.startsWith("file://") || coverUrl.startsWith("/"))) {
            updated = updated.copy(coverUrl = server.buildLocalCoverUrl(coverUrl))
        }
        if (updated.isLocal || updated.songMid.startsWith("local_")) {
            if (updated.mediaMid.startsWith("http://") || updated.mediaMid.startsWith("https://")) {
                return updated
            }
            val localPath =
                updated.localFilePath.takeIf { !it.isNullOrBlank() }
                    ?: org.melodist.data.LocalMusicManager
                        .getScannedSongs()
                        .find { it.songMid == song.songMid }
                        ?.localFilePath
            if (!localPath.isNullOrBlank()) {
                val streamUrl = server.buildLocalAudioStreamUrl(localPath)
                updated =
                    updated.copy(
                        localFilePath = localPath,
                        mediaMid = streamUrl,
                    )
            }
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
        val servers =
            org.melodist.data.WebDavManager
                .getServers()
        val server =
            servers.find { it.id == serverId }
                ?: org.melodist.data.WebDavManager
                    .getActiveServer()
                ?: servers.firstOrNull()
                ?: return song
        if (href.isNotBlank()) {
            val cachedCover =
                org.melodist.data.WebDavManager
                    .getSongCoverPath(server.id, href)
            if (!cachedCover.isNullOrBlank()) {
                return song.copy(coverUrl = cachedCover)
            }
        }
        return song
    }

    private fun resolveAudioSource(song: Song): AudioSourceDescriptor {
        if (song.mediaMid.startsWith("http://") || song.mediaMid.startsWith("https://")) {
            return AudioSourceDescriptor(
                sourceType = AudioSourceType.STREAM_PROXY,
                streamUrl = song.mediaMid,
            )
        }
        val isOfflineMode = tvOfflineProxy.value
        val server = streamServer

        val filePath =
            song.localFilePath
                ?: if (song.isLocal || song.songMid.startsWith("local_")) {
                    org.melodist.data.LocalMusicManager
                        .getScannedSongs()
                        .find { it.songMid == song.songMid }
                        ?.localFilePath
                } else {
                    null
                }

        if ((song.isLocal || song.songMid.startsWith("local_") || !filePath.isNullOrBlank()) && !filePath.isNullOrBlank() && server != null) {
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
