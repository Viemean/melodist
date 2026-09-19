package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class LyricOffsetCalibrationTest {

    @Test
    fun testOffsetCalculationFormulaForLocalTrack() {
        // 场景：本地 5:08 音频 (如 01. 恋爱デコレート.mp3) vs 官方 5:11 母带
        // 本地音频第 15.000 秒切片，送入 ACR 识别返回官方母带时间为 16.520 秒
        val sliceStartSeconds = 15.000
        val officialMatchedOffsetSeconds = 16.520

        val diffSec = officialMatchedOffsetSeconds - sliceStartSeconds
        val rawOffsetMs = Math.round(diffSec * 1000.0)

        assertEquals(1520L, rawOffsetMs)

        // 判定阈值：大于 150ms 视为有效偏移
        val finalOffsetMs = if (abs(rawOffsetMs) < 150L) 0L else rawOffsetMs
        assertEquals(1520L, finalOffsetMs)

        // 验证播放进度的歌词定位换算
        // 当本地播放进度到达 23.420 秒 (本地歌手人声起唱点)
        val localPlaybackPositionMs = 23420L
        val effectiveLyricPositionMs = localPlaybackPositionMs + finalOffsetMs

        // 换算出的歌词行时间戳应准确命中官方歌词中的人声起唱点 (24.940 秒)
        assertEquals(24940L, effectiveLyricPositionMs)

        // 验证用户在歌词界面点击 24.940 秒的歌词行执行 Seek 跳转
        val targetLyricTimestampMs = 24940L
        val calculatedSeekPositionMs = (targetLyricTimestampMs - finalOffsetMs).coerceAtLeast(0L)
        assertEquals(localPlaybackPositionMs, calculatedSeekPositionMs)
    }

    @Test
    fun testJitterUnderThresholdTreatedAsZero() {
        // 微弱时间差 (如 45ms 编解码抖动) 视为 0，不产生偏移
        val sliceStartSeconds = 15.000
        val officialMatchedOffsetSeconds = 15.045

        val diffSec = officialMatchedOffsetSeconds - sliceStartSeconds
        val rawOffsetMs = Math.round(diffSec * 1000.0)

        val finalOffsetMs = if (abs(rawOffsetMs) < 150L) 0L else rawOffsetMs
        assertEquals(0L, finalOffsetMs)
    }

    @Test
    fun testNegativeOffsetWhenLocalHasLongerSilence() {
        // 若本地音频比官方母带多出 1.2 秒的前奏静音
        val sliceStartSeconds = 15.000
        val officialMatchedOffsetSeconds = 13.800

        val diffSec = officialMatchedOffsetSeconds - sliceStartSeconds
        val rawOffsetMs = Math.round(diffSec * 1000.0)

        assertEquals(-1200L, rawOffsetMs)

        val localPlaybackPositionMs = 26140L
        val effectiveLyricPositionMs = localPlaybackPositionMs + rawOffsetMs
        assertEquals(24940L, effectiveLyricPositionMs)
    }
}
