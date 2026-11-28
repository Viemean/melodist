package com.tme.vivowidget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 适配 OriginOS 桌面与智慧桌面（负一屏）下发的 `material_bundle` 系统实时毛玻璃参数。
 */
internal object VivoMaterialHelper {
    private const val WIDGET_TYPE_RADIUS = 8
    private const val WIDGET_TYPE_MATERIAL = 10
    private const val EXTRA_WIDGET_TYPE = "widget_type"
    private const val EXTRA_APP_WIDGET_IDS = "appWidgetIds"
    private const val EXTRA_MATERIAL_BUNDLE = "material_bundle"
    private const val EXTRA_RADIUS_CHANGED = "RADIUS_CHANGED"

    private const val STATE_HOST = "state_host"
    private const val STATE_BLUR_STYLE = "state_blur_style"
    private const val STATE_NIGHT_MODE = "state_night_mode"
    private const val STATE_HOST_HIBOARD = 2

    private const val SET_MATERIAL = "setMaterial"
    private const val CLEAR_MATERIAL = "clearMaterial"
    private const val SET_MATERIAL_CORNER_RADIUS = "setMaterialCornerRadius"
    private const val SET_MATERIAL_CUSTOMIZED = "setMaterialCustomized"
    private const val ENABLE_MATERIAL_G2STYLE = "enableMaterialG2Style"
    private const val SET_MATERIAL_FORCE_UPDATE_BG = "setMaterialForceUpdateBg"
    private const val SET_MATERIAL_GROUP_ID = "setMaterialGroupID"
    private const val CHOOSE_MATERIAL_BLUR_ITERATION = "chooseMaterialBlurIteration"
    private const val SET_GLASS_MATERIAL = "setGlassMaterial"
    private const val SET_GLASS_EFFECT_LIGHT = "setGlassEffectLight"
    private const val SET_BLUR_DYNAMIC_COLOR_PARAMS = "setBlurDynamicColorParams"
    private const val MATERIAL_EFFECT_LEVEL = "materialEffectLevel"
    private const val PARAM_MASK_OPACITY3 = "maskOpacity3"
    private const val VIVO_WIDGET_MATERIAL_LAUNCHER_ALPHA = "vivo.widget.material.launcher.alpha"

    private const val DEFAULT_CORNER_PX = 48.0f
    private const val BLUR_STYLE_DAY_1 = 1
    private const val BLUR_STYLE_DAY_2 = 2
    private const val BLUR_STYLE_DAY_7 = 7
    private const val BLUR_STYLE_DAY_9 = 9
    private const val BLUR_STYLE_DAY_11 = 11
    private const val BLUR_STYLE_DAY_13 = 13

    private val widgetBundles = ConcurrentHashMap<Int, Bundle>()
    private val widgetRadii = ConcurrentHashMap<Int, Float>()

    @Volatile
    private var lastRadiusPx: Float = DEFAULT_CORNER_PX

    fun handleMaterialUpdate(intent: Intent): IntArray? {
        val widgetType = intent.getIntExtra(EXTRA_WIDGET_TYPE, -1)
        if (widgetType != WIDGET_TYPE_MATERIAL && widgetType != WIDGET_TYPE_RADIUS) {
            return null
        }
        val extras = intent.extras ?: return null
        val appWidgetIds = extras.getIntArray(EXTRA_APP_WIDGET_IDS) ?: return null

        val radiusJson = extras.getString(EXTRA_RADIUS_CHANGED)
        if (!radiusJson.isNullOrBlank()) {
            parseOuterCornerRadii(radiusJson, appWidgetIds)
        }

        if (widgetType == WIDGET_TYPE_MATERIAL) {
            val bundle = extras.getBundle(EXTRA_MATERIAL_BUNDLE)
            if (bundle != null) {
                for (id in appWidgetIds) {
                    widgetBundles[id] = bundle
                }
            }
        }
        return appWidgetIds
    }

    fun getMaterialBundle(widgetId: Int): Bundle? = widgetBundles[widgetId]

