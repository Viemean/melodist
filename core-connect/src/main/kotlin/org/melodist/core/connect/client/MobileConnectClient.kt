package org.melodist.core.connect.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    data class Error(val message: String) : MobileConnectionState
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

    private val _connectionState = MutableStateFlow<MobileConnectionState>(MobileConnectionState.Disconnected)
    val connectionState: StateFlow<MobileConnectionState> = _connectionState.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerStateEvent?>(null)
    val playerState: StateFlow<PlayerStateEvent?> = _playerState.asStateFlow()

    private val _queueState = MutableStateFlow<QueueStateEvent?>(null)
    val queueState: StateFlow<QueueStateEvent?> = _queueState.asStateFlow()

    fun connect(targetDevice: ConnectDevice, pinCode: String = "") {
        disconnect()
        currentTarget = targetDevice
        _connectionState.value = MobileConnectionState.Connecting

        val url = "ws://${targetDevice.host}:${targetDevice.port}"
        android.util.Log.i("MelodistConnectClient", "Connecting to $url (deviceName: ${targetDevice.name})")
        val request = Request.Builder().url(url).build()

        activeSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    android.util.Log.i("MelodistConnectClient", "Connected to $url successfully")
                    _connectionState.value = MobileConnectionState.Connected
                    val local = storageManager.getOrCreateLocalDevice()
                    val pairPayload = json.encodeToString(
                        PairRequestPayload(
                            device = local,
                            pinCode = pinCode,
                        ),
                    )
                    sendMessage(ConnectActions.PAIR_REQUEST, pairPayload)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    android.util.Log.d("MelodistConnectClient", "Received message from TV: $text")
                    handleIncomingMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    android.util.Log.i("MelodistConnectClient", "WebSocket closed: $code, reason: $reason")
                    _connectionState.value = MobileConnectionState.Disconnected
                    _playerState.value = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    android.util.Log.e("MelodistConnectClient", "WebSocket connect failed to $url: ${t.message}", t)
                    val friendlyMsg = if (targetDevice.host.startsWith("10.0.2.")) {
                        "目标 IP (${targetDevice.host}) 是模拟器私有地址，外部手机无法直连。请在 TV 端切换物理网卡 IP 或使用手动连接输入电脑 Wi-Fi IP"
                    } else {
                        t.message ?: "连接失败"
                    }
                    _connectionState.value = MobileConnectionState.Error(friendlyMsg)
                    _playerState.value = null
                }
            },
        )
    }

    fun disconnect() {
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
        sendMessage(ConnectActions.CMD_PLAY_SONG, json.encodeToString(cmd))
    }

    fun enqueueNext(song: Song, audioSource: AudioSourceDescriptor? = null) {
        val cmd = EnqueueNextCommand(song = song, audioSource = audioSource)
        sendMessage(ConnectActions.CMD_ENQUEUE_NEXT, json.encodeToString(cmd))
    }

    fun switchTier(tier: org.melodist.model.AudioQualityTier) {
        val cmd = org.melodist.core.connect.model.SwitchTierCommand(tier = tier)
        sendMessage(ConnectActions.CMD_SWITCH_TIER, json.encodeToString(cmd))
    }

    fun pause() {
        sendMessage(ConnectActions.CMD_PAUSE, "")
    }

    fun resume() {
        sendMessage(ConnectActions.CMD_RESUME, "")
    }

    fun previous() {
        sendMessage(ConnectActions.CMD_PREVIOUS, "")
    }

    fun next() {
        sendMessage(ConnectActions.CMD_NEXT, "")
    }

    fun seekTo(positionMs: Long) {
        val cmd = SeekCommand(positionMs = positionMs)
        sendMessage(ConnectActions.CMD_SEEK, json.encodeToString(cmd))
    }

    fun setVolume(volume: Float) {
        val cmd = SetVolumeCommand(volume = volume.coerceIn(0f, 1f))
        sendMessage(ConnectActions.CMD_SET_VOLUME, json.encodeToString(cmd))
    }

    fun triggerAod() {
        sendMessage(ConnectActions.CMD_TRIGGER_AOD, "")
    }

    fun cycleLoopMode() {
        sendMessage(ConnectActions.CMD_CYCLE_LOOP_MODE, "")
    }

    fun openPlayer() {
        sendMessage(ConnectActions.CMD_OPEN_PLAYER, "")
    }

    private fun sendMessage(action: String, payload: String) {
        val socket = activeSocket ?: return
        val message = json.encodeToString(ConnectMessage(action = action, payload = payload))
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
                val resp = try {
                    json.decodeFromString<PairResponsePayload>(msg.payload)
                } catch (_: Exception) {
                    return
                }
                if (resp.accepted && resp.device != null) {
                    val target = currentTarget ?: resp.device
                    val updated = target.copy(token = resp.device.token)
                    storageManager.savePairedDevice(updated)
                    storageManager.setLastConnectedDevice(updated)
                    _connectionState.value = MobileConnectionState.Paired(updated)
                } else {
                    _connectionState.value = MobileConnectionState.Error(resp.message.ifBlank { "Pairing rejected" })
                }
            }
            ConnectActions.EVENT_PLAY_STATE -> {
                try {
                    val state = json.decodeFromString<PlayerStateEvent>(msg.payload)
                    _playerState.value = state
                } catch (_: Exception) {}
            }
            ConnectActions.EVENT_QUEUE_STATE -> {
                try {
                    val queue = json.decodeFromString<QueueStateEvent>(msg.payload)
                    _queueState.value = queue
                } catch (_: Exception) {}
            }
        }
    }
}
