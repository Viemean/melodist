package org.melodist.data.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdateCheckerTest {
    @Test
    fun testIsNewerVersion() {
        // 远程版本高于本地
        assertTrue(UpdateChecker.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v1.1.0", "1.0.9"))
        assertTrue(UpdateChecker.isNewerVersion("v2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "v1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("v1.0.0.1", "1.0.0"))

        // 相同版本
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.0", "1.0.0"))

        // 远程版本低于本地
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.1"))
        assertFalse(UpdateChecker.isNewerVersion("v0.9.9", "1.0.0"))
    }

    @Test
    fun testResolveDownloadUrlPlatformIsolation() {
        val tvOnlyAssets =
            listOf(
                "melodist-tv-1.1.0-arm64-v8a.apk" to "https://github.com/melodist/tv-arm64.apk",
                "melodist-tv-1.1.0-armeabi-v7a.apk" to "https://github.com/melodist/tv-armv7.apk",
            )

        // 仅有 TV 包时，请求 mobile 必须返回 null，不误判
        org.junit.jupiter.api.Assertions.assertNull(
            UpdateChecker.resolveDownloadUrl(tvOnlyAssets, "mobile", "https://fallback.com"),
        )

        // 仅有 TV 包时，请求 tv 返回对应包
        org.junit.jupiter.api.Assertions.assertEquals(
            "https://github.com/melodist/tv-arm64.apk",
            UpdateChecker.resolveDownloadUrl(tvOnlyAssets, "tv", "https://fallback.com"),
        )

        val mixedAssets =
            listOf(
                "melodist-tv-1.2.0.apk" to "https://github.com/melodist/tv.apk",
                "melodist-mobile-1.2.0.apk" to "https://github.com/melodist/mobile.apk",
            )

        // 混合包时精准匹配各自端
        org.junit.jupiter.api.Assertions.assertEquals(
            "https://github.com/melodist/mobile.apk",
            UpdateChecker.resolveDownloadUrl(mixedAssets, "mobile", "https://fallback.com"),
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            "https://github.com/melodist/tv.apk",
            UpdateChecker.resolveDownloadUrl(mixedAssets, "tv", "https://fallback.com"),
        )

        val universalAssets =
            listOf(
                "melodist-universal-1.2.0.apk" to "https://github.com/melodist/universal.apk",
            )
        // 通用包回退
        org.junit.jupiter.api.Assertions.assertEquals(
            "https://github.com/melodist/universal.apk",
            UpdateChecker.resolveDownloadUrl(universalAssets, "mobile", "https://fallback.com"),
        )

        val fullMixedAssets =
            listOf(
                "melodist-tv-1.3.8.apk" to "https://github.com/melodist/tv.apk",
                "melodist-mobile-originos-1.3.8.apk" to "https://github.com/melodist/mobile-originos.apk",
                "melodist-mobile-1.3.8.apk" to "https://github.com/melodist/mobile-standard.apk",
            )
        // 普通 mobile 请求必须排除 originos 包，即使 originos 包排在前面
        org.junit.jupiter.api.Assertions.assertEquals(
            "https://github.com/melodist/mobile-standard.apk",
            UpdateChecker.resolveDownloadUrl(fullMixedAssets, "mobile", "https://fallback.com"),
        )
        // originos 请求必须精准匹配 originos 包
        org.junit.jupiter.api.Assertions.assertEquals(
            "https://github.com/melodist/mobile-originos.apk",
            UpdateChecker.resolveDownloadUrl(fullMixedAssets, "originos", "https://fallback.com"),
        )
    }

    @Test
    fun testOriginOsVersionNormalization() {
        // OriginOS 伪装版本 20.1.3.7，还原为 1.3.7 参与比对
        assertTrue(UpdateChecker.isNewerVersion("v1.3.8", "20.1.3.7"))
        assertFalse(UpdateChecker.isNewerVersion("v1.3.7", "20.1.3.7"))
        assertFalse(UpdateChecker.isNewerVersion("v1.3.6", "20.1.3.7"))
    }
}

