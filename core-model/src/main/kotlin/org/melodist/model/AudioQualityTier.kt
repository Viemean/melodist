package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
enum class AudioQualityTier {
    HiRes,
    SQ,
    HQ,
    Standard,
    Master,
    Premium,
    Atmos,
    Dolby,
    ;

    companion object {
        fun getStereoRank(tier: AudioQualityTier): Int =
            when (tier) {
                Master -> 5
                HiRes -> 4
                SQ -> 3
                HQ -> 2
                Standard -> 1
                else -> 0
            }

        fun getSpatialRank(tier: AudioQualityTier): Int =
            when (tier) {
                Atmos -> 2
                Dolby -> 2
                Premium -> 1
                else -> 0
            }

        /**
         * 双轨制跨音质收敛判定：
         * - 立体声轨道：Master > HiRes > SQ > HQ > Standard
         * - 全景声轨道：Atmos / Dolby > Premium
         * 跨轨道互不淘汰。
         */
        fun shouldPrune(
            currentTier: AudioQualityTier,
            otherTier: AudioQualityTier,
        ): Boolean {
            if (otherTier == currentTier) return false

            val stereoRank = getStereoRank(currentTier)
            if (stereoRank > 0) {
                val otherStereoRank = getStereoRank(otherTier)
                return otherStereoRank in 1 until stereoRank
            }

            val spatialRank = getSpatialRank(currentTier)
            if (spatialRank > 0) {
                val otherSpatialRank = getSpatialRank(otherTier)
                return otherSpatialRank in 1 until spatialRank
            }

            return false
        }

        fun getBadge(tier: AudioQualityTier): String =
            when (tier) {
                Master -> "母带"
                Premium -> "臻品"
                Atmos -> "全景声"
                Dolby -> "杜比"
                HiRes -> "Hi-Res"
                SQ -> "SQ"
                HQ -> "HQ"
                Standard -> "标准"
            }

        fun fromTierName(name: String?): AudioQualityTier? {
            if (name.isNullOrBlank()) return null
            return when (name.trim().uppercase()) {
                "MASTER" -> Master
                "PREMIUM" -> Premium
                "ATMOS", "ATMOS71", "ATMOS51", "5.1", "7.1" -> Atmos
                "DOLBY" -> Dolby
                "HIRES", "HI-RES" -> HiRes
                "SQ" -> SQ
                "HQ" -> HQ
                "STANDARD" -> Standard
                else -> entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            }
        }

        /**
         * 根据实际音频编码参数自动推导最匹配的音质级别
         */
        fun inferFromAudioFormat(
            sampleRate: Int,
            bitsPerSample: Int = 16,
            channelCount: Int = 2,
            mimeType: String? = null,
            bitrate: Int = 0,
        ): AudioQualityTier {
            val mime = mimeType?.lowercase().orEmpty()
            val isDolbyAtmos = mime.contains("eac3-joc") || mime.contains("atmos")

            if (isDolbyAtmos) {
                return Dolby
            }
            if (channelCount >= 5 ||
                mime.contains("ac3") ||
                mime.contains("eac3") ||
                mime.contains("dts")
            ) {
                return Atmos
            }

            if (sampleRate >= 192000) {
                return Master
            }
            if (sampleRate > 48000 || bitsPerSample > 16) {
                return HiRes
            }

            val isExplicitLossy =
                mime.contains("mp3") ||
                    mime.contains("mpeg") ||
                    mime.contains("aac") ||
                    mime.contains("mp4a") ||
                    mime.contains("vorbis") ||
                    mime.contains("opus") ||
                    mime.contains("wma")

            if (isExplicitLossy) {
                return if (bitrate >= 240000) HQ else Standard
            }

            return SQ
        }
    }
}
