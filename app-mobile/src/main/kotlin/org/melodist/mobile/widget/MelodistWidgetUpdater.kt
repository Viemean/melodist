package org.melodist.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.melodist.mobile.MainActivity
import org.melodist.mobile.R
import org.melodist.mobile.ui.player.components.PlayerMonetCacheManager
import org.melodist.mobile.ui.player.components.PlayerMonetColors
import org.melodist.mobile.ui.player.components.resolveMonetColors
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager

private data class WidgetSystemTheme(
    val cardBgColor: Int,
    val playBtnBgColor: Int,
    val playBtnIconColor: Int,
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
)

/**
 * 负责监听播放状态并异步刷新桌面/智慧桌面 4x2 音乐小组件。
 */
object MelodistWidgetUpdater {
    private const val TAG = "MelodistWidgetUpdater"
    private const val COVER_LOAD_SIZE_PX = 500
    private const val COVER_WIDGET_PX = 300
    private const val COVER_CORNER_DP = 8f
    private const val HALF_DIVISOR = 2f
    private const val PLAY_ICON_LUMINANCE_THRESHOLD = 0.45
    private const val ORIGIN_WIDGET_PKG = "com.tme.vivowidget"
    private const val ORIGIN_WIDGET_RECEIVER = "com.tme.vivowidget.WidgetProvider"
    private const val ORIGIN_SYNC_AUTHORITY = "com.tme.vivowidget.sync"
    private const val ORIGIN_SYNC_METHOD = "sync_state"
    private const val ORIGIN_ACTION_SYNC_STATE = "org.melodist.widget.ACTION_SYNC_STATE"
    private const val COVER_COMPRESS_QUALITY = 90
    private const val REQ_CODE_PREV = 2
    private const val REQ_CODE_PLAY_PAUSE = 3
    private const val REQ_CODE_NEXT = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedSongMid: String? = null

    @Volatile
    private var cachedCoverUrl: String? = null

    @Volatile
    private var cachedCoverBitmap: Bitmap? = null

    @Volatile
    private var cachedMonetColors: PlayerMonetColors? = null

