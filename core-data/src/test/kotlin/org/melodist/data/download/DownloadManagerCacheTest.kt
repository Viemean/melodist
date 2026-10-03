package org.melodist.data.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

class DownloadManagerCacheTest {

    @Test
    fun `getCleanTierLabel formats standard tier representations without chinese prefixes`() {
        assertEquals("Master", DownloadManager.getCleanTierLabel(AudioQualityTier.Master))
        assertEquals("Hi-Res", DownloadManager.getCleanTierLabel(AudioQualityTier.HiRes))
        assertEquals("SQ", DownloadManager.getCleanTierLabel(AudioQualityTier.SQ))
        assertEquals("HQ", DownloadManager.getCleanTierLabel(AudioQualityTier.HQ))
        assertEquals("Standard", DownloadManager.getCleanTierLabel(AudioQualityTier.Standard))
        assertEquals("Atmos", DownloadManager.getCleanTierLabel(AudioQualityTier.Atmos))
        assertEquals("Dolby", DownloadManager.getCleanTierLabel(AudioQualityTier.Dolby))
        assertEquals("Premium", DownloadManager.getCleanTierLabel(AudioQualityTier.Premium))
    }

    @Test
    fun `getStandardBaseName formats sanitized singer title and tier`() {
        val song = Song(
            songMid = "001test",
            songId = 12345L,
            name = "晴天/晴空",
            singer = "周杰伦:Jay",
        )
        val baseName = DownloadManager.getStandardBaseName(song, AudioQualityTier.SQ)
        assertEquals("周杰伦_Jay - 晴天_晴空 - SQ", baseName)
    }

    @Test
    fun `cacheExporter interface allows injecting cache extractor`() {
        var isTierChecked = false
        var isExportCalled = false

        val mockExporter = object : MediaCacheExporter {
            override fun isTierFullyCached(songMid: String, tier: AudioQualityTier): Boolean {
                isTierChecked = true
                return songMid == "cached_mid" && tier == AudioQualityTier.SQ
            }

            override fun exportCompleteCache(
                songMid: String,
                tier: AudioQualityTier,
                targetDir: File,
                baseFileName: String,
            ): File? {
                isExportCalled = true
                return if (songMid == "cached_mid") {
                    File(targetDir, "$baseFileName.flac").apply { writeBytes(ByteArray(4096)) }
                } else null
            }
        }

        DownloadManager.cacheExporter = mockExporter

        assertTrue(DownloadManager.cacheExporter?.isTierFullyCached("cached_mid", AudioQualityTier.SQ) == true)
        assertTrue(isTierChecked)

        assertFalse(DownloadManager.cacheExporter?.isTierFullyCached("uncached_mid", AudioQualityTier.SQ) == true)

        val targetDir = File(System.getProperty("java.io.tmpdir"), "melodist_test_${System.currentTimeMillis()}")
        targetDir.mkdirs()
        try {
            val exported = DownloadManager.cacheExporter?.exportCompleteCache("cached_mid", AudioQualityTier.SQ, targetDir, "test_base")
            assertNotNull(exported)
            assertTrue(isExportCalled)
            assertEquals("test_base.flac", exported?.name)
            assertTrue((exported?.length() ?: 0L) > 2048L)

            val unexported = DownloadManager.cacheExporter?.exportCompleteCache("other_mid", AudioQualityTier.SQ, targetDir, "other_base")
            assertNull(unexported)
        } finally {
            targetDir.deleteRecursively()
            DownloadManager.cacheExporter = null
        }
    }
}
