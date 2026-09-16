package org.melodist.tv.connect

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.core.connect.discovery.ConnectNsdHelper
import org.melodist.core.connect.model.AudioSourceType
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.DeviceType
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.core.connect.model.QrPairData
import org.melodist.core.connect.model.QueueStateEvent
import org.melodist.core.connect.server.PendingPairRequest
import org.melodist.core.connect.server.TvConnectServer
import org.melodist.core.connect.server.TvIncomingCommand
import org.melodist.core.connect.storage.ConnectStorageManager
import org.melodist.core.connect.util.NetworkUtils
import org.melodist.core.connect.util.QrCodeUtils
import org.melodist.model.AudioQualityTier
import org.melodist.playback.PlaybackManager
import kotlin.random.Random

object TvConnectManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var storageManager: ConnectStorageManager? = null
    private var connectServer: TvConnectServer? = null
    private var nsdHelper: ConnectNsdHelper? = null

    private val _currentPinCode = MutableStateFlow(generateRandomPin())
    val currentPinCode: StateFlow<String> = _currentPinCode.asStateFlow()

    private val _qrBitmapFlow = MutableStateFlow<Bitmap?>(null)
    val qrBitmapFlow: StateFlow<Bitmap?> = _qrBitmapFlow.asStateFlow()

    private val _localIpFlow = MutableStateFlow<String>("")
    val localIpFlow: StateFlow<String> = _localIpFlow.asStateFlow()

    private val _pendingPairFlow = MutableStateFlow<PendingPairRequest?>(null)
    val pendingPairFlow: StateFlow<PendingPairRequest?> = _pendingPairFlow.asStateFlow()

    private val _navigateToPlayerEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToPlayerEvent: SharedFlow<Unit> = _navigateToPlayerEvent.asSharedFlow()

    val connectedDevice: StateFlow<ConnectDevice?>
        get() = connectServer?.connectedDeviceFlow ?: MutableStateFlow(null)

    val pairedDevices: StateFlow<List<ConnectDevice>>
        get() = storageManager?.pairedDevicesFlow ?: MutableStateFlow(emptyList())

    private const val SERVER_PORT = 8765

    fun init(context: Context) {
        if (storageManager != null) return
        val storage = ConnectStorageManager(context, DeviceType.TV)
        storageManager = storage

        val server = TvConnectServer(storage, port = SERVER_PORT)
        connectServer = server

        val nsd = ConnectNsdHelper(context)
        nsdHelper = nsd

        val localDevice = storage.getOrCreateLocalDevice()

        scope.launch(Dispatchers.IO) {
            try {
                server.start()
            } catch (_: Exception) {}

            nsd.registerTvService(
                port = SERVER_PORT,
                device = localDevice,
                pinCode = _currentPinCode.value,
            )
            refreshNetworkAndQr()
        }

        scope.launch {
            server.pendingPairFlow.collect { req ->
                _pendingPairFlow.value = req
            }
        }

        scope.launch {
            server.commandsFlow.collect { cmd ->
                handleIncomingCommand(cmd)
            }
        }

        scope.launch {
            server.connectedDeviceFlow.collect { dev ->
                if (dev != null) {
                    broadcastPlayerStateNow()
                    broadcastQueueNow()
                }
            }
        }

        // 持续同步播放状态给连接端
        scope.launch(Dispatchers.IO) {
            var lastPosition = -1L
            var lastIsPlaying = false
            var lastSongMid = ""
            var lastLoopMode = ""
            var lastAod = false
            var lastTier: AudioQualityTier? = null
            var lastIsFav = false
            var lastIsRadio = false
            while (isActive) {
                val s = connectServer ?: break
                val dev = s.connectedDeviceFlow.value
                if (dev != null) {
                    val currentSong = PlaybackManager.currentSong.value
                    val isPlaying = PlaybackManager.isPlaying.value
                    val pos = PlaybackManager.currentPositionMs.value
                    val dur = PlaybackManager.durationMs.value
                    val loopMode = PlaybackManager.loopMode.value.name
                    val isAod = org.melodist.tv.screensaver.ScreenSaverManager.isScreenSaverActive.value
                    val currentTier = PlaybackManager.currentTier.value
                    val isFav = PlaybackManager.isSongFavorite(currentSong?.songMid)
                    val isRadio = PlaybackManager.isRadioMode.value

                    val songMid = currentSong?.songMid.orEmpty()
                    if (pos != lastPosition || isPlaying != lastIsPlaying || songMid != lastSongMid || loopMode != lastLoopMode || isAod != lastAod || currentTier != lastTier || isFav != lastIsFav || isRadio != lastIsRadio) {
                        lastPosition = pos
                        lastIsPlaying = isPlaying
                        lastSongMid = songMid
                        lastLoopMode = loopMode
                        lastAod = isAod
                        lastTier = currentTier
                        lastIsFav = isFav
                        lastIsRadio = isRadio

                        s.broadcastPlayerState(
                            PlayerStateEvent(
                                currentSong = currentSong,
                                isPlaying = isPlaying,
                                positionMs = pos,
                                durationMs = dur,
                                queueSize = PlaybackManager.playlist.value.size,
                                currentIndex = PlaybackManager.currentIndex.value,
                                loopMode = loopMode,
                                isAodActive = isAod,
                                prevSong = PlaybackManager.getPreviousSong(),
                                nextSong = PlaybackManager.getNextSong(),
                                currentTier = currentTier,
                                availableTiers = PlaybackManager.availableTiers.value,
                                isFavorite = isFav,
                                isRadioMode = isRadio,
                            ),
                        )
                    }
                }
                delay(1000)
            }
        }
    }

    fun refreshPinCode() {
        _currentPinCode.value = generateRandomPin()
        scope.launch(Dispatchers.IO) {
            val storage = storageManager ?: return@launch
            val localDevice = storage.getOrCreateLocalDevice()
            nsdHelper?.registerTvService(
                port = SERVER_PORT,
                device = localDevice,
                pinCode = _currentPinCode.value,
            )
            refreshNetworkAndQr()
        }
    }

    fun setCustomIp(ip: String?) {
        storageManager?.customTvIp = ip?.trim()?.ifBlank { null }
        refreshNetworkAndQr()
    }

    fun getAvailableIps(): List<String> = NetworkUtils.getAvailableIpv4Addresses()

    private fun refreshNetworkAndQr() {
        val storage = storageManager ?: return
        val ip = storage.customTvIp ?: NetworkUtils.getLocalIpv4Address() ?: "127.0.0.1"
        _localIpFlow.value = ip
        val localDevice = storage.getOrCreateLocalDevice(fallbackHost = ip)

        val qrData = QrPairData(
            deviceId = localDevice.id,
            deviceName = localDevice.name,
            host = ip,
            port = SERVER_PORT,
            token = localDevice.token,
            pinCode = _currentPinCode.value,
        )
        val qrJson = json.encodeToString(qrData)
        val bitmap = QrCodeUtils.generateQrBitmap(qrJson, sizePx = 480)
        _qrBitmapFlow.value = bitmap
    }

    fun acceptPairRequest(requestId: String) {
        connectServer?.acceptPairRequest(requestId)
        if (_pendingPairFlow.value?.requestId == requestId) {
            _pendingPairFlow.value = null
        }
    }

    fun rejectPairRequest(requestId: String) {
        connectServer?.rejectPairRequest(requestId)
        if (_pendingPairFlow.value?.requestId == requestId) {
            _pendingPairFlow.value = null
        }
    }

    fun removePairedDevice(deviceId: String) {
        storageManager?.removePairedDevice(deviceId)
    }

    fun clearAllPairedDevices() {
        storageManager?.clearAllPairedDevices()
    }

    private fun handleIncomingCommand(command: TvIncomingCommand) {
        when (command) {
            is TvIncomingCommand.PlaySong -> {
                val cmd = command.command
                val audioSource = cmd.audioSource
                val streamUrl = audioSource?.streamUrl
                if (cmd.queue.isNotEmpty()) {
                    PlaybackManager.setPlaylist(cmd.queue, startIndex = cmd.index, forceTier = cmd.qualityTier)
                }
                if (audioSource?.sourceType == AudioSourceType.STREAM_PROXY && !streamUrl.isNullOrBlank()) {
                    PlaybackManager.playCustomStream(
                        song = cmd.song,
                        streamUrl = streamUrl,
                        headers = audioSource.headers,
                        seekToMs = cmd.startPositionMs,
                    )
                } else {
                    if (cmd.queue.isEmpty()) {
                        PlaybackManager.playSong(cmd.song, forceTier = cmd.qualityTier, seekToMs = cmd.startPositionMs)
                    }
                }
                broadcastQueueNow()
            }
            is TvIncomingCommand.EnqueueNext -> {
                PlaybackManager.insertNextPlay(command.command.song)
                broadcastQueueNow()
            }
            is TvIncomingCommand.SwitchTier -> {
                PlaybackManager.switchTier(command.command.tier)
                broadcastPlayerStateNow()
            }
            is TvIncomingCommand.Pause -> {
                PlaybackManager.pause()
            }
            is TvIncomingCommand.Resume -> {
                PlaybackManager.play()
            }
            is TvIncomingCommand.Previous -> {
                PlaybackManager.playPrevious()
            }
            is TvIncomingCommand.Next -> {
                PlaybackManager.playNext()
            }
            is TvIncomingCommand.Seek -> {
                PlaybackManager.seekTo(command.positionMs)
            }
            is TvIncomingCommand.SetVolume -> {
                PlaybackManager.setVolume(command.volume)
            }
            is TvIncomingCommand.TriggerAod -> {
                if (org.melodist.tv.screensaver.ScreenSaverManager.isScreenSaverActive.value) {
                    org.melodist.tv.screensaver.ScreenSaverManager.dismissScreenSaver()
                } else {
                    org.melodist.tv.screensaver.ScreenSaverManager.triggerScreenSaver()
                }
            }
            is TvIncomingCommand.CycleLoopMode -> {
                PlaybackManager.cycleLoopMode()
                broadcastPlayerStateNow()
            }
            is TvIncomingCommand.OpenPlayer -> {
                _navigateToPlayerEvent.tryEmit(Unit)
            }
            is TvIncomingCommand.GestureSwipe -> {
                if (!org.melodist.tv.screensaver.ScreenSaverManager.isScreenSaverActive.value) {
                    TakeoverGestureState.updateGesture(command.payload)
                }
            }
            is TvIncomingCommand.ToggleFavorite -> {
                val cmd = command.command
                val mid = cmd.songMid.ifBlank { cmd.song?.songMid.orEmpty() }
                if (mid.isNotBlank()) {
                    PlaybackManager.setSongFavoriteState(mid, cmd.isFavorite)
                    broadcastPlayerStateNow()
                }
            }
            is TvIncomingCommand.SyncLyricsScroll -> {
                TakeoverLyricsState.update(command.payload)
            }
        }
    }

    private fun broadcastPlayerStateNow() {
        val s = connectServer ?: return
        val curSong = PlaybackManager.currentSong.value
        s.broadcastPlayerState(
            PlayerStateEvent(
                currentSong = curSong,
                isPlaying = PlaybackManager.isPlaying.value,
                positionMs = PlaybackManager.currentPositionMs.value,
                durationMs = PlaybackManager.durationMs.value,
                queueSize = PlaybackManager.playlist.value.size,
                currentIndex = PlaybackManager.currentIndex.value,
                loopMode = PlaybackManager.loopMode.value.name,
                isAodActive = org.melodist.tv.screensaver.ScreenSaverManager.isScreenSaverActive.value,
                prevSong = PlaybackManager.getPreviousSong(),
                nextSong = PlaybackManager.getNextSong(),
                currentTier = PlaybackManager.currentTier.value,
                availableTiers = PlaybackManager.availableTiers.value,
                isFavorite = PlaybackManager.isSongFavorite(curSong?.songMid),
                isRadioMode = PlaybackManager.isRadioMode.value,
            ),
        )
    }

    private fun broadcastQueueNow() {
        connectServer?.broadcastQueueState(
            QueueStateEvent(
                queue = PlaybackManager.playlist.value,
                currentIndex = PlaybackManager.currentIndex.value,
            ),
        )
    }

    private fun generateRandomPin(): String {
        return "%06d".format(Random.nextInt(100000, 999999))
    }
}