    private var coverLoadJob: Job? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        scope.launch {
            combine(
                PlaybackManager.currentSong,
                PlaybackManager.isPlaying,
            ) { _, _ -> Unit }
                .collect {
                    updateAllWidgets(appContext)
                }
        }
    }

    fun updateAllWidgets(
        context: Context,
        appWidgetIds: IntArray? = null,
    ) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val provider = ComponentName(appContext, MelodistWidgetProvider::class.java)
        val targetIds = appWidgetIds ?: manager.getAppWidgetIds(provider)
        val hasOriginWidget = isOriginWidgetInstalled(appContext)
        if (targetIds.isEmpty() && !hasOriginWidget) return

        val currentSong = PlaybackManager.currentSong.value
        val songMid = currentSong?.songMid
        val coverUrl = currentSong?.coverUrl?.takeIf { it.isNotBlank() }

        val preloadedCover =
            if (coverUrl != null && coverUrl == cachedCoverUrl) cachedCoverBitmap else null
        val preloadedMonet =
            currentSong?.let { PlayerMonetCacheManager.get(it.songMid) }
                ?: if (songMid != null && songMid == cachedSongMid) cachedMonetColors else null

        val theme = resolveSystemTheme(appContext, preloadedMonet)
        if (targetIds.isNotEmpty()) {
            pushRemoteViews(appContext, manager, targetIds, preloadedCover, theme)
        }
        if (hasOriginWidget) {
            pushOriginWidgetState(appContext, currentSong, preloadedCover, theme)
        }

        val needCoverLoad = coverUrl != null && coverUrl != cachedCoverUrl
        val needMonetLoad = currentSong != null && preloadedMonet == null
        if (needCoverLoad || needMonetLoad) {
            loadCoverAndMonetRefresh(appContext, currentSong, coverUrl)
        } else if (coverUrl == null) {
            coverLoadJob?.cancel()
            cachedSongMid = songMid
            cachedCoverUrl = null
            cachedCoverBitmap = null
            cachedMonetColors = preloadedMonet
        }
    }

    private fun loadCoverAndMonetRefresh(
        appContext: Context,
        song: Song?,
        coverUrl: String?,
    ) {
        coverLoadJob?.cancel()
        coverLoadJob =
            scope.launch(Dispatchers.IO) {
                try {
                    var roundedCover: Bitmap? =
                        if (coverUrl != null && coverUrl == cachedCoverUrl) cachedCoverBitmap else null
                    var monetColors: PlayerMonetColors? =
                        song?.let { PlayerMonetCacheManager.get(it.songMid) }

                    if (coverUrl != null && (roundedCover == null || monetColors == null)) {
                        val request =
                            ImageRequest
                                .Builder(appContext)
                                .data(coverUrl)
                                .size(COVER_LOAD_SIZE_PX, COVER_LOAD_SIZE_PX)
                                .precision(Precision.INEXACT)
                                .allowHardware(false)
                                .build()
                        val result = SingletonImageLoader.get(appContext).execute(request)
                        if (result is SuccessResult) {
                            val rawBitmap = result.image.toBitmap()
                            val cornerPx = COVER_CORNER_DP * appContext.resources.displayMetrics.density
                            if (roundedCover == null) {
                                roundedCover =
                                    createRoundedCenterCropBitmap(
                                        source = rawBitmap,
                                        targetSize = COVER_WIDGET_PX,
                                        cornerRadiusPx = cornerPx,
                                    )
                            }
                            if (monetColors == null) {
                                monetColors = resolveMonetColors(Palette.from(rawBitmap).generate())
                                if (song != null && song.songMid.isNotBlank()) {
                                    PlayerMonetCacheManager.put(song.songMid, monetColors)
                                }
                            }
                        }
                    }

                    if (monetColors == null && song != null) {
                        monetColors = PlayerMonetCacheManager.extractAndCache(appContext, song)
                    }

                    cachedSongMid = song?.songMid
                    cachedCoverUrl = coverUrl
                    cachedCoverBitmap = roundedCover
                    cachedMonetColors = monetColors

                    val theme = resolveSystemTheme(appContext, monetColors)
                    val manager = AppWidgetManager.getInstance(appContext)
                    val provider = ComponentName(appContext, MelodistWidgetProvider::class.java)
                    val ids = manager.getAppWidgetIds(provider)
                    if (ids.isNotEmpty()) {
                        pushRemoteViews(appContext, manager, ids, roundedCover, theme)
                    }
                    if (isOriginWidgetInstalled(appContext)) {
                        pushOriginWidgetState(appContext, song, roundedCover, theme)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load widget cover/monet: $coverUrl", e)
                }
            }
    }

    private fun resolveSystemTheme(
        context: Context,
        monetColors: PlayerMonetColors?,
    ): WidgetSystemTheme {
        val isNight =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        val dynamicPlayBg = monetColors?.getAccentColor(isNight)?.toArgb()
        val dynamicPlayIcon =
            dynamicPlayBg?.let { bgColor ->
                if (ColorUtils.calculateLuminance(bgColor) < PLAY_ICON_LUMINANCE_THRESHOLD) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF1C1B1F.toInt()
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return if (isNight) {
                WidgetSystemTheme(
                    cardBgColor = context.getColor(android.R.color.system_neutral1_900),
                    playBtnBgColor = dynamicPlayBg ?: context.getColor(android.R.color.system_accent1_400),
                    playBtnIconColor = dynamicPlayIcon ?: 0xFFFFFFFF.toInt(),
                    primaryTextColor = context.getColor(android.R.color.system_neutral1_50),
                    secondaryTextColor = context.getColor(android.R.color.system_neutral2_300),
                )
            } else {
                WidgetSystemTheme(
                    cardBgColor = context.getColor(android.R.color.system_accent1_50),
                    playBtnBgColor = dynamicPlayBg ?: context.getColor(android.R.color.system_accent1_500),
                    playBtnIconColor = dynamicPlayIcon ?: 0xFFFFFFFF.toInt(),
                    primaryTextColor = context.getColor(android.R.color.system_neutral1_900),
                    secondaryTextColor = context.getColor(android.R.color.system_neutral2_600),
                )
            }
        }

        return if (isNight) {
            WidgetSystemTheme(
                cardBgColor = 0xFF1E1F26.toInt(),
                playBtnBgColor = dynamicPlayBg ?: 0xFFDE5D83.toInt(),
                playBtnIconColor = dynamicPlayIcon ?: 0xFFFFFFFF.toInt(),
                primaryTextColor = 0xFFF4F3FA.toInt(),
                secondaryTextColor = 0xFFB0ADC0.toInt(),
            )
        } else {
            WidgetSystemTheme(
                cardBgColor = 0xFFEDEAF8.toInt(),
                playBtnBgColor = dynamicPlayBg ?: 0xFFDE5D83.toInt(),
                playBtnIconColor = dynamicPlayIcon ?: 0xFFFFFFFF.toInt(),
                primaryTextColor = 0xFF1C1B24.toInt(),
                secondaryTextColor = 0xFF605D6C.toInt(),
            )
        }
    }

    private fun pushRemoteViews(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
        coverBitmap: Bitmap?,
        theme: WidgetSystemTheme,
    ) {
        val song = PlaybackManager.currentSong.value
        val isPlaying = PlaybackManager.isPlaying.value

        val views = RemoteViews(context.packageName, R.layout.widget_melodist_player)
        views.setInt(R.id.widget_bg, "setColorFilter", theme.cardBgColor)
        views.setInt(R.id.widget_btn_play_pause, "setColorFilter", theme.primaryTextColor)
        views.setInt(R.id.widget_btn_prev, "setColorFilter", theme.primaryTextColor)
        views.setInt(R.id.widget_btn_next, "setColorFilter", theme.primaryTextColor)
        views.setInt(R.id.widget_cover_placeholder, "setColorFilter", theme.secondaryTextColor)

        views.setTextViewText(
            R.id.widget_title,
            song?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_empty_title),
        )
        views.setTextColor(R.id.widget_title, theme.primaryTextColor)

        views.setTextViewText(
            R.id.widget_artist,
            song?.singer?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_empty_artist),
        )
        views.setTextColor(R.id.widget_artist, theme.secondaryTextColor)

        if (coverBitmap != null && !coverBitmap.isRecycled) {
            views.setImageViewBitmap(R.id.widget_cover, coverBitmap)
            views.setViewVisibility(R.id.widget_cover, View.VISIBLE)
            views.setViewVisibility(R.id.widget_cover_placeholder, View.GONE)
        } else {
            views.setImageViewBitmap(R.id.widget_cover, null)
            views.setViewVisibility(R.id.widget_cover, View.GONE)
            views.setViewVisibility(R.id.widget_cover_placeholder, View.VISIBLE)
        }

        views.setImageViewResource(
            R.id.widget_btn_play_pause,
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )

        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val openAppPendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_header, openAppPendingIntent)

        views.setOnClickPendingIntent(
            R.id.widget_btn_prev,
            createActionPendingIntent(context, MelodistWidgetProvider.ACTION_PREV, REQ_CODE_PREV),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_play_pause,
            createActionPendingIntent(context, MelodistWidgetProvider.ACTION_TOGGLE_PLAY_PAUSE, REQ_CODE_PLAY_PAUSE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_next,
            createActionPendingIntent(context, MelodistWidgetProvider.ACTION_NEXT, REQ_CODE_NEXT),
        )

        manager.updateAppWidget(appWidgetIds, views)
    }

    private fun createActionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent =
            Intent(context, MelodistWidgetProvider::class.java).apply {
                this.action = action
            }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createRoundedCenterCropBitmap(
        source: Bitmap,
        targetSize: Int,
        cornerRadiusPx: Float,
    ): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(targetSize.toFloat() / source.width, targetSize.toFloat() / source.height)
        val dx = (targetSize - source.width * scale) / HALF_DIVISOR
        val dy = (targetSize - source.height * scale) / HALF_DIVISOR
        val matrix =
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
        shader.setLocalMatrix(matrix)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = shader
            }
        val rect = RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        return output
    }

    private fun pushOriginWidgetState(
        context: Context,
        song: Song?,
        coverBitmap: Bitmap?,
        theme: WidgetSystemTheme,
    ) {
        val coverBytes =
            if (coverBitmap != null && !coverBitmap.isRecycled) {
                java.io.ByteArrayOutputStream().use { stream ->
                    coverBitmap.compress(Bitmap.CompressFormat.PNG, COVER_COMPRESS_QUALITY, stream)
                    stream.toByteArray()
                }
            } else {
                null
            }

        val payload =
            Bundle().apply {
                putString("title", song?.name.orEmpty())
                putString("artist", song?.singer.orEmpty())
                putBoolean("is_playing", PlaybackManager.isPlaying.value)
                putInt("play_bg_color", theme.playBtnBgColor)
                putInt("play_icon_color", theme.playBtnIconColor)
                putByteArray("cover_bytes", coverBytes)
                putString("host_package", context.packageName)
            }
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val syncedViaProvider =
                try {
                    appContext.contentResolver.call(
                        Uri.parse("content://$ORIGIN_SYNC_AUTHORITY"),
                        ORIGIN_SYNC_METHOD,
                        null,
                        payload,
                    ) != null
                } catch (e: Exception) {
                    Log.w(TAG, "ContentProvider sync failed, falling back to broadcast", e)
                    false
                }
            if (!syncedViaProvider) {
                val intent =
                    Intent(ORIGIN_ACTION_SYNC_STATE).apply {
                        component = ComponentName(ORIGIN_WIDGET_PKG, ORIGIN_WIDGET_RECEIVER)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        putExtras(payload)
                    }
                try {
                    appContext.sendBroadcast(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync OriginOS widget state", e)
                }
            }
        }
    }

    private fun isOriginWidgetInstalled(context: Context): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    ORIGIN_WIDGET_PKG,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(ORIGIN_WIDGET_PKG, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
}
