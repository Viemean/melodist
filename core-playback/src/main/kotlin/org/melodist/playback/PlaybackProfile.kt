package org.melodist.playback

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

data class PlaybackProfile(
    val isTvDevice: Boolean,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val skipThresholdSec: Int,
    val skipThresholdRatio: Float,
    val maxCacheQuotaBytes: Long,
    val allowMasterDiskCache: Boolean,
) {
    fun getTrialThresholdMs(durationMs: Long): Long {
        val byTime = skipThresholdSec * 1000L
        val byRatio = if (durationMs > 0L) (durationMs * skipThresholdRatio).toLong() else byTime
        return minOf(byTime, byRatio)
    }

    companion object {
        val TV =
            PlaybackProfile(
                isTvDevice = true,
                minBufferMs = 8_000,
                maxBufferMs = 15_000,
                skipThresholdSec = 15,
                skipThresholdRatio = 0.20f,
                maxCacheQuotaBytes = 2L * 1024 * 1024 * 1024, // 2GB
                allowMasterDiskCache = false,
            )

        val Mobile =
            PlaybackProfile(
                isTvDevice = false,
                minBufferMs = 15_000,
                maxBufferMs = 30_000,
                skipThresholdSec = 10,
                skipThresholdRatio = 0.15f,
                maxCacheQuotaBytes = 6L * 1024 * 1024 * 1024, // 6GB
                allowMasterDiskCache = true,
            )

        fun detect(context: Context): PlaybackProfile =
            try {
                val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                val isTvUi = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
                val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                if (isTvUi || hasLeanback) TV else Mobile
            } catch (_: Exception) {
                Mobile
            }
    }
}
