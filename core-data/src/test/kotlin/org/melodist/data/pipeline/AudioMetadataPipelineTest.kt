package org.melodist.data.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AudioMetadataPipelineTest {

    @Test
    fun `inferTitleArtist splits standard artist and title patterns`() {
        val (title1, artist1) = AudioMetadataPipeline.inferTitleArtist("周杰伦 - 晴天.flac")
        assertEquals("晴天", title1)
        assertEquals("周杰伦", artist1)

        val (title2, artist2) = AudioMetadataPipeline.inferTitleArtist("Taylor Swift - Blank Space (Live).mp3")
        assertEquals("Blank Space (Live)", title2)
        assertEquals("Taylor Swift", artist2)

        val (title3, artist3) = AudioMetadataPipeline.inferTitleArtist("单纯歌名.wav")
        assertEquals("单纯歌名", title3)
        assertEquals("未知歌手", artist3)

        val (title4, artist4) = AudioMetadataPipeline.inferTitleArtist("A - B - C.flac")
        assertEquals("B - C", title4)
        assertEquals("A", artist4)
    }

    @Test
    fun `detectCompanionLrc identifies same-name lrc file`(@TempDir tempDir: File) {
        val audioFile = File(tempDir, "test_track.flac").apply { writeBytes(ByteArray(1024)) }
        val lrcFile = File(tempDir, "test_track.lrc").apply { writeText("[00:01.00]Hello Lyric") }

        val found = AudioMetadataPipeline.detectCompanionLrc(audioFile)
        assertNotNull(found)
        assertEquals(lrcFile.absolutePath, found?.absolutePath)

        val otherAudio = File(tempDir, "other_track.mp3").apply { writeBytes(ByteArray(1024)) }
        assertNull(AudioMetadataPipeline.detectCompanionLrc(otherAudio))
    }

    @Test
    fun `detectCompanionCover locates folder and cover image files`(@TempDir tempDir: File) {
        val audioFile = File(tempDir, "song.flac").apply { writeBytes(ByteArray(1024)) }

        // 初始无图片
        assertNull(AudioMetadataPipeline.detectCompanionCover(audioFile))

        // 创建有效 folder.jpg
        val coverFile = File(tempDir, "folder.jpg").apply { writeBytes(ByteArray(1024)) }
        val found = AudioMetadataPipeline.detectCompanionCover(audioFile)
        assertNotNull(found)
        assertEquals(coverFile.absolutePath, found?.absolutePath)
    }

    @Test
    fun `saveThumbnailWebp rejects undersized byte arrays`(@TempDir tempDir: File) {
        val undersized = ByteArray(100)
        val result = AudioMetadataPipeline.saveThumbnailWebp(undersized, tempDir, "hash_test")
        assertNull(result)
    }
}
