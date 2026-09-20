package org.melodist.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoverUrlResolverTest {
    private val onlineCdn1200 = "https://y.qq.com/music/photo_new/T002R1200x1200M000003yPnkT3h4fO8.jpg?max_age=2592000"
    private val onlineCdn800 = "https://y.gtimg.cn/music/photo_new/T002R800x800M000003yPnkT3h4fO8.jpg?max_age=2592000"
    private val onlineCdnRaw = "https://y.qq.com/music/photo_new/T002M000003yPnkT3h4fO8.jpg?max_age=2592000"

    @Test
    fun testGetThumbnailUrlReplacesTo500() {
        val thumb1 = CoverUrlResolver.getThumbnailUrl(onlineCdn1200)
        assertTrue(thumb1.contains("R500x500"))
        assertFalse(thumb1.contains("R1200x1200"))

        val thumb2 = CoverUrlResolver.getThumbnailUrl(onlineCdn800)
        assertTrue(thumb2.contains("R500x500"))

        val blank = CoverUrlResolver.getThumbnailUrl("")
        assertEquals("", blank)

        val local = CoverUrlResolver.getThumbnailUrl("file:///data/user/0/cover.webp")
        assertEquals("file:///data/user/0/cover.webp", local)
    }

    @Test
    fun testGetCandidatesThumbnail() {
        val candidates = CoverUrlResolver.getCandidates(onlineCdn1200, CoverScenario.THUMBNAIL)
        assertEquals(2, candidates.size)
        assertTrue(candidates[0].contains("R500x500"))
        assertTrue(candidates[1].contains("R800x800"))
    }

    @Test
    fun testGetCandidatesDetail() {
        val candidates = CoverUrlResolver.getCandidates(onlineCdn800, CoverScenario.DETAIL)
        assertEquals(3, candidates.size)
        assertTrue(candidates[0].contains("R1200x1200"))
        assertTrue(candidates[1].contains("R800x800"))
        assertTrue(candidates[2].contains("R500x500"))
        assertFalse(candidates.any { it.contains("T002M000") })
    }

    @Test
    fun testGetCandidatesPlayerOnWifiAndCellular() {
        val wifiCandidates = CoverUrlResolver.getCandidates(onlineCdn800, CoverScenario.PLAYER, isCellular = false)
        assertEquals(3, wifiCandidates.size)
        assertTrue(wifiCandidates[0].contains("R1200x1200"))
        assertTrue(wifiCandidates[1].contains("R800x800"))
        assertTrue(wifiCandidates[2].contains("R500x500"))

        val cellularCandidates = CoverUrlResolver.getCandidates(onlineCdn800, CoverScenario.PLAYER, isCellular = true)
        assertEquals(2, cellularCandidates.size)
        assertFalse(cellularCandidates.any { it.contains("R1200x1200") })
        assertTrue(cellularCandidates[0].contains("R800x800"))
        assertTrue(cellularCandidates[1].contains("R500x500"))
    }

    @Test
    fun testGetCandidatesFullscreenRaw() {
        val candidates = CoverUrlResolver.getCandidates(onlineCdn1200, CoverScenario.FULLSCREEN_RAW)
        assertEquals(3, candidates.size)
        assertEquals(onlineCdnRaw, candidates[0])
        assertTrue(candidates[1].contains("R1200x1200"))
        assertTrue(candidates[2].contains("R800x800"))
    }

    @Test
    fun testGetCandidatesLocalWithExplicitRaw() {
        val localThumb = "file:///data/user/0/cover_123.webp"
        val localRaw = "file:///data/user/0/cover_raw_123.jpg"

        val thumbCandidates = CoverUrlResolver.getCandidates(localThumb, CoverScenario.THUMBNAIL, explicitRawUrl = localRaw)
        assertEquals(listOf(localThumb), thumbCandidates)

        val playerCandidates = CoverUrlResolver.getCandidates(localThumb, CoverScenario.PLAYER, explicitRawUrl = localRaw)
        assertEquals(listOf(localRaw, localThumb), playerCandidates)
    }

    @Test
    fun testUpgradeTvCoverUrl() {
        val t002 = "https://y.qq.com/music/photo_new/T002R300x300M000123.jpg"
        assertEquals("https://y.qq.com/music/photo_new/T002R1200x1200M000123.jpg", CoverUrlResolver.upgradeTvCoverUrl(t002, 1200))

        val t062 = "https://y.qq.com/music/photo_new/T062R500x500M000456.jpg"
        assertEquals("https://y.qq.com/music/photo_new/T062R800x800M000456.jpg", CoverUrlResolver.upgradeTvCoverUrl(t062, 800))
    }
}
