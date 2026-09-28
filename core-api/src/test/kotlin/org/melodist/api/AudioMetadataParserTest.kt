package org.melodist.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AudioMetadataParserTest {
    @Test
    fun `test parse empty and small bytes`() {
        val result = AudioMetadataParser.parse(ByteArray(10))
        assertNull(result.title)
        assertNull(result.lyrics)
    }

    @Test
    fun `test parse id3v2 without lyrics does not recurse or crash`() {
        // Minimal valid ID3v2.3 header (10 bytes: 'ID3', ver 3, rev 0, flags 0, size 100) + 100 bytes of zeros
        val bytes = ByteArray(110)
        bytes[0] = 'I'.code.toByte()
        bytes[1] = 'D'.code.toByte()
        bytes[2] = '3'.code.toByte()
        bytes[3] = 3
        bytes[4] = 0
        bytes[5] = 0
        bytes[6] = 0
        bytes[7] = 0
        bytes[8] = 0
        bytes[9] = 100

        val metadata = AudioMetadataParser.parse(bytes)
        assertNull(metadata.lyrics)

        val webdavLyrics = WebDavService.extractEmbeddedLyricsFromBytes(bytes)
        assertNull(webdavLyrics)
    }

    @Test
    fun `test fallback lyrics extraction with vorbis comment`() {
        val sampleLyric = "[00:01.00]Hello world\n[00:05.00]Line 2"
        val payload = "SomePrefix LYRICS=$sampleLyric\u0000SomeSuffix".toByteArray(Charsets.UTF_8)
        val metadata = AudioMetadataParser.parse(payload)
        assertEquals(sampleLyric, metadata.lyrics)

        val webdavLyrics = WebDavService.extractEmbeddedLyricsFromBytes(payload)
        assertEquals(sampleLyric, webdavLyrics)
    }
}
