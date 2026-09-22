package org.melodist.mobile.ui.player.components

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.toBitmap

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
    val hsl = FloatArray(3)

    // 过滤纯黑纯白极度过曝与死灰噪点（明度限定 0.08f..0.92f，饱和度 >= 0.10f）
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
            // 对高饱和度赋予极高权重，确保白底插画中的小面积彩色（美甲、腮红等）能被精准识别并放大为背景主题
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
    if (selectedSwatch != null) {
        ColorUtils.colorToHSL(selectedSwatch.rgb, hsl)
        hue = hsl[0]
        sat = hsl[1]
    } else {
        // 全黑白图片默认回退优雅微冷蓝调
        hue = 215f
        sat = 0.22f
    }

    // 1. 亮色背景色：优雅淡雅的莫奈微粉/微蓝/微青微色底（明度 0.93f，饱和度 0.12f..0.20f）
    val lightBgHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.12f, 0.20f),
            0.93f,
        )
    val lightBgColorInt = ColorUtils.HSLToColor(lightBgHsl)

    // 2. 深色背景色：沉稳纯净的黑曜石深灰色（明度 0.075f，饱和度极弱 0.04f..0.08f），避免红棕或暗彩色造成视觉疲劳
    val darkBgHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.04f, 0.08f),
            0.075f,
        )
    val darkBgColorInt = ColorUtils.HSLToColor(darkBgHsl)

    // 3. 阴影基色（深沉色相匹配，消除摩尔纹与断层）：明度 0.07f
    val shadowHsl =
        floatArrayOf(
            hue,
            sat.coerceIn(0.28f, 0.45f),
            0.07f,
        )
    val shadowTintInt = ColorUtils.HSLToColor(shadowHsl)

    // 4. 按钮与进度条强调色：从封面提取鲜亮但不过曝的强调色
    val accentSwatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: selectedSwatch
    val accentHsl = FloatArray(3)
    val accentColorInt =
        if (accentSwatch != null && isValidColor(accentSwatch)) {
            ColorUtils.colorToHSL(accentSwatch.rgb, accentHsl)
            accentHsl[1] = accentHsl[1].coerceIn(0.50f, 0.75f)
            accentHsl[2] = accentHsl[2].coerceIn(0.42f, 0.58f)
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

    // 5. 歌词高亮色：与强调色同色相，通透轻盈
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
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                                rawBitmap.config == android.graphics.Bitmap.Config.HARDWARE
                            ) {
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
