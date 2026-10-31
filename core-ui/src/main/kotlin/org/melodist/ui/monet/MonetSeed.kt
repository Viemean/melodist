package org.melodist.ui.monet

data class MonetSeed(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val accentHue: Float,
    val accentSaturation: Float,
    val accentLightness: Float,
    val isMonochrome: Boolean = false,
)
