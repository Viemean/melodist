package org.melodist.core.connect.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.melodist.core.connect.model.AudioSourceDescriptor
import org.melodist.core.connect.model.ConnectActions
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.ConnectMessage
import org.melodist.core.connect.model.EnqueueNextCommand
import org.melodist.core.connect.model.PairRequestPayload
import org.melodist.core.connect.model.PairResponsePayload
import org.melodist.core.connect.model.PlaySongCommand
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.core.connect.model.QueueStateEvent
import org.melodist.core.connect.model.SeekCommand
import org.melodist.core.connect.model.SetVolumeCommand
import org.melodist.core.connect.storage.ConnectStorageManager
import org.melodist.model.Song
import java.util.concurrent.TimeUnit

sealed interface MobileConnectionState {
    data object Disconnected : MobileConnectionState
    data object Connecting : MobileConnectionState
    data object Connected : MobileConnectionState
    data class Paired(val targetDevice: ConnectDevice) : MobileConnectionState
    data class Reconnecting(
        val targetDevice: ConnectDevice,
        val attempt: Int,
        val maxAttempts: Int = 5,
    ) : MobileConnectionState
    data class Error(val message: String) : MobileConnectionState
}

sealed interface MobileIncomingCommand {
    data object Next : MobileIncomingCommand
    data object Previous : MobileIncomingCommand
    data class PlaySong(val song: Song?) : MobileIncomingCommand
    data object CycleLoopMode : MobileIncomingCommand
    data object Pause : MobileIncomingCommand
    data object Resume : MobileIncomingCommand
}

