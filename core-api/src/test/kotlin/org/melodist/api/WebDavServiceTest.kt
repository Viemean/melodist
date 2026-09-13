package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.melodist.model.WebDavServer

class WebDavServiceTest {
    @Test
    fun testBuildFullUriStandard() {
        val server = WebDavServer(url = "http://192.168.1.100:5005/dav", rootPath = "/dav")
        val uri = WebDavService.buildFullUri(server, "/dav/Music/test.flac")
        assertEquals("http://192.168.1.100:5005/dav/Music/test.flac", uri.toString())
    }

    @Test
    fun testBuildFullUriRelative() {
        val server = WebDavServer(url = "http://192.168.1.100:5005/dav/", rootPath = "/dav")
        val uri = WebDavService.buildFullUri(server, "Music/test.flac")
        assertEquals("http://192.168.1.100:5005/dav/Music/test.flac", uri.toString())
    }

    @Test
    fun testInferTitleArtistHyphen() {
        val (title, artist) = WebDavService.inferTitleArtist("周杰伦 - 晴天.flac")
        assertEquals("晴天", title)
        assertEquals("周杰伦", artist)
    }

    @Test
    fun testInferTitleArtistNumbered() {
        val (title, artist) = WebDavService.inferTitleArtist("01. 七里香.mp3")
        assertEquals("七里香", title)
        assertEquals("WebDAV 音频", artist)
    }
}
