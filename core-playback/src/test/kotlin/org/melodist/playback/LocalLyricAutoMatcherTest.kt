package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.data.AppSettingsManager
import org.melodist.model.LyricLine
import org.melodist.model.Song

class LocalLyricAutoMatcherTest {
    @Test
    fun testNeedsMatchingWithIgnoreEmbeddedLyrics() {
        val testSong =
            Song(
                songMid = "local_123",
                name = "晴天",
                singer = "周杰伦",
                album = "叶惠美",
                localFilePath = "/storage/emulated/0/Music/晴天.flac",
            )

        // 模拟已有非外文、无翻译但有效的本地中文内嵌歌词
        val embeddedLyrics =
            listOf(
                LyricLine(timestampMs = 1000, text = "故事的小黄花"),
                LyricLine(timestampMs = 5000, text = "从出生那年就飘着"),
            )

        // 默认状态：开启自动匹配，但未开启忽略内嵌歌词
        AppSettingsManager.setEnableAutoMatchLyrics(true)
        AppSettingsManager.setIgnoreEmbeddedLyrics(false)
        assertFalse(LocalLyricAutoMatcher.needsMatching(testSong, embeddedLyrics))

        // 开启忽略音频内嵌歌词：即便已有有效中文内嵌歌词，也无条件触发匹配
        AppSettingsManager.setIgnoreEmbeddedLyrics(true)
        assertTrue(LocalLyricAutoMatcher.needsMatching(testSong, embeddedLyrics))

        // 全局关闭自动匹配：即便是开启忽略内嵌，也应返回 false
        AppSettingsManager.setEnableAutoMatchLyrics(false)
        assertFalse(LocalLyricAutoMatcher.needsMatching(testSong, embeddedLyrics))

        // 恢复默认测试环境设置
        AppSettingsManager.setEnableAutoMatchLyrics(true)
        AppSettingsManager.setIgnoreEmbeddedLyrics(false)
    }
}
