package org.melodist.core.connect.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkUtilsTest {
    // ── isBlockedIp ────────────────────────────────────────────────

    @Test
    fun `回环地址 127 x 被拦截`() {
        assertTrue(NetworkUtils.isBlockedIp("127.0.0.1"))
        assertTrue(NetworkUtils.isBlockedIp("127.0.0.2"))
    }

    @Test
    fun `APIPA 169 254 x 被拦截`() {
        assertTrue(NetworkUtils.isBlockedIp("169.254.0.1"))
        assertTrue(NetworkUtils.isBlockedIp("169.254.100.200"))
    }

    @Test
    fun `FakeIP 198 18 x 被拦截`() {
        assertTrue(NetworkUtils.isBlockedIp("198.18.0.1"))
        assertTrue(NetworkUtils.isBlockedIp("198.18.255.254"))
    }

    @Test
    fun `Docker 172 17 和 172 18 被拦截`() {
        assertTrue(NetworkUtils.isBlockedIp("172.17.0.1"))
        assertTrue(NetworkUtils.isBlockedIp("172.18.0.1"))
    }

    @Test
    fun `172 16 不被拦截`() {
        assertFalse(NetworkUtils.isBlockedIp("172.16.0.1"))
    }

    @Test
    fun `192 168 不被拦截`() {
        assertFalse(NetworkUtils.isBlockedIp("192.168.1.100"))
    }

    @Test
    fun `10 x 不被拦截`() {
        assertFalse(NetworkUtils.isBlockedIp("10.0.0.1"))
        assertFalse(NetworkUtils.isBlockedIp("10.8.0.1"))
    }

    // ── isVirtualInterface ─────────────────────────────────────────

    @Test
    fun `tun0 被识别为虚拟接口`() {
        assertTrue(NetworkUtils.isVirtualInterface("tun0"))
    }

    @Test
    fun `tap0 被识别为虚拟接口`() {
        assertTrue(NetworkUtils.isVirtualInterface("tap0"))
    }

    @Test
    fun `docker0 被识别为虚拟接口`() {
        assertTrue(NetworkUtils.isVirtualInterface("docker0"))
    }

    @Test
    fun `mihomo 被识别为虚拟接口`() {
        assertTrue(NetworkUtils.isVirtualInterface("mihomo"))
        assertTrue(NetworkUtils.isVirtualInterface("Mihomo")) // 大小写不敏感
    }

    @Test
    fun `tailscale0 被识别为虚拟接口`() {
        assertTrue(NetworkUtils.isVirtualInterface("tailscale0"))
    }

    @Test
    fun `wlan0 不被识别为虚拟接口`() {
        assertFalse(NetworkUtils.isVirtualInterface("wlan0"))
    }

    @Test
    fun `eth0 不被识别为虚拟接口`() {
        assertFalse(NetworkUtils.isVirtualInterface("eth0"))
    }

    @Test
    fun `en0 不被识别为虚拟接口`() {
        assertFalse(NetworkUtils.isVirtualInterface("en0"))
    }

    // ── scoreIp ────────────────────────────────────────────────────

    @Test
    fun `wlan 接口上的 192 168 得分最高`() {
        val score = NetworkUtils.scoreIp("192.168.1.100", "wlan0")
        assertEquals(90, score) // 50(wlan) + 40(192.168)
    }

    @Test
    fun `eth 接口上的 192 168 得分同 wlan`() {
        val score = NetworkUtils.scoreIp("192.168.1.100", "eth0")
        assertEquals(90, score)
    }

    @Test
    fun `en 前缀接口得到接口加分`() {
        val score = NetworkUtils.scoreIp("192.168.1.1", "en0")
        assertEquals(90, score)
    }

    @Test
    fun `非优先接口的 192 168 地址得 40 分`() {
        val score = NetworkUtils.scoreIp("192.168.1.100", "rmnet0")
        assertEquals(40, score)
    }

    @Test
    fun `wlan 接口上的 172 x 得 80 分`() {
        val score = NetworkUtils.scoreIp("172.20.0.1", "wlan0")
        assertEquals(80, score) // 50 + 30
    }

    @Test
    fun `wlan 接口上的 10 x 非模拟器得 70 分`() {
        val score = NetworkUtils.scoreIp("10.8.0.1", "wlan0")
        assertEquals(70, score) // 50 + 20
    }

    @Test
    fun `模拟器 NAT 地址 10 0 2 x 得 1 分`() {
        val score = NetworkUtils.scoreIp("10.0.2.15", "eth0")
        assertEquals(51, score) // 50(eth) + 1
    }

    @Test
    fun `未知地址段得分为接口分`() {
        val score = NetworkUtils.scoreIp("203.0.113.1", "wlan0")
        assertEquals(50, score)
    }

    @Test
    fun `非优先接口未知地址段得 0 分`() {
        val score = NetworkUtils.scoreIp("203.0.113.1", "rmnet0")
        assertEquals(0, score)
    }

    @Test
    fun `高优先 IP 优先于低优先 IP 排序`() {
        val addresses =
            listOf(
                "10.0.2.15" to NetworkUtils.scoreIp("10.0.2.15", "eth0"),
                "192.168.1.100" to NetworkUtils.scoreIp("192.168.1.100", "wlan0"),
                "10.8.0.1" to NetworkUtils.scoreIp("10.8.0.1", "wlan0"),
            )
        val sorted = addresses.sortedByDescending { it.second }.map { it.first }
        assertEquals("192.168.1.100", sorted[0])
        assertEquals("10.8.0.1", sorted[1])
        assertEquals("10.0.2.15", sorted[2])
    }
}
