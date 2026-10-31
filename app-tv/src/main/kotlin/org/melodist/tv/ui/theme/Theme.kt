package org.melodist.tv.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.ui.monet.MonetSeed
import org.melodist.ui.monet.MonetSeedExtractor
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
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

data class TvMonetColors(
    val surfaceColor: Color = Color(0xFF121418),
    val accentColor: Color = MelodistColors.AccentGreen,
    val containerColor: Color = Color(0xFF1A1D24),
)

fun resolveTvMonetColors(seed: MonetSeed): TvMonetColors {
    val hue = seed.hue
    val targetSat = (seed.saturation * 0.95f).coerceIn(0.30f, 0.46f)
    val targetVal = 0.24f

    val surfaceHsv = floatArrayOf(hue, targetSat, targetVal)
    val darkSurface = Color(android.graphics.Color.HSVToColor(surfaceHsv))

    val accentHsv =
        floatArrayOf(
            seed.accentHue,
            seed.accentSaturation.coerceIn(0.60f, 0.85f),
            0.92f,
        )
    val accent = Color(android.graphics.Color.HSVToColor(accentHsv))

    val containerHsv =
        floatArrayOf(
            hue,
            (targetSat * 0.85f).coerceIn(0.24f, 0.40f),
            (targetVal + 0.08f).coerceIn(0f, 1f),
        )
    val container = Color(android.graphics.Color.HSVToColor(containerHsv))

    return TvMonetColors(
        surfaceColor = darkSurface,
        accentColor = accent,
        containerColor = container,
    )
}

object MonetColorExtractor {
    private val seedCache = android.util.LruCache<String, MonetSeed>(128)
    private val colorCache = android.util.LruCache<String, Color>(128)
    val DefaultSurfaceColor = Color(0xFF121418)
    val DefaultLightSurfaceColor = Color(0xFFF7F7F9)

    fun getCachedColor(url: String?): Color? {
        if (url.isNullOrBlank()) return null
        return colorCache.get(url)
    }

    suspend fun extractSeedFromUrl(
        context: Context? = null,
        url: String,
    ): MonetSeed? {
        if (url.isBlank()) return null
        seedCache.get(url)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val cleanUrl = url.substringBefore('?')
                val decodedPath =
                    try {
                        URLDecoder.decode(
                            if (cleanUrl.startsWith("file://")) cleanUrl.removePrefix("file://") else cleanUrl,
                            "UTF-8",
                        )
                    } catch (_: Exception) {
                        if (cleanUrl.startsWith("file://")) cleanUrl.removePrefix("file://") else cleanUrl
                    }

                val localFile = if (decodedPath.startsWith("/")) File(decodedPath) else null
                if (localFile != null && localFile.exists() && localFile.length() > 0) {
                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = 4
                        }
                    val bitmap = BitmapFactory.decodeFile(localFile.absolutePath, options)
                    if (bitmap != null) {
                        val seed = MonetSeedExtractor.extractFromBitmap(bitmap)
                        bitmap.recycle()
                        seedCache.put(url, seed)
                        return@withContext seed
                    }
                }

                if (context != null) {
                    try {
                        val loader = SingletonImageLoader.get(context)
                        val request =
                            ImageRequest
                                .Builder(context)
                                .data(url)
                                .size(128, 128)
                                .precision(coil3.size.Precision.INEXACT)
                                .allowHardware(false)
                                .build()
                        val result = loader.execute(request)
                        if (result is SuccessResult) {
                            val bitmap = result.image.toBitmap()
                            val seed = MonetSeedExtractor.extractFromBitmap(bitmap)
                            seedCache.put(url, seed)
                            return@withContext seed
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Log.w("MonetColorExtractor", "Operation failed", e)
                    }
                }

                if (cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://")) {
                    val conn = URL(cleanUrl).openConnection() as? HttpURLConnection ?: return@withContext null
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
                        val seed = MonetSeedExtractor.extractFromBitmap(bitmap)
                        bitmap.recycle()
                        seedCache.put(url, seed)
                        return@withContext seed
                    }
                }

                null
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun extractFromUrl(
        context: Context? = null,
        url: String,
    ): Color {
        if (url.isBlank()) return DefaultSurfaceColor
        colorCache.get(url)?.let { return it }

        val seed = extractSeedFromUrl(context, url)
        val color =
            if (seed != null) {
                resolveTvMonetColors(seed).surfaceColor
            } else {
                DefaultSurfaceColor
            }
        colorCache.put(url, color)
        return color
    }

    suspend fun extractFromUrl(url: String): Color = extractFromUrl(null, url)

    fun applyUnifiedMonetCurve(
        avgHue: Float,
        rawSat: Float,
    ): Color {
        val targetSat = (rawSat * 0.95f).coerceIn(0.30f, 0.46f)
        val targetVal = 0.24f
        val targetHsv = floatArrayOf(avgHue, targetSat, targetVal)
        val argb = android.graphics.Color.HSVToColor(targetHsv)
        return Color(argb)
    }

    suspend fun extractWeightedFromUrls(
        urls: List<String>,
        weights: List<Float> = listOf(0.5f, 0.3f, 0.2f),
    ): Color {
        val validUrls = urls.filter { it.isNotBlank() }.take(3)
        if (validUrls.isEmpty()) return DefaultSurfaceColor
        if (validUrls.size == 1) return extractFromUrl(null, validUrls[0])

        val colors = validUrls.map { extractFromUrl(null, it) }
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
 * 支持切歌时的淡入过渡动画
 */
@Composable
fun rememberMonetSurfaceColor(coverUrl: String? = null): Color {
    val context = LocalContext.current
    val cached = remember(coverUrl) { MonetColorExtractor.getCachedColor(coverUrl) }
    var targetColor by remember(coverUrl) { mutableStateOf(cached ?: MonetColorExtractor.DefaultSurfaceColor) }

    LaunchedEffect(coverUrl) {
        if (!coverUrl.isNullOrBlank()) {
            targetColor = MonetColorExtractor.extractFromUrl(context, coverUrl)
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
