package org.melodist.tv.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalTvMaterial3Api::class)
private val DarkColorPalette =
    darkColorScheme(
        primary = MelodistColors.AccentGreen,
        onPrimary = Color.Black,
        surface = MelodistColors.SurfaceDark,
        onSurface = MelodistColors.TextPrimary,
        surfaceVariant = MelodistColors.ContainerDark,
        onSurfaceVariant = MelodistColors.TextSecondary,
        border = MelodistColors.FocusTeal,
    )

/**
 * 专辑莫奈色度提取器 (Material You Monet Dark Surface Spec)
 * 从专辑封面采样色相并计算暗色背景
 */
object MonetColorExtractor {
    private val colorCache = android.util.LruCache<String, Color>(128)
    val DefaultSurfaceColor = Color(0xFF142032) // 默认暗色 (明度 ~20%)

    fun getCachedColor(url: String?): Color? {
        if (url.isNullOrBlank()) return null
        return colorCache.get(url)
    }

    suspend fun extractFromUrl(url: String): Color {
        if (url.isBlank()) return DefaultSurfaceColor
        colorCache.get(url)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val localFile =
                    when {
                        url.startsWith("file://") -> File(url.removePrefix("file://"))
                        url.startsWith("/") -> File(url)
                        else -> null
                    }
                if (localFile != null && localFile.exists() && localFile.length() > 0) {
                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = 8
                        }
                    val bitmap = BitmapFactory.decodeFile(localFile.absolutePath, options)
                    if (bitmap != null) {
                        val color = extractMonetDarkSurface(bitmap)
                        bitmap.recycle()
                        colorCache.put(url, color)
                        return@withContext color
                    }
                }

                val conn = URL(url).openConnection() as? HttpURLConnection ?: return@withContext DefaultSurfaceColor
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.setRequestProperty("Referer", "https://y.qq.com/")
                conn.instanceFollowRedirects = true
                conn.connect()
                val inputStream = conn.inputStream
                val options =
                    BitmapFactory.Options().apply {
                        inSampleSize = 8
                    }
                val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream.close()
                conn.disconnect()

                if (bitmap != null) {
                    val color = extractMonetDarkSurface(bitmap)
                    bitmap.recycle()
                    colorCache.put(url, color)
                    color
                } else {
                    DefaultSurfaceColor
                }
            } catch (_: Exception) {
                DefaultSurfaceColor
            }
        }
    }

    /**
     * 全局统一莫奈调色规则：温和饱和度、舒适暗色大屏氛围、防刺眼
     */
    fun applyUnifiedMonetCurve(
        avgHue: Float,
        rawSat: Float,
    ): Color {
        // 饱和度收敛在 0.30f..0.46f，避免强烈色彩刺眼
        val targetSat = (rawSat * 0.95f).coerceIn(0.30f, 0.46f)
        // 限制明度为 0.25f，平衡深色氛围与色彩辨识度
        val targetVal = 0.25f
        val targetHsv = floatArrayOf(avgHue, targetSat, targetVal)
        val argb = android.graphics.Color.HSVToColor(targetHsv)
        return Color(argb)
    }

    private fun extractMonetDarkSurface(bitmap: Bitmap): Color {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = (width / 24).coerceAtLeast(1)
        val stepY = (height / 24).coerceAtLeast(1)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val hsl = FloatArray(3)
        var sumHueSin = 0.0
        var sumHueCos = 0.0
        var sumSat = 0.0
        var totalWeight = 0.0

        for (y in 0 until height step stepY) {
            val rowOffset = y * width
            for (x in 0 until width step stepX) {
                val pixel = pixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                android.graphics.Color.RGBToHSV(r, g, b, hsl)
                val h = hsl[0]
                val s = hsl[1]
                val v = hsl[2]

                // 排除极暗、极亮与无彩黑白灰，优先采样高饱和鲜艳像素
                if (v in 0.12f..0.96f && s >= 0.12f) {
                    val weight = s * s
                    val rad = Math.toRadians(h.toDouble())
                    sumHueSin += sin(rad) * weight
                    sumHueCos += cos(rad) * weight
                    sumSat += s * weight
                    totalWeight += weight
                }
            }
        }

        if (totalWeight <= 0.0) return DefaultSurfaceColor

        var avgHue = Math.toDegrees(atan2(sumHueSin, sumHueCos)).toFloat()
        if (avgHue < 0) avgHue += 360f

        val avgSat = (sumSat / totalWeight).toFloat()
        return applyUnifiedMonetCurve(avgHue, avgSat)
    }

    suspend fun extractWeightedFromUrls(
        urls: List<String>,
        weights: List<Float> = listOf(0.5f, 0.3f, 0.2f),
    ): Color {
        val validUrls = urls.filter { it.isNotBlank() }.take(3)
        if (validUrls.isEmpty()) return DefaultSurfaceColor
        if (validUrls.size == 1) return extractFromUrl(validUrls[0])

        val colors = validUrls.map { extractFromUrl(it) }
        var sumHueSin = 0.0
        var sumHueCos = 0.0
        var sumSat = 0.0
        var totalWeight = 0.0
        val hsv = FloatArray(3)

        colors.forEachIndexed { idx, color ->
            val w = weights.getOrElse(idx) { 0.2f }
            val r = (color.red * 255).toInt()
            val g = (color.green * 255).toInt()
            val b = (color.blue * 255).toInt()
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val weight = s * w
            val rad = Math.toRadians(h.toDouble())
            sumHueSin += sin(rad) * weight
            sumHueCos += cos(rad) * weight
            sumSat += s * weight
            totalWeight += weight
        }

        if (totalWeight <= 0.0) return colors[0]

        var avgHue = Math.toDegrees(atan2(sumHueSin, sumHueCos)).toFloat()
        if (avgHue < 0) avgHue += 360f

        val avgSat = (sumSat / totalWeight).toFloat()
        return applyUnifiedMonetCurve(avgHue, avgSat)
    }
}

/**
 * 提取专辑主色作为背景色
 * 支持切歌时平滑淡入过渡动画
 */
@Composable
fun rememberMonetSurfaceColor(coverUrl: String? = null): Color {
    val cached = remember(coverUrl) { MonetColorExtractor.getCachedColor(coverUrl) }
    var targetColor by remember(coverUrl) { mutableStateOf(cached ?: MonetColorExtractor.DefaultSurfaceColor) }

    LaunchedEffect(coverUrl) {
        if (!coverUrl.isNullOrBlank()) {
            targetColor = MonetColorExtractor.extractFromUrl(coverUrl)
        } else {
            targetColor = MonetColorExtractor.DefaultSurfaceColor
        }
    }

    return animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 700),
        label = "MonetSurfaceColorTransition",
    ).value
}

@Composable
fun rememberMonetSurfaceColor(baseColor: Color): Color =
    remember(baseColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(baseColor.toArgb(), hsv)
        MonetColorExtractor.applyUnifiedMonetCurve(hsv[0], hsv[1])
    }

val LocalMonetSurface = compositionLocalOf { MonetColorExtractor.DefaultSurfaceColor }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MelodistTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorPalette,
        content = content,
    )
}

/**
 * 基于莫奈基础表面色推导具有同色相的深色容器层色 (Material You Surface Container)
 * 按层级提升明度计算容器背景色
 */
fun Color.toMonetContainer(elevation: Float = 0.06f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[2] = (hsv[2] + elevation).coerceIn(0f, 1f)
    hsv[1] = (hsv[1] * 0.90f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
