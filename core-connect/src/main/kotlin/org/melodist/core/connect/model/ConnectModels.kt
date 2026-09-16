package org.melodist.core.connect.model

import kotlinx.serialization.Serializable
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

enum class DeviceType {
    TV,
    MOBILE,
}

@Serializable
data class ConnectDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val host: String = "",
    val port: Int = 8765,
    val token: String = "",
    val pairedAt: Long = System.currentTimeMillis(),
)

enum class AudioSourceType {
    DIRECT_API,
    STREAM_PROXY,
    DIRECT_WEBDAV,
}

@Serializable
data class AudioSourceDescriptor(
    val sourceType: AudioSourceType,
    val streamUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ConnectMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val action: String,
    val payload: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

object ConnectActions {
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_RESPONSE = "pair_response"
    const val DISCONNECT = "disconnect"
    const val PING = "ping"
    const val PONG = "pong"

    const val CMD_PLAY_SONG = "cmd_play_song"
    const val CMD_ENQUEUE_NEXT = "cmd_enqueue_next"
    const val CMD_PAUSE = "cmd_pause"
    const val CMD_RESUME = "cmd_resume"
    const val CMD_SEEK = "cmd_seek"
    const val CMD_PREVIOUS = "cmd_prev"
    const val CMD_NEXT = "cmd_next"
    const val CMD_SET_VOLUME = "cmd_set_volume"
    const val CMD_SWITCH_TIER = "cmd_switch_tier"
    const val CMD_TRIGGER_AOD = "cmd_trigger_aod"
    const val CMD_CYCLE_LOOP_MODE = "cmd_cycle_loop_mode"
    const val CMD_OPEN_PLAYER = "cmd_open_player"
    const val CMD_GESTURE_SWIPE = "cmd_gesture_swipe"

    const val EVENT_PLAY_STATE = "event_play_state"
    const val EVENT_QUEUE_STATE = "event_queue_state"
}

enum class RemoteControlMode {
    TAKEOVER, // 全面接管模式：所有播放、歌单、切歌与音质切换均直接在 TV 上执行
    BROWSE,   // 浏览模式：手机本地独立播放，仅在主动点击接力或菜单发送时在 TV 播放
}

@Serializable
data class PairRequestPayload(
    val device: ConnectDevice,
    val pinCode: String = "",
)

@Serializable
data class PairResponsePayload(
    val accepted: Boolean,
    val message: String = "",
    val device: ConnectDevice? = null,
)

@Serializable
data class PlaySongCommand(
    val song: Song,
    val queue: List<Song> = emptyList(),
    val index: Int = 0,
    val startPositionMs: Long = 0L,
    val audioSource: AudioSourceDescriptor? = null,
    val qualityTier: org.melodist.model.AudioQualityTier? = null,
)

@Serializable
data class SwitchTierCommand(
    val tier: org.melodist.model.AudioQualityTier,
)

@Serializable
data class EnqueueNextCommand(
    val song: Song,
    val audioSource: AudioSourceDescriptor? = null,
)

@Serializable
data class SeekCommand(
    val positionMs: Long,
)

@Serializable
data class SetVolumeCommand(
    val volume: Float,
)

@Serializable
data class PlayerStateEvent(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1.0f,
    val queueSize: Int = 0,
    val currentIndex: Int = -1,
    val loopMode: String = "ListRepeat",
    val isAodActive: Boolean = false,
    val prevSong: Song? = null,
    val nextSong: Song? = null,
    val currentTier: AudioQualityTier? = null,
    val availableTiers: Set<AudioQualityTier> = emptySet(),
)

@Serializable
data class QueueStateEvent(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
)

@Serializable
data class QrPairData(
    val version: Int = 1,
    val deviceId: String,
    val deviceName: String,
    val host: String,
    val port: Int,
    val token: String,
    val pinCode: String,
)

@Serializable
enum class GestureSwipeState {
    DRAGGING,
    SETTLING,
    CANCEL,
    IDLE,
}

@Serializable
data class GestureSwipePayload(
    val state: GestureSwipeState,
    val fraction: Float = 0f,
    val targetFraction: Float = 0f,
    val durationMs: Long = 200L,
    val timestamp: Long = System.currentTimeMillis(),
)
