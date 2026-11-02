package org.melodist.mobile.ui.player.components

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.toBitmap
import org.melodist.ui.monet.MonetSeed
import org.melodist.ui.monet.MonetSeedExtractor

data class PlayerMonetColors(
    val darkBackgroundColor: Color = Color(0xFF141416),
    val lightBackgroundColor: Color = Color(0xFFF7F7FA),
    val darkAccentColor: Color = Color(0xFF5CE6AA),
    val lightAccentColor: Color = Color(0xFF1E825B),
    val darkHighlightColor: Color = Color(0xFF85ECC0),
    val lightHighlightColor: Color = Color(0xFF2DB580),
    val shadowTint: Color = Color(0xFF0D1016),
) {
    constructor(
        darkBackgroundColor: Color = Color(0xFF141416),
        lightBackgroundColor: Color = Color(0xFFF7F7FA),
        accentColor: Color = Color(0xFF2DB580),
        highlightColor: Color = Color(0xFF3EC896),
        shadowTint: Color = Color(0xFF0D1016),
    ) : this(
        darkBackgroundColor = darkBackgroundColor,
        lightBackgroundColor = lightBackgroundColor,
        darkAccentColor = accentColor,
        lightAccentColor = accentColor,
        darkHighlightColor = highlightColor,
        lightHighlightColor = highlightColor,
        shadowTint = shadowTint,
    )

    val backgroundColor: Color get() = darkBackgroundColor
    val accentColor: Color get() = darkAccentColor
    val highlightColor: Color get() = darkHighlightColor

    fun getAccentColor(isDark: Boolean): Color = if (isDark) darkAccentColor else lightAccentColor

    fun getHighlightColor(isDark: Boolean): Color = if (isDark) darkHighlightColor else lightHighlightColor

    fun getBackgroundColor(
        isDark: Boolean,
        isAmoled: Boolean = false,
    ): Color =
        if (isDark) {
            if (isAmoled) Color.Black else darkBackgroundColor
        } else {
            lightBackgroundColor
        }
}

fun resolveMonetColors(palette: Palette): PlayerMonetColors {
    val seed = MonetSeedExtractor.extractFromPalette(palette)
    return resolveMonetColorsFromSeed(seed)
}

fun resolveMonetColorsFromSeed(seed: MonetSeed): PlayerMonetColors {
    val hue = seed.hue
    val sat = seed.saturation

    val lightBgHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.12f, 0.20f),
            0.93f,
        )
    val lightBgColorInt = ColorUtils.HSLToColor(lightBgHsl)

    val darkBgHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.04f, 0.08f),
            0.075f,
        )
    val darkBgColorInt = ColorUtils.HSLToColor(darkBgHsl)

    val shadowHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.28f, 0.45f),
            0.07f,
        )
    val shadowTintInt = ColorUtils.HSLToColor(shadowHsl)

    val accentHue = seed.accentHue
    val accentSat = seed.accentSaturation

    val isYellowGreen = accentHue in 40f..100f
    val isRed = accentHue in 340f..360f || accentHue in 0f..25f
    val isBlueViolet = accentHue in 210f..275f

    val lightAccentL =
        if (isYellowGreen) {
            0.36f
        } else {
            0.44f
        }
    val lightAccentColorInt =
        ColorUtils.HSLToColor(
            floatArrayOf(
                accentHue,
                accentSat.coerceIn(0.65f, 0.90f),
                lightAccentL,
            ),
        )
    val lightHighlightColorInt =
        ColorUtils.HSLToColor(
            floatArrayOf(
                accentHue,
                accentSat.coerceIn(0.60f, 0.85f),
                (lightAccentL - 0.08f).coerceAtLeast(0.28f),
            ),
        )

    val darkAccentL =
        when {
            isBlueViolet -> 0.70f
            isRed -> 0.62f
            isYellowGreen -> 0.54f
            else -> 0.64f
        }

    val darkAccentSat =
        when {
            isBlueViolet -> accentSat.coerceIn(0.55f, 0.72f)
            isYellowGreen -> accentSat.coerceIn(0.65f, 0.85f)
            else -> accentSat.coerceIn(0.65f, 0.82f)
        }

    val darkAccentColorInt =
        ColorUtils.HSLToColor(
            floatArrayOf(
                accentHue,
                darkAccentSat,
                darkAccentL,
            ),
        )
    val darkHighlightColorInt =
        ColorUtils.HSLToColor(
            floatArrayOf(
                accentHue,
                (darkAccentSat - 0.05f).coerceAtLeast(0.48f),
                (darkAccentL + 0.08f).coerceAtMost(0.82f),
            ),
        )

    return PlayerMonetColors(
        darkBackgroundColor = Color(darkBgColorInt),
        lightBackgroundColor = Color(lightBgColorInt),
        darkAccentColor = Color(darkAccentColorInt),
        lightAccentColor = Color(lightAccentColorInt),
        darkHighlightColor = Color(darkHighlightColorInt),
        lightHighlightColor = Color(lightHighlightColorInt),
        shadowTint = Color(shadowTintInt),
    )
}

object PlayerMonetCacheManager {
    private val memoryCache = androidx.collection.LruCache<String, PlayerMonetColors>(50)

    fun get(songMid: String): PlayerMonetColors? {
        if (songMid.isBlank()) return null
        return memoryCache.get(songMid)
    }

    fun put(
        songMid: String,
        colors: PlayerMonetColors,
    ) {
        if (songMid.isBlank()) return
        memoryCache.put(songMid, colors)
    }

    suspend fun extractAndCache(
        context: android.content.Context,
        song: org.melodist.model.Song,
    ): PlayerMonetColors? {
        val mid = song.songMid
        if (mid.isBlank()) return null
        val cached = get(mid)
        if (cached != null) return cached

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val loader = coil3.SingletonImageLoader.get(context)
                val candidates =
                    org.melodist.mobile.util.MobileCoverCacheResolver
                        .resolvePaletteCandidates(song)
                for (source in candidates) {
                    val request =
                        coil3.request.ImageRequest
                            .Builder(context)
                            .data(source)
                            .size(128, 128)
                            .precision(coil3.size.Precision.INEXACT)
                            .build()
                    val result = loader.execute(request)
                    if (result is coil3.request.SuccessResult) {
                        val rawBitmap = result.image.toBitmap()
                        val softwareBitmap =
                            if (rawBitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                                rawBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            } else {
                                rawBitmap
                            }
                        if (softwareBitmap != null) {
                            val palette = Palette.from(softwareBitmap).generate()
                            val colors = resolveMonetColors(palette)
                            put(mid, colors)
                            return@withContext colors
                        }
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
        }
    }
}
