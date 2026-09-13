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
    Atmos51,
    Atmos71,
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
                Atmos71 -> 3
                Atmos51 -> 2
                Dolby -> 2
                Premium -> 1
                else -> 0
            }

        /**
         * 双轨制跨音质收敛判定：
         * - 立体声轨道：Master > HiRes > SQ > HQ > Standard
         * - 全景声轨道：Atmos71 > Atmos51 / Dolby > Premium
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
                Atmos51 -> "5.1"
                Atmos71 -> "7.1"
                Dolby -> "杜比"
                HiRes -> "Hi-Res"
                SQ -> "SQ"
                HQ -> "HQ"
                Standard -> "标准"
            }
    }
}