    fun applyMaterialToRoot(
        views: RemoteViews,
        rootViewId: Int,
        widgetId: Int,
        bundle: Bundle,
    ): Boolean {
        val setMaterialBundle = bundle.getBundle(SET_MATERIAL) ?: return false

        views.setBundle(rootViewId, CLEAR_MATERIAL, Bundle())
        views.setBundle(rootViewId, SET_MATERIAL, setMaterialBundle)

        if (bundle.containsKey(SET_MATERIAL_FORCE_UPDATE_BG)) {
            views.setBoolean(
                rootViewId,
                SET_MATERIAL_FORCE_UPDATE_BG,
                bundle.getBoolean(SET_MATERIAL_FORCE_UPDATE_BG),
            )
        }
        if (bundle.containsKey(SET_MATERIAL_GROUP_ID)) {
            views.setInt(rootViewId, SET_MATERIAL_GROUP_ID, bundle.getInt(SET_MATERIAL_GROUP_ID))
        }
        if (bundle.containsKey(ENABLE_MATERIAL_G2STYLE)) {
            views.setBoolean(
                rootViewId,
                ENABLE_MATERIAL_G2STYLE,
                bundle.getBoolean(ENABLE_MATERIAL_G2STYLE),
            )
        }

        val customized = bundle.getBundle(SET_MATERIAL_CUSTOMIZED)
        if (customized != null) {
            views.setBundle(rootViewId, SET_MATERIAL_CUSTOMIZED, Bundle(customized))
        }

        if (bundle.containsKey(SET_BLUR_DYNAMIC_COLOR_PARAMS)) {
            views.setInt(
                rootViewId,
                SET_BLUR_DYNAMIC_COLOR_PARAMS,
                bundle.getInt(SET_BLUR_DYNAMIC_COLOR_PARAMS),
            )
        }
        if (bundle.containsKey(CHOOSE_MATERIAL_BLUR_ITERATION)) {
            views.setInt(
                rootViewId,
                CHOOSE_MATERIAL_BLUR_ITERATION,
                bundle.getInt(CHOOSE_MATERIAL_BLUR_ITERATION),
            )
        }

        val radius = widgetRadii[widgetId] ?: lastRadiusPx
        views.setFloat(rootViewId, SET_MATERIAL_CORNER_RADIUS, radius)

        if (bundle.containsKey(SET_GLASS_MATERIAL)) {
            val level = bundle.getInt(MATERIAL_EFFECT_LEVEL, 2)
            if (level == 0 || level == 1) {
                views.setBundle(rootViewId, SET_GLASS_MATERIAL, bundle.getBundle(SET_GLASS_MATERIAL))
                views.setBundle(
                    rootViewId,
                    SET_GLASS_EFFECT_LIGHT,
                    bundle.getBundle(SET_GLASS_EFFECT_LIGHT),
                )
            }
        }
        return true
    }

    fun isDarkBlurSurface(
        bundle: Bundle,
        isSystemNight: Boolean,
    ): Boolean {
        if (bundle.getBoolean(STATE_NIGHT_MODE, false)) {
            return true
        }
        return when (bundle.getInt(STATE_BLUR_STYLE, -1)) {
            BLUR_STYLE_DAY_1,
            BLUR_STYLE_DAY_2,
            BLUR_STYLE_DAY_7,
            BLUR_STYLE_DAY_9,
            BLUR_STYLE_DAY_11,
            BLUR_STYLE_DAY_13,
            -> false

            -1 -> isSystemNight

            else -> if (bundle.getInt(STATE_HOST) == STATE_HOST_HIBOARD) isSystemNight else true
        }
    }

    private fun parseOuterCornerRadii(
        raw: String,
        appWidgetIds: IntArray,
    ) {
        try {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("[")) {
                val single = trimmed.toFloatOrNull()?.takeIf { it > 0f } ?: return
                lastRadiusPx = single
                for (id in appWidgetIds) {
                    widgetRadii[id] = single
                }
                return
            }
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val value = obj.optDouble("outerCornerValue", -1.0).toFloat()
                if (value <= 0f) continue
                lastRadiusPx = value
                val id = obj.optInt("id", -1)
                if (id > 0) {
                    widgetRadii[id] = value
                } else {
                    for (targetId in appWidgetIds) {
                        widgetRadii[targetId] = value
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore malformed corner radius payload.
        }
    }
}
