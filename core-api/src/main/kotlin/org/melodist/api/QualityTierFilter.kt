package org.melodist.api

import org.melodist.model.AudioQualityTier

object QualityTierFilter {
    @Volatile
    var isTierSupported: (AudioQualityTier) -> Boolean = { true }
}
