package org.melodist.core.connect.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    internal val VIRTUAL_INTERFACE_PATTERNS = listOf(
        "tun", "tap", "ppp", "p2p", "virbr", "docker", "dummy", "vbox",
        "mihomo", "clash", "tailscale", "wireguard", "wg", "zt", "br-",
    )

    fun isEmulator(): Boolean {
        val fp = android.os.Build.FINGERPRINT.lowercase()
        val model = android.os.Build.MODEL.lowercase()
        val hw = android.os.Build.HARDWARE.lowercase()
        return fp.startsWith("generic") || fp.contains("emulator") ||
            model.contains("sdk") || model.contains("emulator") ||
            hw.contains("goldfish") || hw.contains("ranchu")
    }

    // 判断 IP 地址是否属于需要过滤的范围（回环、APIPA、FakeIP、Docker 等）
    internal fun isBlockedIp(host: String): Boolean =
        host.startsWith("127.") ||
            host.startsWith("169.254.") ||
            host.startsWith("198.18.") ||
            host.startsWith("172.17.") ||
            host.startsWith("172.18.")

    // 判断网络接口名是否属于虚拟/隧道接口
    internal fun isVirtualInterface(name: String): Boolean =
        VIRTUAL_INTERFACE_PATTERNS.any { name.lowercase().contains(it) }

    // 计算 IP 地址的优先级得分（越高越优先推荐为本机地址）
    internal fun scoreIp(host: String, interfaceName: String): Int {
        var score = 0
        val name = interfaceName.lowercase()
        if (name.startsWith("wlan") || name.startsWith("eth") ||
            name.startsWith("en") || name.startsWith("wl")
        ) {
            score += 50
        }
        if (host.startsWith("192.168.")) {
            score += 40
        } else if (host.startsWith("172.")) {
            score += 30
        } else if (host.startsWith("10.") && !host.startsWith("10.0.2.")) {
            score += 20
        } else if (host.startsWith("10.0.2.")) {
            score += 1
        }
        return score
    }

    fun getAvailableIpv4Addresses(): List<String> {
        val results = mutableListOf<Pair<String, Int>>() // Pair<IP, PriorityScore>
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name
                if (isVirtualInterface(name)) continue

                val addresses = Collections.list(intf.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (isBlockedIp(host)) continue
                        results.add(host to scoreIp(host, name))
                    }
                }
            }
        } catch (_: Exception) {}

        return results.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    fun getLocalIpv4Address(): String? {
        return getAvailableIpv4Addresses().firstOrNull()
    }
}
