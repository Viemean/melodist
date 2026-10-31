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
    val accentColor: Color = Color(0xFF2DB580),
    val highlightColor: Color = Color(0xFF3EC896),
    val shadowTint: Color = Color(0xFF0D1016),
) {
    val backgroundColor: Color get() = darkBackgroundColor

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

    val accentHsl =
        floatArrayOf(
            seed.accentHue,
            seed.accentSaturation.coerceIn(0.50f, 0.75f),
            seed.accentLightness.coerceIn(0.42f, 0.58f),
        )
    val accentColorInt = ColorUtils.HSLToColor(accentHsl)

    val hlHsl =
        floatArrayOf(
            accentHsl[0],
            accentHsl[1].coerceIn(0.55f, 0.78f),
            0.62f,
        )
    val highlightColorInt = ColorUtils.HSLToColor(hlHsl)

    return PlayerMonetColors(
        darkBackgroundColor = Color(darkBgColorInt),
        lightBackgroundColor = Color(lightBgColorInt),
        accentColor = Color(accentColorInt),
        highlightColor = Color(highlightColorInt),
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
