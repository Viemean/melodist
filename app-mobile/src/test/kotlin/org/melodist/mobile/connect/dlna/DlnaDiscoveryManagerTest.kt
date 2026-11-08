package org.melodist.mobile.connect.dlna

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DlnaDiscoveryManagerTest {
    @Test
    fun `resolveControlUrl resolves relative path with URLBase lacking trailing slash`() {
        val xml = "<root><URLBase>http://192.168.110.73:16458</URLBase></root>"
        val result =
            DlnaDiscoveryManager.resolveControlUrl(
                location = "http://192.168.110.73:16458/",
                rawCu = "AVTransport/ff3d3ffe-7d67-4f76-b9b8-e7ee7ff7bffe/control",
                xml = xml,
            )
        assertEquals("http://192.168.110.73:16458/AVTransport/ff3d3ffe-7d67-4f76-b9b8-e7ee7ff7bffe/control", result)
    }

    @Test
    fun `resolveControlUrl resolves relative path against location filename when URLBase absent`() {
        val xml = "<root></root>"
        val result =
            DlnaDiscoveryManager.resolveControlUrl(
                location = "http://192.168.1.50:8080/description.xml",
                rawCu = "AVTransport/control",
                xml = xml,
            )
        assertEquals("http://192.168.1.50:8080/AVTransport/control", result)
    }

    @Test
    fun `resolveControlUrl preserves absolute control URL`() {
        val xml = "<root></root>"
        val result =
            DlnaDiscoveryManager.resolveControlUrl(
                location = "http://192.168.1.50:8080/description.xml",
                rawCu = "http://192.168.1.50:9090/custom/control",
                xml = xml,
            )
        assertEquals("http://192.168.1.50:9090/custom/control", result)
    }

    @Test
    fun `resolveControlUrl resolves absolute path against host root`() {
        val xml = "<root></root>"
        val result =
            DlnaDiscoveryManager.resolveControlUrl(
                location = "http://192.168.1.50:8080/upnp/desc.xml",
                rawCu = "/control/avt",
                xml = xml,
            )
        assertEquals("http://192.168.1.50:8080/control/avt", result)
    }
}
