package com.tme.vivowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.io.File

private data class OriginWidgetState(
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val playBgColor: Int? = null,
    val playIconColor: Int? = null,
    val coverBitmap: Bitmap? = null,
    val hostPackage: String? = null,
)

private data class OriginSystemColors(
    val cardBgColor: Int,
    val primaryTextColor: Int,
    val secondaryTextColor: Int,
    val playBgColor: Int,
    val playIconColor: Int,
)

/**
 * 独立伪装成 OriginOS 官方原子组件（com.tme.vivowidget.WidgetProvider）的入口，
 * 支持接收 OriginOS 系统的实时毛玻璃参数并通过跨进程广播与 Melodist 主应用联动。
 */
class WidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        requestHostSync(context)
        renderAll(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        requestHostSync(context)
        renderAll(context, appWidgetIds)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val materialIds = VivoMaterialHelper.handleMaterialUpdate(intent)
            if (materialIds != null) {
                if (getState(context).title.isBlank()) {
                    requestHostSync(context)
                }
                renderAll(context, materialIds)
                return
            }
        }
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_SYNC_STATE -> {
                intent.extras?.let { applySyncBundle(context, it) }
            }

            Intent.ACTION_CONFIGURATION_CHANGED -> {
                renderAll(context)
            }
        }
    }

    internal fun applySyncBundle(
        context: Context,
        bundle: Bundle,
    ) {
        val coverBytes = bundle.getByteArray(EXTRA_COVER_BYTES)
        val parsed = parseSyncState(bundle, coverBytes)
        cachedState = parsed
        saveState(context, parsed, coverBytes)
        renderAll(context)
    }

    private fun parseSyncState(
        bundle: Bundle,
        coverBytes: ByteArray?,
    ): OriginWidgetState {
        val decodedCover =
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
            } else {
                null
            }
        val playBg =
            if (bundle.containsKey(EXTRA_PLAY_BG_COLOR)) {
                bundle.getInt(EXTRA_PLAY_BG_COLOR, DEFAULT_PLAY_BG)
            } else {
                null
            }
        val playIcon =
            if (bundle.containsKey(EXTRA_PLAY_ICON_COLOR)) {
                bundle.getInt(EXTRA_PLAY_ICON_COLOR, DEFAULT_PLAY_ICON)
            } else {
                null
            }
        return OriginWidgetState(
            title = bundle.getString(EXTRA_TITLE).orEmpty(),
            artist = bundle.getString(EXTRA_ARTIST).orEmpty(),
            isPlaying = bundle.getBoolean(EXTRA_IS_PLAYING, false),
            playBgColor = playBg,
            playIconColor = playIcon,
            coverBitmap = decodedCover,
            hostPackage = bundle.getString(EXTRA_HOST_PACKAGE),
        )
    }

    private fun renderAll(
        context: Context,
        appWidgetIds: IntArray? = null,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, WidgetProvider::class.java)
        val targetIds = appWidgetIds ?: manager.getAppWidgetIds(provider)
        if (targetIds.isEmpty()) return

        val state = getState(context)
        val hostPkg = resolveHostPackage(context, state.hostPackage)
        for (widgetId in targetIds) {
            val materialBundle = VivoMaterialHelper.getMaterialBundle(widgetId)
            val views = buildRemoteViews(context, widgetId, state, hostPkg, materialBundle)
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun buildRemoteViews(
        context: Context,
        widgetId: Int,
        state: OriginWidgetState,
        hostPkg: String,
        materialBundle: Bundle?,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_melodist_player)
        val materialApplied =
            materialBundle != null &&
                VivoMaterialHelper.applyMaterialToRoot(views, R.id.widget_root, widgetId, materialBundle)

        val colors = resolveSystemColors(context, state, materialBundle, materialApplied)
        if (materialApplied) {
            views.setViewVisibility(R.id.widget_bg, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_bg, View.VISIBLE)
            views.setInt(R.id.widget_bg, "setColorFilter", colors.cardBgColor)
        }

        applyVisualState(context, views, state, colors)
        bindClickActions(context, views, hostPkg)
        return views
    }

    private fun applyVisualState(
        context: Context,
        views: RemoteViews,
        state: OriginWidgetState,
        colors: OriginSystemColors,
    ) {
        views.setInt(R.id.widget_btn_play_pause, "setColorFilter", colors.primaryTextColor)
        views.setInt(R.id.widget_btn_prev, "setColorFilter", colors.primaryTextColor)
        views.setInt(R.id.widget_btn_next, "setColorFilter", colors.primaryTextColor)
        views.setInt(R.id.widget_cover_placeholder, "setColorFilter", colors.secondaryTextColor)

        views.setTextViewText(
            R.id.widget_title,
            state.title.ifBlank { context.getString(R.string.widget_empty_title) },
        )
        views.setTextColor(R.id.widget_title, colors.primaryTextColor)

        views.setTextViewText(
            R.id.widget_artist,
            state.artist.ifBlank { context.getString(R.string.widget_empty_artist) },
        )
        views.setTextColor(R.id.widget_artist, colors.secondaryTextColor)

        val cover = state.coverBitmap
        if (cover != null && !cover.isRecycled) {
            views.setImageViewBitmap(R.id.widget_cover, cover)
            views.setViewVisibility(R.id.widget_cover, View.VISIBLE)
            views.setViewVisibility(R.id.widget_cover_placeholder, View.GONE)
        } else {
            views.setImageViewBitmap(R.id.widget_cover, null)
            views.setViewVisibility(R.id.widget_cover, View.GONE)
            views.setViewVisibility(R.id.widget_cover_placeholder, View.VISIBLE)
        }

        views.setImageViewResource(
            R.id.widget_btn_play_pause,
            if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
        )
    }

    private fun bindClickActions(
        context: Context,
        views: RemoteViews,
        hostPkg: String,
    ) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(hostPkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val openAppPendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_header, openAppPendingIntent)
        }

        views.setOnClickPendingIntent(
            R.id.widget_btn_prev,
            createControlPendingIntent(context, hostPkg, HOST_ACTION_PREV, REQ_PREV),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_play_pause,
            createControlPendingIntent(context, hostPkg, HOST_ACTION_TOGGLE_PLAY_PAUSE, REQ_PLAY_PAUSE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_next,
            createControlPendingIntent(context, hostPkg, HOST_ACTION_NEXT, REQ_NEXT),
        )
    }

    private fun resolveSystemColors(
        context: Context,
        state: OriginWidgetState,
        materialBundle: Bundle?,
        materialApplied: Boolean,
    ): OriginSystemColors {
        val isNight =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        if (materialApplied && materialBundle != null) {
            val darkSurface = VivoMaterialHelper.isDarkBlurSurface(materialBundle, isNight)
            return OriginSystemColors(
                cardBgColor = 0x00000000,
                primaryTextColor = if (darkSurface) BLUR_DARK_PRIMARY_TEXT else BLUR_LIGHT_PRIMARY_TEXT,
                secondaryTextColor = if (darkSurface) BLUR_DARK_SUB_TEXT else BLUR_LIGHT_SUB_TEXT,
                playBgColor = state.playBgColor ?: DEFAULT_PLAY_BG,
                playIconColor = state.playIconColor ?: DEFAULT_PLAY_ICON,
            )
        }

        val cardBgColor: Int
        val primaryTextColor: Int
        val secondaryTextColor: Int
        val fallbackPlayBg: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cardBgColor =
                context.getColor(
                    if (isNight) android.R.color.system_neutral1_900 else android.R.color.system_accent1_50,
                )
            primaryTextColor =
                context.getColor(
                    if (isNight) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900,
                )
            secondaryTextColor =
                context.getColor(
                    if (isNight) android.R.color.system_neutral2_300 else android.R.color.system_neutral2_600,
                )
            fallbackPlayBg =
                context.getColor(
                    if (isNight) android.R.color.system_accent1_400 else android.R.color.system_accent1_500,
                )
        } else {
            cardBgColor = if (isNight) FALLBACK_DARK_BG else FALLBACK_LIGHT_BG
            primaryTextColor = if (isNight) FALLBACK_DARK_TEXT else FALLBACK_LIGHT_TEXT
            secondaryTextColor = if (isNight) FALLBACK_DARK_SUBTEXT else FALLBACK_LIGHT_SUBTEXT
            fallbackPlayBg = DEFAULT_PLAY_BG
        }

        return OriginSystemColors(
            cardBgColor = cardBgColor,
            primaryTextColor = primaryTextColor,
            secondaryTextColor = secondaryTextColor,
            playBgColor = state.playBgColor ?: fallbackPlayBg,
            playIconColor = state.playIconColor ?: DEFAULT_PLAY_ICON,
        )
    }

    private fun createControlPendingIntent(
        context: Context,
        hostPkg: String,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent =
            Intent(action).apply {
                component = ComponentName(hostPkg, HOST_RECEIVER_CLASS)
            }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestHostSync(context: Context) {
        val hostPkg = resolveHostPackage(context, getState(context).hostPackage)
        val syncIntent =
            Intent(HOST_ACTION_REQUEST_SYNC).apply {
                component = ComponentName(hostPkg, HOST_RECEIVER_CLASS)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        try {
            context.sendBroadcast(syncIntent)
        } catch (_: Exception) {
            // Host app may not be installed yet.
        }
    }

    private fun getState(context: Context): OriginWidgetState {
        cachedState?.let { return it }
        val loaded = loadState(context)
        cachedState = loaded
        return loaded
    }

    private fun saveState(
        context: Context,
        state: OriginWidgetState,
        coverBytes: ByteArray?,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(EXTRA_TITLE, state.title)
            putString(EXTRA_ARTIST, state.artist)
            putBoolean(EXTRA_IS_PLAYING, state.isPlaying)
            if (state.playBgColor != null) putInt(EXTRA_PLAY_BG_COLOR, state.playBgColor) else remove(EXTRA_PLAY_BG_COLOR)
            if (state.playIconColor != null) {
                putInt(EXTRA_PLAY_ICON_COLOR, state.playIconColor)
            } else {
                remove(EXTRA_PLAY_ICON_COLOR)
            }
            if (state.hostPackage != null) putString(EXTRA_HOST_PACKAGE, state.hostPackage) else remove(EXTRA_HOST_PACKAGE)
            apply()
        }
        val coverFile = File(context.filesDir, COVER_CACHE_FILE)
        try {
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                coverFile.writeBytes(coverBytes)
            } else if (coverFile.exists()) {
                coverFile.delete()
            }
        } catch (_: Exception) {
            // Ignore disk cache write errors.
        }
    }

    private fun loadState(context: Context): OriginWidgetState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val coverFile = File(context.filesDir, COVER_CACHE_FILE)
        val cover =
            if (coverFile.exists()) {
                try {
                    BitmapFactory.decodeFile(coverFile.absolutePath)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        return OriginWidgetState(
            title = prefs.getString(EXTRA_TITLE, "").orEmpty(),
            artist = prefs.getString(EXTRA_ARTIST, "").orEmpty(),
            isPlaying = prefs.getBoolean(EXTRA_IS_PLAYING, false),
            playBgColor =
                if (prefs.contains(EXTRA_PLAY_BG_COLOR)) prefs.getInt(EXTRA_PLAY_BG_COLOR, DEFAULT_PLAY_BG) else null,
            playIconColor =
                if (prefs.contains(EXTRA_PLAY_ICON_COLOR)) {
                    prefs.getInt(EXTRA_PLAY_ICON_COLOR, DEFAULT_PLAY_ICON)
                } else {
                    null
                },
            coverBitmap = cover,
            hostPackage = prefs.getString(EXTRA_HOST_PACKAGE, null),
        )
    }

    private fun resolveHostPackage(
        context: Context,
        preferred: String?,
    ): String =
        when {
            !preferred.isNullOrBlank() && isPackageInstalled(context, preferred) -> preferred
            isPackageInstalled(context, PKG_ORIGIN_OS) -> PKG_ORIGIN_OS
            else -> PKG_STANDARD
        }

    private fun isPackageInstalled(
        context: Context,
        packageName: String,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }

    companion object {
        const val ACTION_SYNC_STATE = "org.melodist.widget.ACTION_SYNC_STATE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_PLAY_BG_COLOR = "play_bg_color"
        const val EXTRA_PLAY_ICON_COLOR = "play_icon_color"
        const val EXTRA_COVER_BYTES = "cover_bytes"
        const val EXTRA_HOST_PACKAGE = "host_package"

        private const val PKG_ORIGIN_OS = "com.tencent.qqmusic"
        private const val PKG_STANDARD = "org.melodist.mobile"
        private const val HOST_RECEIVER_CLASS = "org.melodist.mobile.widget.MelodistWidgetProvider"
        private const val HOST_ACTION_TOGGLE_PLAY_PAUSE =
            "org.melodist.mobile.widget.ACTION_TOGGLE_PLAY_PAUSE"
        private const val HOST_ACTION_PREV = "org.melodist.mobile.widget.ACTION_PREV"
        private const val HOST_ACTION_NEXT = "org.melodist.mobile.widget.ACTION_NEXT"
        private const val HOST_ACTION_REQUEST_SYNC = "org.melodist.mobile.widget.ACTION_REQUEST_SYNC"

        private const val REQ_PREV = 101
        private const val REQ_PLAY_PAUSE = 102
        private const val REQ_NEXT = 103

        private const val BLUR_DARK_PRIMARY_TEXT = 0xFFFFFFFF.toInt()
        private const val BLUR_DARK_SUB_TEXT = 0xB3FFFFFF.toInt()
        private const val BLUR_LIGHT_PRIMARY_TEXT = 0xFF1C1B24.toInt()
        private const val BLUR_LIGHT_SUB_TEXT = 0xFF555260.toInt()

        private const val DEFAULT_PLAY_BG = 0xFFDE5D83.toInt()
        private const val DEFAULT_PLAY_ICON = 0xFFFFFFFF.toInt()
        private const val FALLBACK_DARK_BG = 0xFF1E1F26.toInt()
        private const val FALLBACK_LIGHT_BG = 0xFFEDEAF8.toInt()
        private const val FALLBACK_DARK_TEXT = 0xFFF4F3FA.toInt()
        private const val FALLBACK_LIGHT_TEXT = 0xFF1C1B24.toInt()
        private const val FALLBACK_DARK_SUBTEXT = 0xFFB0ADC0.toInt()
        private const val FALLBACK_LIGHT_SUBTEXT = 0xFF605D6C.toInt()

        private const val PREFS_NAME = "melodist_origin_widget_state"
        private const val COVER_CACHE_FILE = "widget_cover.png"

        @Volatile
        private var cachedState: OriginWidgetState? = null

        fun syncFromBundle(
            context: Context,
            bundle: Bundle,
        ) {
            WidgetProvider().applySyncBundle(context.applicationContext, bundle)
        }
    }
}
