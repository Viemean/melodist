package org.melodist.mobile.ui.player.components

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

data class PlayerMonetColors(
    val backgroundColor: Color = Color(0xFF283446),
    val accentColor: Color = Color(0xFF2DB580),
    val highlightColor: Color = Color(0xFF3EC896),
)

fun resolveMonetColors(palette: Palette): PlayerMonetColors {
    val hsl = FloatArray(3)

    fun isValidColor(swatch: Palette.Swatch?): Boolean {
        if (swatch == null) return false
        ColorUtils.colorToHSL(swatch.rgb, hsl)
        val s = hsl[1]
        val l = hsl[2]
        return s >= 0.15f && l in 0.10f..0.92f
    }

    // 优先选取鲜艳和主导色板
    val preferredCandidates =
        listOfNotNull(
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.dominantSwatch,
            palette.mutedSwatch,
            palette.darkVibrantSwatch,
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
                    val lightnessScore = 1f - kotlin.math.abs(l - 0.5f) * 1.5f
                    swatch.population * 0.35f + (s * 1000f) * 0.45f + (lightnessScore * 500f) * 0.20f
                }
        }
    }

    val hue: Float
    val sat: Float
    if (selectedSwatch != null) {
        ColorUtils.colorToHSL(selectedSwatch.rgb, hsl)
        hue = hsl[0]
        sat = hsl[1]
    } else {
        hue = 215f
        sat = 0.30f
    }

    // 莫奈背景色：呈现通透中深色调（明度 0.38f，饱和度 0.30f..0.52f）
    val bgHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.30f, 0.52f),
            0.38f,
        )
    val bgColorInt = ColorUtils.HSLToColor(bgHsl)

    // 按钮与进度条莫奈取色：从封面提取鲜亮、高饱和的强调色
    val accentSwatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: selectedSwatch
    val accentHsl = FloatArray(3)
    val accentColorInt =
        if (accentSwatch != null && isValidColor(accentSwatch)) {
            ColorUtils.colorToHSL(accentSwatch.rgb, accentHsl)
            accentHsl[1] = accentHsl[1].coerceIn(0.50f, 0.75f)
            accentHsl[2] = accentHsl[2].coerceIn(0.42f, 0.56f)
            ColorUtils.HSLToColor(accentHsl)
        } else {
            val defaultAccentHsl =
                floatArrayOf(
                    if (sat < 0.20f) 165f else hue,
                    0.60f,
                    0.50f,
                )
            ColorUtils.HSLToColor(defaultAccentHsl)
        }

    // 歌词高亮色：与强调色同色相，亮度略高但不过曝
    val hlHsl =
        floatArrayOf(
            accentHsl[0],
            accentHsl[1].coerceIn(0.55f, 0.78f),
            0.60f,
        )
    val highlightColorInt = ColorUtils.HSLToColor(hlHsl)

    return PlayerMonetColors(
        backgroundColor = Color(bgColorInt),
        accentColor = Color(accentColorInt),
        highlightColor = Color(highlightColorInt),
    )
}
