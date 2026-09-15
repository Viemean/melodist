package org.melodist.model

object AudioFileFilter {
    val SUPPORTED_AUDIO_EXTENSIONS: Set<String> =
        setOf(
            "mp3",
            "flac",
            "wav",
            "m4a",
            "aac",
            "ogg",
            "ape",
            "dsf",
            "dff",
            "opus",
            "wma",
        )

    fun isAudioFile(nameOrPath: String): Boolean {
        val ext = nameOrPath.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_AUDIO_EXTENSIONS
    }

    fun inferQualityTierByExtension(nameOrPath: String): AudioQualityTier {
        val lower = nameOrPath.lowercase()
        return when {
            lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ape") -> AudioQualityTier.SQ
            lower.endsWith(".dsf") || lower.endsWith(".dff") -> AudioQualityTier.HiRes
            else -> AudioQualityTier.HQ
        }
    }
}
