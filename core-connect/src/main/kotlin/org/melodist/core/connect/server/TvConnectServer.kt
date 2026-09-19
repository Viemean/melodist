package org.melodist.core.connect.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.melodist.core.connect.model.ConnectActions
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.ConnectMessage
import org.melodist.core.connect.model.DeviceType
import org.melodist.core.connect.model.EnqueueNextCommand
import org.melodist.core.connect.model.PairRequestPayload
import org.melodist.core.connect.model.PairResponsePayload
import org.melodist.core.connect.model.PlaySongCommand
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.core.connect.model.QueueStateEvent
import org.melodist.core.connect.model.SeekCommand
import org.melodist.core.connect.model.SetVolumeCommand
import org.melodist.core.connect.storage.ConnectStorageManager
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

sealed interface TvIncomingCommand {
    data class PlaySong(val command: PlaySongCommand) : TvIncomingCommand
    data class EnqueueNext(val command: EnqueueNextCommand) : TvIncomingCommand
    data object Pause : TvIncomingCommand
    data object Resume : TvIncomingCommand
    data object Previous : TvIncomingCommand
    data object Next : TvIncomingCommand
    data class Seek(val positionMs: Long) : TvIncomingCommand
    data class SetVolume(val volume: Float) : TvIncomingCommand
    data class SwitchTier(val command: org.melodist.core.connect.model.SwitchTierCommand) : TvIncomingCommand
    data object TriggerAod : TvIncomingCommand
    data object CycleLoopMode : TvIncomingCommand
    data object OpenPlayer : TvIncomingCommand
    data class GestureSwipe(val payload: org.melodist.core.connect.model.GestureSwipePayload) : TvIncomingCommand
    data class ToggleFavorite(val command: org.melodist.core.connect.model.ToggleFavoriteCommand) : TvIncomingCommand
    data class SyncLyricsScroll(val payload: org.melodist.core.connect.model.LyricsScrollPayload) : TvIncomingCommand
    data object RequestGetPlayerState : TvIncomingCommand
    data object RequestGetQueueState : TvIncomingCommand
    data class SyncLyrics(val payload: org.melodist.core.connect.model.LyricsSyncPayload) : TvIncomingCommand
}

data class PendingPairRequest(
    val requestId: String,
    val device: ConnectDevice,
    val pinCode: String,
    val socket: WebSocket,
)

