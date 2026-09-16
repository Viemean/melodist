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

    fun start() {
        if (server != null) return
        val localDevice = storageManager.getOrCreateLocalDevice()
        val wsServer = InternalWebSocketServer(InetSocketAddress(port))
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
        val payload = json.encodeToString(
            PairResponsePayload(
                accepted = true,
                message = "Paired successfully",
                device = localDevice,
            ),
        )
        sendMessage(request.socket, ConnectActions.PAIR_RESPONSE, payload)
    }

    fun rejectPairRequest(requestId: String) {
        val request = pendingRequests.remove(requestId) ?: return
        val payload = json.encodeToString(
            PairResponsePayload(
                accepted = false,
                message = "Pairing rejected by TV user",
                device = null,
            ),
        )
        sendMessage(request.socket, ConnectActions.PAIR_RESPONSE, payload)
        try {
            request.socket.close()
        } catch (_: Exception) {}
    }

    fun broadcastPlayerState(event: PlayerStateEvent) {
        val payload = json.encodeToString(event)
        broadcast(ConnectActions.EVENT_PLAY_STATE, payload)
    }

    fun broadcastQueueState(event: QueueStateEvent) {
        val payload = json.encodeToString(event)
        broadcast(ConnectActions.EVENT_QUEUE_STATE, payload)
    }

    private fun broadcast(action: String, payload: String) {
        val message = json.encodeToString(ConnectMessage(action = action, payload = payload))
        activeClients.keys.forEach { ws ->
            if (ws.isOpen) {
                try {
                    ws.send(message)
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendMessage(socket: WebSocket, action: String, payload: String) {
        if (!socket.isOpen) return
        val message = json.encodeToString(ConnectMessage(action = action, payload = payload))
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
            android.util.Log.i("MelodistConnectServer", "TvConnectServer started on port $port")
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
                sendMessage(conn, ConnectActions.PONG, "")
            }
            ConnectActions.PAIR_REQUEST -> {
                val req = try {
                    json.decodeFromString<PairRequestPayload>(msg.payload)
                } catch (_: Exception) {
                    return
                }
                val isTrusted = storageManager.isDevicePaired(req.device.id) &&
                    storageManager.isTokenTrusted(req.device.token)

                if (isTrusted) {
                    activeClients[conn] = req.device
                    _connectedDeviceFlow.value = req.device
                    val local = storageManager.getOrCreateLocalDevice()
                    val payload = json.encodeToString(
                        PairResponsePayload(
                            accepted = true,
                            message = "Auto paired",
                            device = local,
                        ),
                    )
                    sendMessage(conn, ConnectActions.PAIR_RESPONSE, payload)
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
                try {
                    val cmd = json.decodeFromString<PlaySongCommand>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.PlaySong(cmd)) }
                } catch (_: Exception) {}
            }
            ConnectActions.CMD_ENQUEUE_NEXT -> {
                try {
                    val cmd = json.decodeFromString<EnqueueNextCommand>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.EnqueueNext(cmd)) }
                } catch (_: Exception) {}
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
                try {
                    val cmd = json.decodeFromString<SeekCommand>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.Seek(cmd.positionMs)) }
                } catch (_: Exception) {}
            }
            ConnectActions.CMD_SET_VOLUME -> {
                try {
                    val cmd = json.decodeFromString<SetVolumeCommand>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SetVolume(cmd.volume)) }
                } catch (_: Exception) {}
            }
            ConnectActions.CMD_SWITCH_TIER -> {
                try {
                    val cmd = json.decodeFromString<org.melodist.core.connect.model.SwitchTierCommand>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SwitchTier(cmd)) }
                } catch (_: Exception) {}
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
                try {
                    val payload = json.decodeFromString<org.melodist.core.connect.model.GestureSwipePayload>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.GestureSwipe(payload)) }
                } catch (_: Exception) {}
            }
            ConnectActions.CMD_TOGGLE_FAVORITE -> {
                try {
                    val cmd = json.decodeFromString<org.melodist.core.connect.model.ToggleFavoriteCommand>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.ToggleFavorite(cmd)) }
                } catch (_: Exception) {}
            }
            ConnectActions.CMD_SYNC_LYRICS_SCROLL -> {
                try {
                    val payload = json.decodeFromString<org.melodist.core.connect.model.LyricsScrollPayload>(msg.payload)
                    scope.launch { _commandsFlow.emit(TvIncomingCommand.SyncLyricsScroll(payload)) }
                } catch (_: Exception) {}
            }
        }
    }
}
