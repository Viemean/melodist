package org.melodist.tv.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.data.AppSettingsManager
import org.melodist.data.ScreenSaverBrightness
import org.melodist.data.ScreenSaverTimeout
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ScreenSaverPanel(menuRequester: FocusRequester) {
    val settings by AppSettingsManager.settings.collectAsState()
    val scrollState = rememberScrollState()
    var isPreviewing by remember { mutableStateOf(false) }

    if (isPreviewing) {
        ScreenSaverOverlay(
            enablePixelShift = settings.enablePixelShift,
            onDismiss = { isPreviewing = false },
        )
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingPanelHeader(title = "OLED 屏保与显示保护")

        // 1. 自动进入屏保闲置时长
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "自动进入屏保等待时间",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScreenSaverTimeout.entries.forEachIndexed { index, timeout ->
                    val isSelected = (settings.screenSaverTimeout == timeout)
                    val btnModifier =
                        Modifier
                            .weight(1f)
                            .then(if (index == 0) Modifier.focusProperties { left = menuRequester } else Modifier)

                    Button(
                        onClick = { AppSettingsManager.updateScreenSaverTimeout(timeout) },
                        modifier = btnModifier,
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.ButtonCorner,
                                focusedShape = MelodistShapes.ButtonCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor =
                                    if (isSelected) {
                                        MelodistColors.AccentGreen.copy(
                                            alpha = 0.22f,
                                        )
                                    } else {
                                        Color.White.copy(alpha = 0.05f)
                                    },
                                focusedContainerColor = Color.White,
                                contentColor = if (isSelected) MelodistColors.AccentGreen else Color.White,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                border =
                                    Border(
                                        border =
                                            if (isSelected) {
                                                BorderStroke(1.5.dp, MelodistColors.AccentGreen)
                                            } else {
                                                BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                                            },
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                                focusedBorder =
                                    Border(
                                        border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                            ),
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text(
                            text = timeout.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 2. 屏保显示亮度调节
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "屏保显示亮度",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScreenSaverBrightness.entries.forEachIndexed { index, brightness ->
                    val isSelected = (settings.screenSaverBrightness == brightness)
                    val btnModifier =
                        Modifier
                            .weight(1f)
                            .then(if (index == 0) Modifier.focusProperties { left = menuRequester } else Modifier)

                    Button(
                        onClick = { AppSettingsManager.updateScreenSaverBrightness(brightness) },
                        modifier = btnModifier,
                        shape =
                            ButtonDefaults.shape(
                                shape = MelodistShapes.ButtonCorner,
                                focusedShape = MelodistShapes.ButtonCorner,
                            ),
                        colors =
                            ButtonDefaults.colors(
                                containerColor =
                                    if (isSelected) {
                                        MelodistColors.AccentGreen.copy(
                                            alpha = 0.22f,
                                        )
                                    } else {
                                        Color.White.copy(alpha = 0.05f)
                                    },
                                focusedContainerColor = Color.White,
                                contentColor = if (isSelected) MelodistColors.AccentGreen else Color.White,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                border =
                                    Border(
                                        border =
                                            if (isSelected) {
                                                BorderStroke(1.5.dp, MelodistColors.AccentGreen)
                                            } else {
                                                BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                                            },
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                                focusedBorder =
                                    Border(
                                        border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                                        shape = MelodistShapes.ButtonCorner,
                                    ),
                            ),
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text(
                            text = brightness.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 3. OLED 防烧屏像素微移开关
        SettingSwitchCard(
            title = "OLED 防烧屏像素微移",
            checked = settings.enablePixelShift,
            menuRequester = menuRequester,
            onToggle = {
                AppSettingsManager.updateEnablePixelShift(!settings.enablePixelShift)
            },
        )

        // 4. 音乐播放中允许进入屏保开关 (移除多余描述)
        SettingSwitchCard(
            title = "音频播放期间允许进入屏保",
            checked = settings.enableScreenSaverDuringPlayback,
            menuRequester = menuRequester,
            onToggle = {
                AppSettingsManager.updateEnableScreenSaverDuringPlayback(!settings.enableScreenSaverDuringPlayback)
            },
        )

        // 5. 立即预览屏保按钮
        Button(
            onClick = { isPreviewing = true },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusProperties { left = menuRequester },
            shape =
                ButtonDefaults.shape(
                    shape = MelodistShapes.ButtonCorner,
                    focusedShape = MelodistShapes.ButtonCorner,
                ),
            colors =
                ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.06f),
                    focusedContainerColor = Color.White,
                    contentColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
            border =
                ButtonDefaults.border(
                    border =
                        Border(
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                    focusedBorder =
                        Border(
                            border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                ),
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "立即预览屏幕保护效果",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            text = "提示：自动亮度支持根据电视环境光传感器在暗室与亮室间自适应切换（无光感机型保持标准亮度）；若电视系统级屏保仍覆盖了应用屏保，可在电视系统设置的“显示”或“屏保”中关闭系统屏保。",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.5f),
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
fun ScreenSaverOverlay(
    enablePixelShift: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val settings by AppSettingsManager.settings.collectAsState()

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 环境光传感器自适应与迟滞防抖
    var isAmbientDark by remember { mutableStateOf(false) }
    var hasLightSensor by remember { mutableStateOf(false) }
    DisposableEffect(settings.screenSaverBrightness) {
        if (settings.screenSaverBrightness == ScreenSaverBrightness.Auto) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            hasLightSensor = (lightSensor != null)

            if (lightSensor != null) {
                val listener =
                    object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            val lux = event?.values?.getOrNull(0) ?: return
                            // 迟滞门限：低于 40 lux 判定为暗室/拉窗帘，高于 70 lux 判定为开灯/白天
                            if (lux < 40f) {
                                isAmbientDark = true
                            } else if (lux > 70f) {
                                isAmbientDark = false
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
                onDispose {
                    sensorManager.unregisterListener(listener)
                }
            } else {
                onDispose {}
            }
        } else {
            onDispose {}
        }
    }

    val targetBrightnessAlpha =
        when (settings.screenSaverBrightness) {
            ScreenSaverBrightness.Auto -> {
                if (hasLightSensor && isAmbientDark) {
                    ScreenSaverBrightness.Soft.alpha
                } else {
                    ScreenSaverBrightness.Standard.alpha
                }
            }
            ScreenSaverBrightness.Soft -> ScreenSaverBrightness.Soft.alpha
            ScreenSaverBrightness.Standard -> ScreenSaverBrightness.Standard.alpha
            ScreenSaverBrightness.Bright -> ScreenSaverBrightness.Bright.alpha
        }
    val animatedBrightnessAlpha by animateFloatAsState(
        targetValue = targetBrightnessAlpha,
        animationSpec = tween(durationMillis = 600),
        label = "screenSaverBrightnessAlpha",
    )

    BackHandler {
        onDismiss()
    }

    val currentSong by PlaybackManager.currentSong.collectAsState()

    var canDismiss by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(600L) // 延迟避免确认键连击触发退出
        canDismiss = true
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 像素微移：业界 OLED 防烧屏采用离散微步策略（每 60 秒平滑微移一步，其余 57.5 秒完全静止）
    val shiftCoordinates =
        remember {
            listOf(
                IntOffset(0, 0),
                IntOffset(12, 8),
                IntOffset(-8, 12),
                IntOffset(14, -6),
                IntOffset(-12, -8),
                IntOffset(8, -10),
                IntOffset(-14, 6),
                IntOffset(6, 14),
            )
        }
    var currentCoordinateIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(enablePixelShift) {
        if (!enablePixelShift) {
            currentCoordinateIndex = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(60_000L) // 每 60 秒微调一次坐标
            currentCoordinateIndex = (currentCoordinateIndex + 1) % shiftCoordinates.size
        }
    }
    val animatedPixelOffset by animateIntOffsetAsState(
        targetValue = if (enablePixelShift) shiftCoordinates[currentCoordinateIndex] else IntOffset.Zero,
        animationSpec = tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "pixelShift",
    )

    // 当前系统时间与日期拆分为 5 个独立区域
    var hourStr by remember { mutableStateOf("") }
    var minuteStr by remember { mutableStateOf("") }
    var monthDayStr by remember { mutableStateOf("") }
    var dayOfWeekStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val hourSdf = SimpleDateFormat("HH", Locale.getDefault())
        val minuteSdf = SimpleDateFormat("mm", Locale.getDefault())
        val monthDaySdf = SimpleDateFormat("M月d日", Locale.CHINESE)
        val dayOfWeekSdf = SimpleDateFormat("EEEE", Locale.CHINESE)
        while (true) {
            val now = Date()
            hourStr = hourSdf.format(now)
            minuteStr = minuteSdf.format(now)
            monthDayStr = monthDaySdf.format(now)
            dayOfWeekStr = dayOfWeekSdf.format(now)
            delay(1000L)
        }
    }

    val currentTime = if (hourStr.isNotBlank() && minuteStr.isNotBlank()) "$hourStr:$minuteStr" else "--:--"

    // 提取专辑主色与辅色
    val effectiveCoverUrl =
        remember(currentSong) {
            currentSong?.coverUrl?.ifBlank { null }
                ?: currentSong?.albumMid?.takeIf { it.isNotBlank() }?.let {
                    MusicApiService.getAlbumCoverUrl(it)
                }
        }

    var albumColors by remember {
        mutableStateOf(ScreenSaverColorExtractor.fallbackColors(currentSong?.name ?: currentSong?.songMid))
    }

    LaunchedEffect(effectiveCoverUrl, currentSong?.songMid) {
        albumColors =
            ScreenSaverColorExtractor.extractColors(
                context = context,
                coverUrl = effectiveCoverUrl,
                fallbackKey = currentSong?.name ?: currentSong?.songMid,
            )
    }

    val animatedTimeColor by animateColorAsState(
        targetValue = albumColors.first,
        animationSpec = tween(durationMillis = 800),
        label = "albumTimeColor",
    )
    val animatedDateColor by animateColorAsState(
        targetValue = albumColors.second,
        animationSpec = tween(durationMillis = 800),
        label = "albumDateColor",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (canDismiss) {
                                onDismiss()
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }.padding(horizontal = 64.dp, vertical = 48.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .offset { animatedPixelOffset }
                        .alpha(animatedBrightnessAlpha),
                contentAlignment = Alignment.Center,
            ) {
                val song = currentSong
                val clockFontSize = if (song != null) 144.sp else 170.sp
                val dotSize = if (song != null) 12.dp else 14.dp
                val dotSpacing = if (song != null) 28.dp else 32.dp
                val dotHorizontalPadding = if (song != null) 12.dp else 14.dp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 时间文本
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = hourStr,
                            fontSize = clockFontSize,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraLight,
                            color = animatedTimeColor,
                            letterSpacing = 4.sp,
                        )
                        Column(
                            modifier = Modifier.padding(horizontal = dotHorizontalPadding),
                            verticalArrangement = Arrangement.spacedBy(dotSpacing),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(dotSize)
                                        .clip(CircleShape)
                                        .background(animatedTimeColor),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .size(dotSize)
                                        .clip(CircleShape)
                                        .background(animatedTimeColor),
                            )
                        }
                        Text(
                            text = minuteStr,
                            fontSize = clockFontSize,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraLight,
                            color = animatedTimeColor,
                            letterSpacing = 4.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    // 日期文本
                    Text(
                        text = if (monthDayStr.isNotBlank() && dayOfWeekStr.isNotBlank()) "$monthDayStr $dayOfWeekStr" else "",
                        fontSize = 22.sp,
                        color = animatedDateColor,
                        fontWeight = FontWeight.Normal,
                    )

                    // 当前正在播放的曲目信息
                    if (song != null) {
                        Spacer(modifier = Modifier.height(44.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 22.dp, vertical = 14.dp),
                        ) {
                            if (song.coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = song.coverUrl,
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .size(68.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = song.name,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = song.singer,
                                    fontSize = 14.sp,
                                    color = Color.White.copy(alpha = 0.5f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal object ScreenSaverColorExtractor {
    private val cache = android.util.LruCache<String, Pair<Color, Color>>(128)

    // 预置色调配对
    val MONET_PRESET_PALETTES =
        listOf(
            // 1. 睡莲天青与湖蓝
            Pair(Color(0xFF4A90E2), Color(0xFF78B5F2)),
            // 2. 麦草暖金与柔杏
            Pair(Color(0xFFD98A2B), Color(0xFFE8AE68)),
            // 3. 鸢尾雅紫与浅罗兰
            Pair(Color(0xFF8B68E0), Color(0xFFB39AE8)),
            // 4. 吉维尼池翠与荷叶青
            Pair(Color(0xFF32A885), Color(0xFF64C9AA)),
            // 5. 喜帕珊瑚与柔瑰粉
            Pair(Color(0xFFD65C5C), Color(0xFFE88A8A)),
            // 6. 圣拉扎尔群青与天际蓝
            Pair(Color(0xFF4376D6), Color(0xFF6C99EC)),
        )

    fun fallbackColors(key: String? = null): Pair<Color, Color> =
        if (!key.isNullOrBlank()) {
            val index = Math.floorMod(key.hashCode(), MONET_PRESET_PALETTES.size)
            MONET_PRESET_PALETTES[index]
        } else {
            val hour =
                java.util.Calendar
                    .getInstance()
                    .get(java.util.Calendar.HOUR_OF_DAY)
            val index = Math.floorMod(hour / 4, MONET_PRESET_PALETTES.size)
            MONET_PRESET_PALETTES[index]
        }

    suspend fun extractColors(
        context: Context,
        coverUrl: String?,
        fallbackKey: String? = null,
    ): Pair<Color, Color> {
        if (coverUrl.isNullOrBlank()) {
            return fallbackColors(fallbackKey)
        }
        cache.get(coverUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val imageLoader = SingletonImageLoader.get(context)
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(coverUrl)
                        .size(48, 48)
                        .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    val pair = analyzeBitmap(bitmap, fallbackKey ?: coverUrl)
                    cache.put(coverUrl, pair)
                    pair
                } else {
                    fallbackColors(fallbackKey)
                }
            } catch (_: Exception) {
                fallbackColors(fallbackKey)
            }
        }
    }

    private fun analyzeBitmap(
        bitmap: Bitmap,
        fallbackKey: String,
    ): Pair<Color, Color> {
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

                // 过滤暗色像素
                if (v < 0.18f) continue
                // 过滤高亮与白色像素
                if (v >= 0.75f && s < 0.28f) continue
                // 过滤低饱和度中性灰像素
                if (s < 0.18f) continue

                // 权重倾向于鲜艳饱满且明度适中的核心彩色像素
                val weight = (s * s) * (1.0f - abs(v - 0.60f) * 0.7f).coerceIn(0.1f, 1.0f)
                val rad = Math.toRadians(h.toDouble())
                sumHueSin += sin(rad) * weight
                sumHueCos += cos(rad) * weight
                sumSat += s * weight
                totalWeight += weight
            }
        }

        if (totalWeight <= 0.0) {
            // 无有效色彩时回退至预置配色
            return fallbackColors(fallbackKey)
        }

        var avgHue = Math.toDegrees(atan2(sumHueSin, sumHueCos)).toFloat()
        if (avgHue < 0) avgHue += 360f

        val avgSat = (sumSat / totalWeight).toFloat()

        // 限制饱和度下限为 0.55f
        val primarySat = avgSat.coerceIn(0.55f, 0.85f)
        // 限制明度为 0.86f
        val primaryHsv = floatArrayOf(avgHue, primarySat, 0.86f)
        val primaryColor = Color(android.graphics.Color.HSVToColor(primaryHsv))

        // 辅色色相偏移 28°，明度 0.78f
        val secondaryHue = (avgHue + 28f) % 360f
        val secondarySat = (primarySat * 0.85f).coerceIn(0.45f, 0.75f)
        val secondaryHsv = floatArrayOf(secondaryHue, secondarySat, 0.78f)
        val secondaryColor = Color(android.graphics.Color.HSVToColor(secondaryHsv))

        return Pair(primaryColor, secondaryColor)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

