package org.melodist.core.connect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.core.connect.model.AudioSourceDescriptor
import org.melodist.core.connect.model.AudioSourceType
import org.melodist.core.connect.model.ConnectActions
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.ConnectMessage
import org.melodist.core.connect.model.DeviceType
import org.melodist.core.connect.model.PairRequestPayload
import org.melodist.core.connect.model.PairResponsePayload
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.core.connect.model.QrPairData
import org.melodist.core.connect.model.QueueStateEvent
import org.melodist.core.connect.model.RemoteControlMode
import org.melodist.core.connect.model.SeekCommand
import org.melodist.core.connect.model.SetVolumeCommand

class ConnectModelsTest {

    // ── ConnectDevice ──────────────────────────────────────────────

    @Test
    fun `ConnectDevice 默认端口为 8765`() {
        val device = ConnectDevice(id = "id1", name = "TV", type = DeviceType.TV)
        assertEquals(8765, device.port)
    }

    @Test
    fun `ConnectDevice 默认 token 为空字符串`() {
        val device = ConnectDevice(id = "id1", name = "TV", type = DeviceType.TV)
        assertEquals("", device.token)
    }

    @Test
    fun `ConnectDevice 数据类相等性`() {
        val a = ConnectDevice(id = "abc", name = "TV", type = DeviceType.TV, host = "192.168.1.2", port = 8765, token = "tok")
        val b = ConnectDevice(id = "abc", name = "TV", type = DeviceType.TV, host = "192.168.1.2", port = 8765, token = "tok")
        assertEquals(a, b)
    }

    @Test
    fun `ConnectDevice 不同 id 不相等`() {
        val a = ConnectDevice(id = "aaa", name = "TV", type = DeviceType.TV)
        val b = ConnectDevice(id = "bbb", name = "TV", type = DeviceType.TV)
        assertNotEquals(a, b)
    }

    @Test
    fun `DeviceType 包含 TV 和 MOBILE`() {
        val types = DeviceType.entries.map { it.name }
        assertTrue(types.contains("TV"))
        assertTrue(types.contains("MOBILE"))
    }

    // ── ConnectMessage ─────────────────────────────────────────────

