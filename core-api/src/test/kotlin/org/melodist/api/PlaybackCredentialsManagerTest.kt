package org.melodist.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlaybackCredentialsManagerTest {
    @BeforeEach
    fun setUp() {
        PlaybackCredentialsManager.clearCredentials()
        UserSession.clear()
    }

    @AfterEach
    fun tearDown() {
        PlaybackCredentialsManager.clearCredentials()
        UserSession.clear()
    }

    @Test
    fun `test export and import token without password`() {
        val profile =
            UserProfile(
                uin = "123456789",
                nick = "测试会员",
                musicKey = "W_X_TEST_MUSIC_KEY_123456789",
                isVip = true,
                cookies =
                    mapOf(
                        "qm_keyst" to "Q_M_KEY_ST_VALUE_ABCDEFGHIJKLMNOP",
                        "uin" to "123456789",
                        "pskey" to "PSKEY_VALUE_XYZ",
                        "unrelated_tracking_cookie" to "IGNORE_ME_VERY_LONG_STRING",
                    ),
            )

        val token = PlaybackCredentialsManager.exportToken(profile, password = null)
        assertNotNull(token)
        assertTrue(token.isNotBlank())
        // 验证长度不超过 300 字符
        assertTrue(token.length < 300, "Token length should be compact, actual: ${token.length}")
        assertFalse(PlaybackCredentialsManager.isTokenPasswordProtected(token))

        val imported = PlaybackCredentialsManager.importToken(token, password = null)
        assertEquals("123456789", imported.uin)
        assertEquals("W_X_TEST_MUSIC_KEY_123456789", imported.musicKey)
        assertTrue(imported.isVip)
        assertEquals("测试会员", imported.nick)
        // 验证非关键cookie被过滤
        assertTrue(imported.cookies.containsKey("qm_keyst"))
        assertFalse(imported.cookies.containsKey("unrelated_tracking_cookie"))

        // 验证活动凭证获取
        assertEquals("123456789", PlaybackCredentialsManager.getActiveUin())
        assertEquals("W_X_TEST_MUSIC_KEY_123456789", PlaybackCredentialsManager.getActiveAuthst())
        assertTrue(PlaybackCredentialsManager.getActiveCookieHeader().contains("qm_keyst=Q_M_KEY_ST_VALUE_ABCDEFGHIJKLMNOP"))
    }

    @Test
    fun `test export and import token with password`() {
        val profile =
            UserProfile(
                uin = "987654321",
                nick = "密码保护用户",
                musicKey = "SECURE_KEY_VAL",
                isVip = false,
                cookies = mapOf("qm_keyst" to "SECURE_COOKIE"),
            )

        val password = "MySecretPassword123"
        val token = PlaybackCredentialsManager.exportToken(profile, password = password)
        assertTrue(token.length < 300, "Token length should be compact, actual: ${token.length}")
        assertTrue(PlaybackCredentialsManager.isTokenPasswordProtected(token))

        // 错误密码解密应失败
        assertThrows<IllegalArgumentException> {
            PlaybackCredentialsManager.importToken(token, password = "WrongPassword")
        }

        // 无密码解密应提示输入密码
        assertThrows<IllegalArgumentException> {
            PlaybackCredentialsManager.importToken(token, password = null)
        }

        // 正确密码解密应成功
        val imported = PlaybackCredentialsManager.importToken(token, password = password)
        assertEquals("987654321", imported.uin)
        assertEquals("SECURE_KEY_VAL", imported.musicKey)
    }

    @Test
    fun `test fallback to UserSession when custom credentials not set`() {
        UserSession.profile =
            UserProfile(
                uin = "55555",
                musicKey = "USER_SESSION_KEY",
                cookies = mapOf("uin" to "55555"),
            )

        assertEquals("55555", PlaybackCredentialsManager.getActiveUin())
        assertEquals("USER_SESSION_KEY", PlaybackCredentialsManager.getActiveAuthst())
        assertEquals("uin=55555", PlaybackCredentialsManager.getActiveCookieHeader())
    }

    @Test
    fun `test inspectToken gating handles expired, non-vip, and downgrade correctly`() {
        // 1. 普通用户凭证 (Non-VIP)
        val nonVipProfile = UserProfile(uin = "1001", nick = "普通账号", isVip = false, isHugeVip = false, isSvip = false)
        val nonVipToken = PlaybackCredentialsManager.exportToken(nonVipProfile)
        val nonVipResult = PlaybackCredentialsManager.inspectToken(nonVipToken)
        assertTrue(nonVipResult is PlaybackCredentialsManager.ImportValidationResult.NonVip)

        // 2. 过期凭证 (Expired)
        val expiredProfile = UserProfile(
            uin = "1002",
            nick = "过期账号",
            isVip = true,
            isHugeVip = true,
            vipExpireAt = "2020-01-01",
        )
        val expiredToken = PlaybackCredentialsManager.exportToken(expiredProfile)
        val expiredResult = PlaybackCredentialsManager.inspectToken(expiredToken)
        assertTrue(expiredResult is PlaybackCredentialsManager.ImportValidationResult.Expired)

        // 3. 降级导入 (当前账号是 SVIP，导入的凭证是绿钻)
        UserSession.profile = UserProfile(uin = "88888", isSvip = true, isVip = true)
        val greenProfile = UserProfile(
            uin = "1003",
            nick = "绿钻账号",
            isVip = true,
            isHugeVip = true,
            vipExpireAt = "2099-01-01",
        )
        val greenToken = PlaybackCredentialsManager.exportToken(greenProfile)
        val downgradeResult = PlaybackCredentialsManager.inspectToken(greenToken)
        assertTrue(downgradeResult is PlaybackCredentialsManager.ImportValidationResult.DowngradeWarning)
        val warning = downgradeResult as PlaybackCredentialsManager.ImportValidationResult.DowngradeWarning
        assertEquals(PlaybackVipTier.SVIP, warning.currentTier)
        assertEquals(PlaybackVipTier.GREEN, warning.importedTier)

        // 4. 正常升级或同级导入 (当前账号是普通用户，导入绿钻)
        UserSession.profile = UserProfile(uin = "0", isVip = false)
        val successResult = PlaybackCredentialsManager.inspectToken(greenToken)
        assertTrue(successResult is PlaybackCredentialsManager.ImportValidationResult.Success)
    }

    @Test
    fun `test markCredentialsRevoked and effective tier fallback`() {
        UserSession.profile = UserProfile(uin = "0", isVip = false)
        val validVipCreds = PlaybackCredentials(
            uin = "2001",
            nick = "有效VIP",
            isVip = true,
            isHugeVip = true,
            vipExpireAt = "2099-01-01",
            isRevoked = false,
        )
        PlaybackCredentialsManager.setCredentials(validVipCreds)
        assertEquals(PlaybackVipTier.GREEN, PlaybackCredentialsManager.getEffectiveVipTier())
        assertFalse(PlaybackCredentialsManager.isCredentialInvalid(validVipCreds))

        // 模拟被云端吊销
        PlaybackCredentialsManager.markCredentialsRevoked()
        assertTrue(PlaybackCredentialsManager.isCredentialRevoked())
        assertTrue(PlaybackCredentialsManager.isCredentialInvalid())
        // 失效后应回退到 UserSession.profile 的普通用户等级
        assertEquals(PlaybackVipTier.NONE, PlaybackCredentialsManager.getEffectiveVipTier())
    }
}
