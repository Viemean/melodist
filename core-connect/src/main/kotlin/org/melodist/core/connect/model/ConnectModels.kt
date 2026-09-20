package org.melodist.core.connect.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
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
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val action: String,
    val payload: String = "",
    val data: JsonElement? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    inline fun <reified T> decodeData(json: Json): T? {
        if (data != null) {
            return try {
                json.decodeFromJsonElement<T>(data)
            } catch (_: Exception) {
                null
            }
        }
        if (payload.isNotBlank()) {
            return try {
                json.decodeFromString<T>(payload)
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    companion object {
        inline fun <reified T> create(
            action: String,
            data: T,
            json: Json,
            id: String =
                java.util.UUID
                    .randomUUID()
                    .toString(),
        ): ConnectMessage {
            val element =
                try {
                    json.encodeToJsonElement(data)
                } catch (_: Exception) {
                    null
                }
            val str =
                try {
                    json.encodeToString(data)
                } catch (_: Exception) {
                    ""
                }
            return ConnectMessage(
                id = id,
                action = action,
                payload = str,
                data = element,
            )
        }
    }
}

object ConnectActions {
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_RESPONSE = "pair_response"
    const val DISCONNECT = "disconnect"
    const val PING = "ping"
    const val PONG = "pong"

    const val REQ_GET_PLAYER_STATE = "req_get_player_state"
    const val REQ_GET_QUEUE_STATE = "req_get_queue_state"

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
    const val CMD_TOGGLE_FAVORITE = "cmd_toggle_favorite"
    const val CMD_SYNC_LYRICS_SCROLL = "cmd_sync_lyrics_scroll"
    const val CMD_SYNC_LYRICS = "cmd_sync_lyrics"

    const val EVENT_PLAY_STATE = "event_play_state"
    const val EVENT_QUEUE_STATE = "event_queue_state"
    const val EVENT_SYNC_LYRICS = "event_sync_lyrics"
}

enum class RemoteControlMode {
    TAKEOVER, // 全面接管模式：所有播放、歌单、切歌与音质切换均直接在 TV 上执行
    BROWSE, // 浏览模式：手机本地独立播放，仅在主动点击接力或菜单发送时在 TV 播放
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
data class ToggleFavoriteCommand(
    val song: Song? = null,
    val songMid: String = "",
    val isFavorite: Boolean = false,
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
    val isFavorite: Boolean = false,
    val isRadioMode: Boolean = false,
    val lyricOffsetMs: Long = 0L,
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

@Serializable
data class LyricsScrollPayload(
    val lineIndex: Int,
    val isUserScrolling: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class LyricsSyncPayload(
    val songMid: String,
    val title: String = "",
    val singer: String = "",
    val lyrics: List<LyricLine> = emptyList(),
    val sourceDeviceId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val lyricOffsetMs: Long = 0L,
)
