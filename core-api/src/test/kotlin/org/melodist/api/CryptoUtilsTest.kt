package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CryptoUtilsTest {
    @Test
    fun `computeZzcSign valid input produces lowercase zzc signature`() {
        val input = "{\"comm\":{\"uin\":\"10000\"}}"
        val sign = CryptoUtils.computeZzcSign(input)

        assertNotNull(sign)
        assertTrue(sign.startsWith("zzc"))
        assertEquals(sign.lowercase(), sign)
        assertTrue(sign.length > 15)
    }

    @Test
    fun `computeZzcSign is deterministic for identical input`() {
        val payload = "{\"req\":{\"module\":\"music.pf_song_detail_svr\",\"method\":\"get_song_detail_yqq\"}}"
        val sign1 = CryptoUtils.computeZzcSign(payload)
        val sign2 = CryptoUtils.computeZzcSign(payload)

        assertEquals(sign1, sign2)
    }

    @Test
    fun `encryptAg1Request produces valid Base64 payload`() {
        val payload = "{\"test\":\"melodist-test-payload-12345\"}"
        val encryptedBase64 = CryptoUtils.encryptAg1Request(payload)

        assertNotNull(encryptedBase64)
        assertTrue(encryptedBase64.isNotBlank())
    }
}
