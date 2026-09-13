package org.melodist.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UserSessionTest {
    @BeforeEach
    fun setUp() {
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
}
