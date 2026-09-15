package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AudioFileFilterTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "song.mp3",
            "track.flac",
            "audio.wav",
            "music.m4a",
            "stream.aac",
            "theme.ogg",
            "cd.ape",
            "sacd.dsf",
            "master.dff",
            "voice.opus",
            "classic.wma",
            "UPPERCASE.FLAC",
            "MIXED.Mp3",
            "/storage/emulated/0/Music/Artist/Album/01. Track.WAV",
        ],
    )
    fun `isAudioFile returns true for supported extensions`(path: String) {
        assertTrue(AudioFileFilter.isAudioFile(path))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "cover.jpg",
            "folder.png",
            "lyrics.lrc",
            "info.txt",
            "album.cue",
            "movie.mp4",
            "video.mkv",
            "archive.zip",
            "noextension",
            "",
        ],
    )
    fun `isAudioFile returns false for unsupported extensions`(path: String) {
        assertFalse(AudioFileFilter.isAudioFile(path))
    }

    @Test
    fun `inferQualityTierByExtension returns SQ for lossless formats`() {
        assertEquals(AudioQualityTier.SQ, AudioFileFilter.inferQualityTierByExtension("song.flac"))
        assertEquals(AudioQualityTier.SQ, AudioFileFilter.inferQualityTierByExtension("song.FLAC"))
        assertEquals(AudioQualityTier.SQ, AudioFileFilter.inferQualityTierByExtension("track.wav"))
        assertEquals(AudioQualityTier.SQ, AudioFileFilter.inferQualityTierByExtension("disc.ape"))
    }

    @Test
    fun `inferQualityTierByExtension returns HiRes for DSD formats`() {
        assertEquals(AudioQualityTier.HiRes, AudioFileFilter.inferQualityTierByExtension("sacd.dsf"))
        assertEquals(AudioQualityTier.HiRes, AudioFileFilter.inferQualityTierByExtension("sacd.DSF"))
        assertEquals(AudioQualityTier.HiRes, AudioFileFilter.inferQualityTierByExtension("master.dff"))
    }

    @Test
    fun `inferQualityTierByExtension returns HQ for other audio formats`() {
        assertEquals(AudioQualityTier.HQ, AudioFileFilter.inferQualityTierByExtension("song.mp3"))
        assertEquals(AudioQualityTier.HQ, AudioFileFilter.inferQualityTierByExtension("music.m4a"))
        assertEquals(AudioQualityTier.HQ, AudioFileFilter.inferQualityTierByExtension("stream.aac"))
        assertEquals(AudioQualityTier.HQ, AudioFileFilter.inferQualityTierByExtension("voice.opus"))
    }
}
