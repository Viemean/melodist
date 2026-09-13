package org.melodist.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AudioQualityTierTest {
    @ParameterizedTest
    @CsvSource(
        "Master, HiRes, true",
        "Master, SQ, true",
        "Master, HQ, true",
        "Master, Standard, true",
        "HiRes, SQ, true",
        "HiRes, HQ, true",
        "HiRes, Standard, true",
        "SQ, HQ, true",
        "SQ, Standard, true",
        "HQ, Standard, true",
    )
    fun `stereo track higher quality should prune lower quality`(
        current: AudioQualityTier,
        other: AudioQualityTier,
        expected: Boolean,
    ) {
        assertEquals(expected, AudioQualityTier.shouldPrune(current, other))
    }

    @ParameterizedTest
    @CsvSource(
        "Standard, Standard, false",
        "Standard, HQ, false",
        "Standard, SQ, false",
        "Standard, HiRes, false",
        "Standard, Master, false",
        "HQ, HQ, false",
        "HQ, SQ, false",
        "SQ, SQ, false",
        "SQ, HiRes, false",
        "HiRes, HiRes, false",
        "HiRes, Master, false",
        "Master, Master, false",
    )
    fun `stereo track lower or equal quality should not prune`(
        current: AudioQualityTier,
        other: AudioQualityTier,
        expected: Boolean,
    ) {
        assertEquals(expected, AudioQualityTier.shouldPrune(current, other))
    }

    @ParameterizedTest
    @CsvSource(
        "Atmos71, Atmos51, true",
        "Atmos71, Dolby, true",
        "Atmos71, Premium, true",
        "Atmos51, Premium, true",
        "Dolby, Premium, true",
    )
    fun `spatial track higher quality should prune lower quality`(
        current: AudioQualityTier,
        other: AudioQualityTier,
        expected: Boolean,
    ) {
        assertEquals(expected, AudioQualityTier.shouldPrune(current, other))
    }

    @ParameterizedTest
    @CsvSource(
        "Premium, Premium, false",
        "Premium, Atmos51, false",
        "Atmos51, Atmos51, false",
        "Atmos51, Dolby, false",
        "Dolby, Atmos51, false",
        "Dolby, Dolby, false",
        "Atmos51, Atmos71, false",
        "Atmos71, Atmos71, false",
    )
    fun `spatial track lower or equal quality should not prune`(
        current: AudioQualityTier,
        other: AudioQualityTier,
        expected: Boolean,
    ) {
        assertEquals(expected, AudioQualityTier.shouldPrune(current, other))
    }

    @ParameterizedTest
    @CsvSource(
        "Master, Atmos71, false",
        "Master, Dolby, false",
        "HiRes, Premium, false",
        "SQ, Atmos51, false",
        "Atmos71, Master, false",
        "Atmos71, HiRes, false",
        "Dolby, SQ, false",
        "Premium, Standard, false",
    )
    fun `cross track should never prune`(
        current: AudioQualityTier,
        other: AudioQualityTier,
        expected: Boolean,
    ) {
        assertEquals(expected, AudioQualityTier.shouldPrune(current, other))
    }
}
