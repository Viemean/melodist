package org.melodist.ui.monet

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

object MonetSeedExtractor {
    fun extractFromBitmap(bitmap: Bitmap): MonetSeed {
        val isHardware =
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                bitmap.config == Bitmap.Config.HARDWARE
        val readableBitmap =
            if (isHardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        if (readableBitmap == null) {
            return MonetSeed(
                hue = 215f,
                saturation = 0.22f,
                lightness = 0.50f,
                accentHue = 165f,
                accentSaturation = 0.60f,
                accentLightness = 0.50f,
                isMonochrome = true,
            )
        }

        try {
            return extractFromPalette(Palette.from(readableBitmap).generate())
        } finally {
            if (readableBitmap !== bitmap) readableBitmap.recycle()
        }
    }

    fun extractFromPalette(palette: Palette): MonetSeed {
        val hsl = FloatArray(3)

        fun isValidColor(swatch: Palette.Swatch?): Boolean {
            if (swatch == null) return false
            ColorUtils.colorToHSL(swatch.rgb, hsl)
            val s = hsl[1]
            val l = hsl[2]
            return s >= 0.10f && l in 0.08f..0.92f
        }

        val preferredCandidates =
            listOfNotNull(
                palette.vibrantSwatch,
                palette.lightVibrantSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.dominantSwatch,
            )

        var selectedSwatch = preferredCandidates.firstOrNull { isValidColor(it) }

        if (selectedSwatch == null) {
            val validSwatches = palette.swatches.filter { isValidColor(it) }
            if (validSwatches.isNotEmpty()) {
                selectedSwatch =
                    validSwatches.maxByOrNull { swatch ->
                        ColorUtils.colorToHSL(swatch.rgb, hsl)
                        val s = hsl[1]
                        val l = hsl[2]
                        val lightnessScore = 1f - kotlin.math.abs(l - 0.50f) * 1.5f
                        (s * 3200f) + (lightnessScore * 800f) + (swatch.population * 0.08f)
                    }
            }
        }

        val hue: Float
        val sat: Float
        val lit: Float
        val isMonochrome: Boolean

        if (selectedSwatch != null) {
            ColorUtils.colorToHSL(selectedSwatch.rgb, hsl)
            hue = hsl[0]
            sat = hsl[1]
            lit = hsl[2]
            isMonochrome = false
        } else {
            hue = 215f
            sat = 0.22f
            lit = 0.50f
            isMonochrome = true
        }

        val accentSwatch =
            listOfNotNull(palette.vibrantSwatch, palette.lightVibrantSwatch, selectedSwatch)
                .firstOrNull { isValidColor(it) }
        val accentHsl = FloatArray(3)
        val accentHue: Float
        val accentSat: Float
        val accentLit: Float

        if (accentSwatch != null) {
            ColorUtils.colorToHSL(accentSwatch.rgb, accentHsl)
            accentHue = accentHsl[0]
            accentSat = accentHsl[1]
            accentLit = accentHsl[2]
        } else {
            accentHue = if (isMonochrome) 165f else hue
            accentSat = 0.60f
            accentLit = 0.50f
        }

        return MonetSeed(
            hue = hue,
            saturation = sat,
            lightness = lit,
            accentHue = accentHue,
            accentSaturation = accentSat,
            accentLightness = accentLit,
            isMonochrome = isMonochrome,
        )
    }
}