    @Test
    fun `ConnectMessage 自动生成 UUID id`() {
        val msg = ConnectMessage(action = ConnectActions.PING)
        assertNotNull(msg.id)
        assertTrue(msg.id.isNotBlank())
        // UUID v4 格式：8-4-4-4-12
        assertTrue(msg.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `两个 ConnectMessage 的 id 不相同`() {
        val m1 = ConnectMessage(action = ConnectActions.PING)
        val m2 = ConnectMessage(action = ConnectActions.PING)
        assertNotEquals(m1.id, m2.id)
    }

    @Test
    fun `ConnectMessage timestamp 大于 0`() {
        val msg = ConnectMessage(action = ConnectActions.PONG)
        assertTrue(msg.timestamp > 0)
    }

    @Test
    fun `ConnectMessage 默认 payload 为空字符串`() {
        val msg = ConnectMessage(action = ConnectActions.PING)
        assertEquals("", msg.payload)
    }

    // ── ConnectActions 常量完整性 ──────────────────────────────────

    @Test
    fun `ConnectActions 握手常量非空`() {
        listOf(
            ConnectActions.PAIR_REQUEST,
            ConnectActions.PAIR_RESPONSE,
            ConnectActions.DISCONNECT,
            ConnectActions.PING,
            ConnectActions.PONG,
        ).forEach { action ->
            assertTrue(action.isNotBlank(), "action '$action' 不能为空")
        }
    }

    @Test
    fun `ConnectActions 控制命令常量非空且唯一`() {
        val cmds = listOf(
            ConnectActions.CMD_PLAY_SONG,
            ConnectActions.CMD_ENQUEUE_NEXT,
            ConnectActions.CMD_PAUSE,
            ConnectActions.CMD_RESUME,
            ConnectActions.CMD_SEEK,
            ConnectActions.CMD_PREVIOUS,
            ConnectActions.CMD_NEXT,
            ConnectActions.CMD_SET_VOLUME,
            ConnectActions.CMD_SWITCH_TIER,
            ConnectActions.CMD_TRIGGER_AOD,
            ConnectActions.CMD_CYCLE_LOOP_MODE,
            ConnectActions.CMD_OPEN_PLAYER,
            ConnectActions.CMD_GESTURE_SWIPE,
            ConnectActions.CMD_TOGGLE_FAVORITE,
            ConnectActions.CMD_SYNC_LYRICS_SCROLL,
        )
        cmds.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(cmds.size, cmds.toSet().size, "CMD 常量存在重复值")
    }

    @Test
    fun `ConnectActions 事件常量非空且唯一`() {
        val events = listOf(
            ConnectActions.EVENT_PLAY_STATE,
            ConnectActions.EVENT_QUEUE_STATE,
        )
        events.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(events.size, events.toSet().size)
    }

    // ── AudioSourceDescriptor ──────────────────────────────────────

    @Test
    fun `AudioSourceDescriptor 默认 streamUrl 为 null`() {
        val desc = AudioSourceDescriptor(sourceType = AudioSourceType.DIRECT_API)
        assertEquals(null, desc.streamUrl)
        assertTrue(desc.headers.isEmpty())
    }

    @Test
    fun `AudioSourceType 包含三种类型`() {
        val types = AudioSourceType.entries.map { it.name }
        assertTrue(types.contains("DIRECT_API"))
        assertTrue(types.contains("STREAM_PROXY"))
        assertTrue(types.contains("DIRECT_WEBDAV"))
    }

    // ── PairRequestPayload / PairResponsePayload ───────────────────

    @Test
    fun `PairRequestPayload 默认 pinCode 为空`() {
        val device = ConnectDevice(id = "id", name = "TV", type = DeviceType.TV)
        val payload = PairRequestPayload(device = device)
        assertEquals("", payload.pinCode)
    }

    @Test
    fun `PairResponsePayload rejected 时 device 为 null`() {
        val payload = PairResponsePayload(accepted = false, message = "拒绝配对")
        assertFalse(payload.accepted)
        assertEquals(null, payload.device)
    }

    // ── PlayerStateEvent 默认值 ────────────────────────────────────

    @Test
    fun `PlayerStateEvent 默认值合法`() {
        val event = PlayerStateEvent()
        assertFalse(event.isPlaying)
        assertEquals(0L, event.positionMs)
        assertEquals(0L, event.durationMs)
        assertEquals(1.0f, event.volume)
        assertEquals(0, event.queueSize)
        assertEquals(-1, event.currentIndex)
        assertFalse(event.isAodActive)
    }

    // ── QueueStateEvent ────────────────────────────────────────────

    @Test
    fun `QueueStateEvent 默认队列为空`() {
        val event = QueueStateEvent()
        assertTrue(event.queue.isEmpty())
        assertEquals(-1, event.currentIndex)
    }

    // ── SeekCommand / SetVolumeCommand ─────────────────────────────

    @Test
    fun `SeekCommand 存储指定位置`() {
        val cmd = SeekCommand(positionMs = 30_000L)
        assertEquals(30_000L, cmd.positionMs)
    }

    @Test
    fun `SetVolumeCommand 存储音量值`() {
        val cmd = SetVolumeCommand(volume = 0.75f)
        assertEquals(0.75f, cmd.volume)
    }

    // ── QrPairData ─────────────────────────────────────────────────

    @Test
    fun `QrPairData 默认版本号为 1`() {
        val data = QrPairData(
            deviceId = "id",
            deviceName = "TV",
            host = "192.168.1.1",
            port = 8765,
            token = "tok",
            pinCode = "1234",
        )
        assertEquals(1, data.version)
    }

    // ── RemoteControlMode ──────────────────────────────────────────

    @Test
    fun `RemoteControlMode 包含 TAKEOVER 和 BROWSE`() {
        val modes = RemoteControlMode.entries.map { it.name }
        assertTrue(modes.contains("TAKEOVER"))
        assertTrue(modes.contains("BROWSE"))
    }
}
