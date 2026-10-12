package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class MusicApiPlayStreamTest {
    @Test
    fun `computeTimeKey generates expected uppercase md5 hex`() {
        val optime = 1760246338L
        val timeSec = 60L
        val uin = "12345678"
        // 1760246338 + 60 + 12345678 + gk2$Lh-&l4#!4iow
        val expected = md5Hex("17602463386012345678gk2\$Lh-&l4#!4iow").uppercase()
        val actual = computeTimeKey(optime, timeSec, uin)
        assertEquals(expected, actual)
    }

    @Test
    fun `buildPlayStreamXml formats valid xml payload`() {
        val deviceInfo =
            DeviceReportInfo(
                model = "TestDevice",
                osVersion = "14",
                sdkInt = 34,
                androidId = "0123456789abcdef",
                openUdid = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
            )
        val xml =
            buildPlayStreamXml(
                songId = 186016L,
                durationSec = 269L,
                playTimeSec = 60L,
                deviceInfo = deviceInfo,
                uin = "12345678",
                authst = "W_X_TEST_KEY",
                optime = 1760246338L,
            )

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><root>"))
        assertTrue(xml.contains("<OpenUDID>a1b2c3d4e5f60718293a4b5c6d7e8f9012345678</OpenUDID>"))
        assertTrue(xml.contains("<aid>0123456789abcdef</aid>"))
        assertTrue(xml.contains("<phonetype>TestDevice</phonetype>"))
        assertTrue(xml.contains("<os_ver>14</os_ver>"))
        assertTrue(xml.contains("<devicelevel>34</devicelevel>"))
        assertTrue(xml.contains("<qq>12345678</qq>"))
        assertTrue(xml.contains("<tmeLoginType>1</tmeLoginType>")) // W_X key -> 1
        assertTrue(xml.contains("""songid="186016""""))
        assertTrue(xml.contains("""time="60""""))
        assertTrue(xml.contains("""audiotime="269""""))
        assertTrue(xml.contains("""play_duration_mi="60000""""))
        assertTrue(xml.endsWith("</root>"))
    }

    @Test
    fun `compressGzip accurately compresses data and preserves content upon decompression`() {
        val sample = "Test imusic_tj gzip stream payload verification content"
        val compressed = compressGzip(sample.toByteArray(Charsets.UTF_8))
        assertTrue(compressed.isNotEmpty())

        val decompressed =
            GZIPInputStream(ByteArrayInputStream(compressed)).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        assertEquals(sample, decompressed)
    }
}
