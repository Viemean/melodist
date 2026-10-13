package org.melodist.api

import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListenTogetherTargetResolveTest {
    @Test
    fun `test resolve pure room id`() = runBlocking {
        val api = MusicApiService()
        val (showId, encUin, songId) = api.resolveListenTogetherShareTarget("LUGC000AKC4340fcDu")
        assertEquals("LUGC000AKC4340fcDu", showId)
        assertEquals("", encUin)
        assertEquals(0L, songId)
    }

    @Test
    fun `test resolve room id with mixed text`() = runBlocking {
        val api = MusicApiService()
        val (showId, _, _) = api.resolveListenTogetherShareTarget("邀请你一起听歌，房间号为 LUGC0027eFVU0AfrvQ 点击加入")
        assertEquals("LUGC0027eFVU0AfrvQ", showId)
    }

    @Test
    fun `test resolve landing page url`() = runBlocking {
        val api = MusicApiService()
        val url = "https://i.y.qq.com/n2/m/share/listen_together/index.html?share_encrypt_uin=mock_uin_123&showid=LUGC000AKC4340fcDu&songid=578521842"
        val (showId, encUin, songId) = api.resolveListenTogetherShareTarget(url)
        assertEquals("LUGC000AKC4340fcDu", showId)
        assertEquals("mock_uin_123", encUin)
        assertEquals(578521842L, songId)
    }

    @Test
    fun `test resolve landing page with hosteuin fallback`() = runBlocking {
        val api = MusicApiService()
        val url = "https://i.y.qq.com/n2/m/share/listen_together/index.html?ADTAG=cbshare&hosteuin=mock_host_456&showid=LUGC0027eFVU0AfrvQ&songid=369178464"
        val (showId, encUin, songId) = api.resolveListenTogetherShareTarget(url)
        assertEquals("LUGC0027eFVU0AfrvQ", showId)
        assertEquals("mock_host_456", encUin)
        assertEquals(369178464L, songId)
    }

    @Test
    fun `test resolve c6 short url redirect with mock response`() = runBlocking {
        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host == "c6.y.qq.com") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header(
                            "Location",
                            "https://i.y.qq.com/n2/m/share/listen_together/index.html?showid=LUGC_MOCK_ROOM_001&songid=123456&share_encrypt_uin=dummy_uin_mock",
                        )
                        .body("".toResponseBody(null))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val api = MusicApiService(mockClient)
        val shortUrlText = "【一起听】邀请你一起来听歌！https://c6.y.qq.com/base/fcgi-bin/u?__=dummy_code，快来听吧"
        val (showId, encUin, songId) = api.resolveListenTogetherShareTarget(shortUrlText)
        assertEquals("LUGC_MOCK_ROOM_001", showId)
        assertEquals("dummy_uin_mock", encUin)
        assertEquals(123456L, songId)
    }

    @Test
    fun `test resolve space stats from mock SSR html`() = runBlocking {
        val mockHtml = """
            <!DOCTYPE html><html><head>
            <script>window.__ssrFirstPageData__="{\"rsp_1\":{\"req_0\":{\"code\":0,\"data\":{\"Together\":{\"TogetherData\":{\"ListenTime\":14160,\"ListenNum\":17,\"FavoriteSongNum\":1}}}}}}"</script>
            </head><body></body></html>
        """.trimIndent()

        val mockClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.contains("close_friend_space")) {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(mockHtml.toResponseBody(null))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        val api = MusicApiService(mockClient)
        val stats = api.getListenTogetherSpaceStats("LUGC_MOCK_ROOM", "dummy_partner_uin")
        org.junit.jupiter.api.Assertions.assertNotNull(stats)
        assertEquals(236L, stats?.listenMinutes)
        assertEquals(17, stats?.listenSongsCount)
        assertEquals(1, stats?.favoriteSongsCount)
    }
}