class MobileConnectClient(
    private val storageManager: ConnectStorageManager,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .build(),
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var activeSocket: WebSocket? = null
    private var currentTarget: ConnectDevice? = null
    private var isManualDisconnect = false
    private var lastPinCode: String = ""
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow<MobileConnectionState>(MobileConnectionState.Disconnected)
    val connectionState: StateFlow<MobileConnectionState> = _connectionState.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerStateEvent?>(null)
    val playerState: StateFlow<PlayerStateEvent?> = _playerState.asStateFlow()

    private val _queueState = MutableStateFlow<QueueStateEvent?>(null)
    val queueState: StateFlow<QueueStateEvent?> = _queueState.asStateFlow()

    private val _commandsFlow = MutableSharedFlow<MobileIncomingCommand>(extraBufferCapacity = 32)
    val commandsFlow: SharedFlow<MobileIncomingCommand> = _commandsFlow.asSharedFlow()

    private val _lyricsSyncFlow = MutableSharedFlow<org.melodist.core.connect.model.LyricsSyncPayload>(extraBufferCapacity = 16)
    val lyricsSyncFlow: SharedFlow<org.melodist.core.connect.model.LyricsSyncPayload> = _lyricsSyncFlow.asSharedFlow()

    fun connect(targetDevice: ConnectDevice, pinCode: String = "") {
        isManualDisconnect = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocket()
        currentTarget = targetDevice
        lastPinCode = pinCode
        _connectionState.value = MobileConnectionState.Connecting
        startSocket(targetDevice, pinCode)
    }

    private fun startSocket(targetDevice: ConnectDevice, pinCode: String) {
        val url = "ws://${targetDevice.host}:${targetDevice.port}"
        android.util.Log.i("MelodistConnectClient", "Connecting to $url (deviceName: ${targetDevice.name})")
        val request = Request.Builder().url(url).build()

        activeSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    android.util.Log.i("MelodistConnectClient", "Connected to $url successfully")
                    reconnectAttempt = 0
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _connectionState.value = MobileConnectionState.Connected
                    val local = storageManager.getOrCreateLocalDevice()
                    sendData(
                        ConnectActions.PAIR_REQUEST,
                        PairRequestPayload(
                            device = local,
                            pinCode = pinCode,
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    android.util.Log.d("MelodistConnectClient", "Received message from TV: $text")
                    handleIncomingMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    android.util.Log.i("MelodistConnectClient", "WebSocket closed: $code, reason: $reason")
                    handleDisconnectionOrScheduleReconnect(targetDevice, isFailure = false, errorMsg = null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    android.util.Log.e("MelodistConnectClient", "WebSocket connect failed to $url: ${t.message}", t)
                    val friendlyMsg = if (targetDevice.host.startsWith("10.0.2.")) {
                        "目标 IP (${targetDevice.host}) 是模拟器私有地址，外部手机无法直连。请在 TV 端切换物理网卡 IP 或使用手动连接输入电脑 Wi-Fi IP"
                    } else {
                        t.message ?: "连接失败"
                    }
                    handleDisconnectionOrScheduleReconnect(targetDevice, isFailure = true, errorMsg = friendlyMsg)
                }
            },
        )
    }

    private fun handleDisconnectionOrScheduleReconnect(
        targetDevice: ConnectDevice,
        isFailure: Boolean,
        errorMsg: String?,
    ) {
        if (isManualDisconnect) {
            _connectionState.value = MobileConnectionState.Disconnected
            _playerState.value = null
            return
        }

        val hasPairedBefore = storageManager.isDevicePaired(targetDevice.id)
        if (reconnectAttempt < maxReconnectAttempts && hasPairedBefore) {
            reconnectAttempt++
            val currentAttempt = reconnectAttempt
            _connectionState.value = MobileConnectionState.Reconnecting(
                targetDevice = targetDevice,
                attempt = currentAttempt,
                maxAttempts = maxReconnectAttempts,
            )
            val delayMs = (1L shl (currentAttempt - 1)) * 1000L
            android.util.Log.i("MelodistConnectClient", "Scheduling reconnect attempt $currentAttempt in ${delayMs}ms to ${targetDevice.name}")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                if (!isManualDisconnect && currentTarget?.id == targetDevice.id) {
                    startSocket(targetDevice, lastPinCode)
                }
            }
        } else {
            reconnectAttempt = 0
            if (isFailure && errorMsg != null) {
                _connectionState.value = MobileConnectionState.Error(errorMsg)
            } else {
                _connectionState.value = MobileConnectionState.Disconnected
            }
            _playerState.value = null
        }
    }

    private fun closeSocket() {
        activeSocket?.let {
            try {
                it.close(1000, "Normal closure")
            } catch (_: Exception) {}
        }
        activeSocket = null
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        activeSocket?.let {
            val local = storageManager.getOrCreateLocalDevice()
            try {
                val msg = json.encodeToString(
                    ConnectMessage(action = ConnectActions.DISCONNECT, payload = local.id),
                )
                it.send(msg)
                it.close(1000, "Disconnect requested")
            } catch (_: Exception) {}
        }
        activeSocket = null
        currentTarget = null
        _connectionState.value = MobileConnectionState.Disconnected
        _playerState.value = null
        _queueState.value = null
    }

    fun requestPlayerState() {
        sendAction(ConnectActions.REQ_GET_PLAYER_STATE)
    }

    fun requestQueueState() {
        sendAction(ConnectActions.REQ_GET_QUEUE_STATE)
    }

    fun sendLyricsSync(payload: org.melodist.core.connect.model.LyricsSyncPayload) {
        sendData(ConnectActions.CMD_SYNC_LYRICS, payload)
    }

    fun playSong(
        song: Song,
        queue: List<Song> = emptyList(),
        index: Int = 0,
        startPositionMs: Long = 0L,
        audioSource: AudioSourceDescriptor? = null,
        qualityTier: org.melodist.model.AudioQualityTier? = null,
    ) {
        val cmd = PlaySongCommand(
            song = song,
            queue = queue,
            index = index,
            startPositionMs = startPositionMs,
            audioSource = audioSource,
            qualityTier = qualityTier,
        )
        sendData(ConnectActions.CMD_PLAY_SONG, cmd)
    }

    fun enqueueNext(song: Song, audioSource: AudioSourceDescriptor? = null) {
        val cmd = EnqueueNextCommand(song = song, audioSource = audioSource)
        sendData(ConnectActions.CMD_ENQUEUE_NEXT, cmd)
    }

    fun switchTier(tier: org.melodist.model.AudioQualityTier) {
        val cmd = org.melodist.core.connect.model.SwitchTierCommand(tier = tier)
        sendData(ConnectActions.CMD_SWITCH_TIER, cmd)
    }

    fun pause() {
        sendAction(ConnectActions.CMD_PAUSE)
    }

    fun resume() {
        sendAction(ConnectActions.CMD_RESUME)
    }

    fun previous() {
        sendAction(ConnectActions.CMD_PREVIOUS)
    }

    fun next() {
        sendAction(ConnectActions.CMD_NEXT)
    }

    fun seekTo(positionMs: Long) {
        val cmd = SeekCommand(positionMs = positionMs)
        sendData(ConnectActions.CMD_SEEK, cmd)
    }

    fun setVolume(volume: Float) {
        val cmd = SetVolumeCommand(volume = volume.coerceIn(0f, 1f))
        sendData(ConnectActions.CMD_SET_VOLUME, cmd)
    }

    fun triggerAod() {
        sendAction(ConnectActions.CMD_TRIGGER_AOD)
    }

    fun cycleLoopMode() {
        sendAction(ConnectActions.CMD_CYCLE_LOOP_MODE)
    }

    fun openPlayer() {
        sendAction(ConnectActions.CMD_OPEN_PLAYER)
    }

    fun sendGestureSwipe(
        state: org.melodist.core.connect.model.GestureSwipeState,
        fraction: Float = 0f,
        targetFraction: Float = 0f,
        durationMs: Long = 200L,
    ) {
        val payload = org.melodist.core.connect.model.GestureSwipePayload(
            state = state,
            fraction = fraction,
            targetFraction = targetFraction,
            durationMs = durationMs,
        )
        sendData(ConnectActions.CMD_GESTURE_SWIPE, payload)
    }

    fun toggleFavorite(
        song: Song? = null,
        songMid: String = "",
        isFavorite: Boolean = false,
    ) {
        val effectiveMid = songMid.ifBlank { song?.songMid.orEmpty() }
        val cmd = org.melodist.core.connect.model.ToggleFavoriteCommand(
            song = song,
            songMid = effectiveMid,
            isFavorite = isFavorite,
        )
        sendData(ConnectActions.CMD_TOGGLE_FAVORITE, cmd)
    }

    fun syncLyricsScroll(lineIndex: Int, isUserScrolling: Boolean) {
        val payload = org.melodist.core.connect.model.LyricsScrollPayload(
            lineIndex = lineIndex,
            isUserScrolling = isUserScrolling,
        )
        sendData(ConnectActions.CMD_SYNC_LYRICS_SCROLL, payload)
    }

    private inline fun <reified T> sendData(action: String, data: T) {
        val socket = activeSocket ?: return
        val message = json.encodeToString(ConnectMessage.create(action, data, json))
        try {
            socket.send(message)
        } catch (_: Exception) {}
    }

    private fun sendAction(action: String) {
        val socket = activeSocket ?: return
        val message = json.encodeToString(ConnectMessage(action = action))
        try {
            socket.send(message)
        } catch (_: Exception) {}
    }

    private fun handleIncomingMessage(text: String) {
        val msg = try {
            json.decodeFromString<ConnectMessage>(text)
        } catch (_: Exception) {
            return
        }

        when (msg.action) {
            ConnectActions.PAIR_RESPONSE -> {
                val resp = msg.decodeData<PairResponsePayload>(json) ?: return
                if (resp.accepted && resp.device != null) {
                    val target = currentTarget ?: resp.device
                    val updated = target.copy(token = resp.device.token)
                    storageManager.savePairedDevice(updated)
                    storageManager.setLastConnectedDevice(updated)
                    _connectionState.value = MobileConnectionState.Paired(updated)
                    requestPlayerState()
                    requestQueueState()
                } else {
                    _connectionState.value = MobileConnectionState.Error(resp.message.ifBlank { "Pairing rejected" })
                }
            }
            ConnectActions.EVENT_PLAY_STATE -> {
                val state = msg.decodeData<PlayerStateEvent>(json)
                if (state != null) {
                    _playerState.value = state
                }
            }
            ConnectActions.EVENT_QUEUE_STATE -> {
                val queue = msg.decodeData<QueueStateEvent>(json)
                if (queue != null) {
                    _queueState.value = queue
                }
            }
            ConnectActions.EVENT_SYNC_LYRICS -> {
                val payload = msg.decodeData<org.melodist.core.connect.model.LyricsSyncPayload>(json)
                if (payload != null) {
                    _lyricsSyncFlow.tryEmit(payload)
                }
            }
            ConnectActions.CMD_NEXT -> {
                _commandsFlow.tryEmit(MobileIncomingCommand.Next)
            }
            ConnectActions.CMD_PREVIOUS -> {
                _commandsFlow.tryEmit(MobileIncomingCommand.Previous)
            }
            ConnectActions.CMD_PLAY_SONG -> {
                val song = msg.decodeData<Song>(json)
                _commandsFlow.tryEmit(MobileIncomingCommand.PlaySong(song))
            }
            ConnectActions.CMD_CYCLE_LOOP_MODE -> {
                _commandsFlow.tryEmit(MobileIncomingCommand.CycleLoopMode)
            }
            ConnectActions.CMD_PAUSE -> {
                _commandsFlow.tryEmit(MobileIncomingCommand.Pause)
            }
            ConnectActions.CMD_RESUME -> {
                _commandsFlow.tryEmit(MobileIncomingCommand.Resume)
            }
        }
    }
}
