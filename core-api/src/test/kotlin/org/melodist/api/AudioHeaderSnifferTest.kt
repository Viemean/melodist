package org.melodist.api

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier

class AudioHeaderSnifferTest {

    @Test
    fun `test AudioHeaderSniffer enrichQualityOptions with real track`() = runBlocking {
        val api = MusicApiService()
        val queries = listOf("少年 梦然", "踏山河 是七叔呢", "阿肆 我在人民广场吃炸鸡")

        for (q in queries) {
            val songs = api.search(q)
            for (s in songs.take(2)) {
                val options = api.probeSongQualities(s.songMid, s.mediaMid)
                val availableFlac = options.filter { it.isAvailable && !it.playUrl.isNullOrBlank() && it.tier == AudioQualityTier.SQ }
                if (availableFlac.isNotEmpty()) {
                    println("测试曲目: ${s.name} - ${s.singer} (mid: ${s.songMid})")
                    val sqOptionBefore = availableFlac.first()
                    println("嗅探前 SQ: size=${sqOptionBefore.sizeBytes}, bitDepth=${sqOptionBefore.bitDepth}, bitrate=${sqOptionBefore.bitrate}")

                    val st = System.currentTimeMillis()
                    val enriched = AudioHeaderSniffer.enrichQualityOptions(options, s.durationSeconds)
                    val cost = System.currentTimeMillis() - st
                    println("并发嗅探耗时: ${cost}ms")

                    val sqOptionAfter = enriched.first { it.tier == AudioQualityTier.SQ }
                    println("嗅探后 SQ: size=${sqOptionAfter.sizeBytes}, bitDepth=${sqOptionAfter.bitDepth}, sampleRate=${sqOptionAfter.sampleRateHz}, bitrate=${sqOptionAfter.bitrate}")

                    assertTrue(sqOptionAfter.sizeBytes > 0L)
                    assertTrue(sqOptionAfter.bitDepth == 16 || sqOptionAfter.bitDepth == 24)
                    assertTrue(sqOptionAfter.sampleRateHz >= 44100)
                    assertTrue(sqOptionAfter.bitrate.endsWith("kbps"))
                    return@runBlocking
                }
            }
        }
    }
}