class TvConnectServer(
    private val storageManager: ConnectStorageManager,
    private val port: Int = 8765,
) {
    var actualPort: Int = port
        private set
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var server: InternalWebSocketServer? = null

    private val _commandsFlow = MutableSharedFlow<TvIncomingCommand>(extraBufferCapacity = 64)
    val commandsFlow: SharedFlow<TvIncomingCommand> = _commandsFlow.asSharedFlow()

    private val _pendingPairFlow = MutableSharedFlow<PendingPairRequest>(extraBufferCapacity = 8)
    val pendingPairFlow: SharedFlow<PendingPairRequest> = _pendingPairFlow.asSharedFlow()

    private val _connectedDeviceFlow = MutableStateFlow<ConnectDevice?>(null)
    val connectedDeviceFlow: StateFlow<ConnectDevice?> = _connectedDeviceFlow.asStateFlow()

    private val activeClients = ConcurrentHashMap<WebSocket, ConnectDevice>()
    private val pendingRequests = ConcurrentHashMap<String, PendingPairRequest>()

    private fun findAvailablePort(startPort: Int): Int {
        for (candidate in startPort..(startPort + 10)) {
            try {
                java.net.ServerSocket(candidate).use {
                    return candidate
                }
            } catch (_: Exception) {}
        }
        return startPort
    }

    fun start() {
        if (server != null) return
        val bindPort = findAvailablePort(port)
        actualPort = bindPort
        val wsServer = InternalWebSocketServer(InetSocketAddress(bindPort))
        server = wsServer
        wsServer.isReuseAddr = true
        wsServer.start()
    }

    fun stop() {
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null
        activeClients.clear()
        pendingRequests.clear()
        _connectedDeviceFlow.value = null
    }

    fun acceptPairRequest(requestId: String) {
        val request = pendingRequests.remove(requestId) ?: return
        storageManager.savePairedDevice(request.device)
        activeClients[request.socket] = request.device
        _connectedDeviceFlow.value = request.device

        val localDevice = storageManager.getOrCreateLocalDevice()
        sendData(
            request.socket,
            ConnectActions.PAIR_RESPONSE,
            PairResponsePayload(
                accepted = true,
                message = "Paired successfully",
                device = localDevice,
            ),
        )
        scope.launch {
            _commandsFlow.emit(TvIncomingCommand.RequestGetPlayerState)
            _commandsFlow.emit(TvIncomingCommand.RequestGetQueueState)
        }
    }

    fun rejectPairRequest(requestId: String) {
        val request = pendingRequests.remove(requestId) ?: return
        sendData(
            request.socket,
            ConnectActions.PAIR_RESPONSE,
            PairResponsePayload(
                accepted = false,
                message = "Pairing rejected by TV user",
                device = null,
            ),
        )
        try {
            request.socket.close()
        } catch (_: Exception) {}
    }

    fun broadcastPlayerState(event: PlayerStateEvent) {
        broadcastData(ConnectActions.EVENT_PLAY_STATE, event)
    }

    fun broadcastQueueState(event: QueueStateEvent) {
        broadcastData(ConnectActions.EVENT_QUEUE_STATE, event)
    }

    fun broadcastLyrics(payload: org.melodist.core.connect.model.LyricsSyncPayload) {
        broadcastData(ConnectActions.EVENT_SYNC_LYRICS, payload)
    }

    fun broadcastNext() {
        broadcastAction(ConnectActions.CMD_NEXT)
    }

    fun broadcastPrevious() {
        broadcastAction(ConnectActions.CMD_PREVIOUS)
    }

    fun broadcastPlaySong(song: org.melodist.model.Song) {
        broadcastData(ConnectActions.CMD_PLAY_SONG, song)
    }

    fun broadcastCycleLoopMode() {
        broadcastAction(ConnectActions.CMD_CYCLE_LOOP_MODE)
    }

    private inline fun <reified T> broadcastData(action: String, data: T) {
        val message = json.encodeToString(ConnectMessage.create(action, data, json))
        activeClients.keys.forEach { ws ->
            if (ws.isOpen) {
                try {
                    ws.send(message)
                } catch (_: Exception) {}
            }
        }
    }

    private fun broadcastAction(action: String) {
        val message = json.encodeToString(ConnectMessage(action = action))
        activeClients.keys.forEach { ws ->
            if (ws.isOpen) {
                try {
                    ws.send(message)
                } catch (_: Exception) {}
            }
        }
    }

    private inline fun <reified T> sendData(socket: WebSocket, action: String, data: T) {
        if (!socket.isOpen) return
        val message = json.encodeToString(ConnectMessage.create(action, data, json))
        try {
            socket.send(message)
        } catch (_: Exception) {}
    }

    private fun sendAction(socket: WebSocket, action: String) {
        if (!socket.isOpen) return
        val message = json.encodeToString(ConnectMessage(action = action))
        try {
            socket.send(message)
        } catch (_: Exception) {}
    }

    private inner class InternalWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            android.util.Log.i("MelodistConnectServer", "Client socket opened from ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            android.util.Log.i("MelodistConnectServer", "Client socket closed: code=$code, reason=$reason, remote=$remote")
            val device = activeClients.remove(conn)
            if (device != null && _connectedDeviceFlow.value?.id == device.id) {
                _connectedDeviceFlow.value = activeClients.values.firstOrNull()
            }
        }

        override fun onMessage(conn: WebSocket, text: String) {
            android.util.Log.d("MelodistConnectServer", "Received message from client: $text")
            handleIncomingMessage(conn, text)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            android.util.Log.e("MelodistConnectServer", "WebSocket error: ${ex.message}", ex)
        }

        override fun onStart() {
            android.util.Log.i("MelodistConnectServer", "TvConnectServer started on port $actualPort")
        }
    }

    private fun handleIncomingMessage(conn: WebSocket, text: String) {
        val msg = try {
            json.decodeFromString<ConnectMessage>(text)
        } catch (_: Exception) {
            return
        }

        when (msg.action) {
            ConnectActions.PING -> {
                sendAction(conn, ConnectActions.PONG)
            }
            ConnectActions.REQ_GET_PLAYER_STATE -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.RequestGetPlayerState) }
            }
            ConnectActions.REQ_GET_QUEUE_STATE -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.RequestGetQueueState) }
            }
            ConnectActions.PAIR_REQUEST -> {
                val req = msg.decodeData<PairRequestPayload>(json) ?: return
                val isTrusted = storageManager.isDevicePaired(req.device.id) &&
                    storageManager.isTokenTrusted(req.device.token)

                if (isTrusted) {
                    activeClients[conn] = req.device
                    _connectedDeviceFlow.value = req.device
                    val local = storageManager.getOrCreateLocalDevice()
                    sendData(
                        conn,
                        ConnectActions.PAIR_RESPONSE,
                        PairResponsePayload(
                            accepted = true,
                            message = "Auto paired",
                            device = local,
                        ),
                    )
                    scope.launch {
                        _commandsFlow.emit(TvIncomingCommand.RequestGetPlayerState)
                        _commandsFlow.emit(TvIncomingCommand.RequestGetQueueState)
                    }
                } else {
                    val pending = PendingPairRequest(
                        requestId = msg.id,
                        device = req.device,
                        pinCode = req.pinCode,
                        socket = conn,
                    )
                    pendingRequests[msg.id] = pending
                    scope.launch {
                        _pendingPairFlow.emit(pending)
                    }
                }
            }
            ConnectActions.DISCONNECT -> {
                activeClients.remove(conn)
                if (_connectedDeviceFlow.value?.id == msg.payload) {
                    _connectedDeviceFlow.value = activeClients.values.firstOrNull()
                }
                try {
                    conn.close()
                } catch (_: Exception) {}
            }
            ConnectActions.CMD_PLAY_SONG -> {
                val cmd = msg.decodeData<PlaySongCommand>(json)
                if (cmd != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.PlaySong(cmd)) }
                }
            }
            ConnectActions.CMD_ENQUEUE_NEXT -> {
                val cmd = msg.decodeData<EnqueueNextCommand>(json)
                if (cmd != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.EnqueueNext(cmd)) }
                }
            }
            ConnectActions.CMD_PAUSE -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.Pause) }
            }
            ConnectActions.CMD_RESUME -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.Resume) }
            }
            ConnectActions.CMD_PREVIOUS -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.Previous) }
            }
            ConnectActions.CMD_NEXT -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.Next) }
            }
            ConnectActions.CMD_SEEK -> {
                val cmd = msg.decodeData<SeekCommand>(json)
                if (cmd != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.Seek(cmd.positionMs)) }
                }
            }
            ConnectActions.CMD_SET_VOLUME -> {
                val cmd = msg.decodeData<SetVolumeCommand>(json)
                if (cmd != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SetVolume(cmd.volume)) }
                }
            }
            ConnectActions.CMD_SWITCH_TIER -> {
                val cmd = msg.decodeData<org.melodist.core.connect.model.SwitchTierCommand>(json)
                if (cmd != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SwitchTier(cmd)) }
                }
            }
            ConnectActions.CMD_TRIGGER_AOD -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.TriggerAod) }
            }
            ConnectActions.CMD_CYCLE_LOOP_MODE -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.CycleLoopMode) }
            }
            ConnectActions.CMD_OPEN_PLAYER -> {
                scope.launch { _commandsFlow.emit(TvIncomingCommand.OpenPlayer) }
            }
            ConnectActions.CMD_GESTURE_SWIPE -> {
                val payload = msg.decodeData<org.melodist.core.connect.model.GestureSwipePayload>(json)
                if (payload != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.GestureSwipe(payload)) }
                }
            }
            ConnectActions.CMD_TOGGLE_FAVORITE -> {
                val cmd = msg.decodeData<org.melodist.core.connect.model.ToggleFavoriteCommand>(json)
                if (cmd != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.ToggleFavorite(cmd)) }
                }
            }
            ConnectActions.CMD_SYNC_LYRICS_SCROLL -> {
                val payload = msg.decodeData<org.melodist.core.connect.model.LyricsScrollPayload>(json)
                if (payload != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SyncLyricsScroll(payload)) }
                }
            }
            ConnectActions.CMD_SYNC_LYRICS -> {
                val payload = msg.decodeData<org.melodist.core.connect.model.LyricsSyncPayload>(json)
                if (payload != null) {
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SyncLyrics(payload)) }
                }
            }
        }
    }
}
