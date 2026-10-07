package org.melodist.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserSessionTest {
    @BeforeEach
    fun setUp() {
        UserSession.clear()
    }

    @AfterEach
    fun tearDown() {
        UserSession.clear()
    }

    @Test
    fun `isLoggedIn returns false initially`() {
        assertFalse(UserSession.isLoggedIn)
        assertEquals("", UserSession.profile.uin)
    }

    @Test
    fun `update populates session and isLoggedIn returns true`() {
        UserSession.update(
            uin = "12345678",
            nick = "测试用户",
            musicKey = "TEST_MUSIC_KEY",
            cookies = mapOf("qm_keyst" to "TEST_MUSIC_KEY", "uin" to "12345678"),
            avatarUrl = "https://example.com/avatar.jpg",
            isVip = true,
        )

        assertTrue(UserSession.isLoggedIn)
        assertEquals("12345678", UserSession.profile.uin)
        assertEquals("测试用户", UserSession.profile.nick)
        assertTrue(UserSession.profile.isVip)

        val cookieHeader = UserSession.getCookieHeader()
        assertTrue(cookieHeader.contains("qm_keyst=TEST_MUSIC_KEY"))
        assertTrue(cookieHeader.contains("uin=12345678"))
    }

    @Test
    fun `update resolves empty uin from cookies`() {
        UserSession.update(
            uin = "",
            nick = "",
            musicKey = "KEY",
            cookies = mapOf("musicid" to "88776655"),
        )

        assertTrue(UserSession.isLoggedIn)
        assertEquals("88776655", UserSession.profile.uin)
    }

    @Test
    fun `effectiveAvatarUrl falls back to uin when cookies have blank values`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                cookies =
                    mapOf(
                        "pt2gguin" to "",
                        "uin" to "o",
                        "qqmusic_uin" to "   ",
                    ),
            )
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=$testUin&s=640", profile.effectiveAvatarUrl)
    }

    @Test
    fun `effectiveAvatarUrl resolves valid numeric qq from cookie with o prefix`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                cookies =
                    mapOf(
                        "pt2gguin" to "o$testUin",
                    ),
            )
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=$testUin&s=640", profile.effectiveAvatarUrl)
    }

    @Test
    fun `effectiveAvatarUrl prefers explicit avatarUrl when present`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                avatarUrl = "https://custom.avatar/pic.jpg",
            )
        assertEquals("https://custom.avatar/pic.jpg", profile.effectiveAvatarUrl)
    }

    @Test
    fun `update does not synthesize qq avatar for wechat accounts`() {
        val testMusicId = "88887777"
        UserSession.update(
            uin = testMusicId,
            nick = "微信用户",
            musicKey = "W_X_TEST_KEY",
            cookies = mapOf("tmeLoginType" to "1"),
            avatarUrl = "",
        )
        assertEquals("", UserSession.profile.avatarUrl)
    }

    @Test
    fun `effectiveAvatarUrl ignores t_user_default placeholder and falls back to qq avatar`() {
        val testUin = System.getenv("TEST_UIN") ?: "1000000001"
        val profile =
            UserProfile(
                uin = testUin,
                avatarUrl = "https://y.qq.com/music/common/upload/t_user_default/3284895.png",
            )
        assertEquals("https://q1.qlogo.cn/g?b=qq&nk=$testUin&s=640", profile.effectiveAvatarUrl)
    }
}
