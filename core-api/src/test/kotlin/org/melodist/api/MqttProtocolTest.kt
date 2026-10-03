package org.melodist.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MqttProtocolTest {
    @Test
    fun testBuildConnectPacket() {
        val bytes =
            MqttProtocol.buildConnectPacket(
                clientId = "test_client",
                authMethod = "pass",
                userProperties = listOf("tmeAppID" to "qqmusic"),
            )
        assertNotNull(bytes)
        assertEquals(0x10.toByte(), bytes[0])
    }

    @Test
    fun testBuildSubscribePacket() {
        val bytes =
            MqttProtocol.buildSubscribePacket(
                packetId = 1,
                topic = "management.qrcode_login/test_uuid",
                userProperties = listOf("authorization" to "tmelogin"),
            )
        assertNotNull(bytes)
        assertEquals((0x82).toByte(), bytes[0])
    }

    @Test
    fun testFetchOfficialAppQrCode() =
        kotlinx.coroutines.runBlocking {
            val service = LoginApiService()
            val qr = service.fetchOfficialAppQrCode()
            assertNotNull(qr)
            assertNotNull(qr.identifier)
            assert(qr.imageBytes.isNotEmpty())
            val status = service.pollOfficialAppQrStatus(qr.identifier)
            assertNotNull(status)
            service.cancelOfficialAppSession(qr.identifier)
        }
}
