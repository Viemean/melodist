package org.melodist.playback

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

object PlaybackSourceResolver {
    fun isTelevision(context: Context?): Boolean {
        val ctx = context ?: return false
        val pkg = ctx.packageName.orEmpty()
        if (pkg == "org.melodist.tv" || pkg.endsWith(".tv")) {
            return true
        }
        val pm = ctx.packageManager
        if (pm != null) {
            try {
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature("android.hardware.type.television")
                ) {
                    return true
                }
            } catch (e: Exception) {
                Log.w("PlaybackSourceResolver", "Operation failed", e)
            }
        }
        try {
            val uiModeManager = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
                return true
            }
        } catch (e: Exception) {
            Log.w("PlaybackSourceResolver", "Operation failed", e)
        }
        return false
    }

    fun isCellularNetwork(context: Context?): Boolean {
        val ctx = context ?: return false
        if (isTelevision(ctx)) {
            return false
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun isLocalOrWebDavSong(song: Song?): Boolean {
        if (song == null) return false
        return song.songMid.startsWith("webdav_") ||
            song.songMid.startsWith("local_") ||
            !song.localFilePath.isNullOrBlank() ||
            song.isWebDav ||
            song.isLocal
    }

    /**
     * 判断歌曲是否为纯本地导入或 WebDAV 音乐（无线上音源可用）
     * 仅当歌曲为纯本地或 WebDAV 时才限制切换音质，已下载的在线歌曲不在此列
     */
    fun isPureLocalOrWebDav(song: Song?): Boolean {
        if (song == null) return false
        if (song.songMid.isBlank()) return true
        return song.songMid.startsWith("webdav_") ||
            song.songMid.startsWith("local_") ||
            song.songMid.startsWith("pc_local_") ||
            song.isWebDav ||
            song.isLocal
    }

    fun getAudioQualityRank(tier: AudioQualityTier): Int =
        when (tier) {
            AudioQualityTier.Master -> 9
            AudioQualityTier.Dolby -> 8
            AudioQualityTier.DtsX -> 7
            AudioQualityTier.Atmos -> 6
            AudioQualityTier.Premium -> 5
            AudioQualityTier.HiRes -> 4
            AudioQualityTier.SQ -> 3
            AudioQualityTier.HQ -> 2
            AudioQualityTier.Standard -> 1
        }

    fun clampCellularTier(
        requestedTier: AudioQualityTier,
        song: Song?,
        context: Context?,
        cellularLimit: AudioQualityTier,
    ): AudioQualityTier {
        if (isTelevision(context)) {
            return requestedTier
        }
        if (isLocalOrWebDavSong(song)) {
            return requestedTier
        }
        if (!isCellularNetwork(context)) {
            return requestedTier
        }
        val mid = song?.songMid.orEmpty()
        val downloadedTier =
            if (mid.isNotBlank()) {
                org.melodist.data.download.DownloadManager
                    .getCompletedDownload(mid)
                    ?.second
            } else {
                null
            }

        val cachedTier =
            if (mid.isNotBlank()) {
                MelodistCacheManager.getBestFullyCachedTier(mid)
            } else {
                null
            }

        val localTier = downloadedTier ?: cachedTier
        if (localTier != null) {
            val localRank = getAudioQualityRank(localTier)
            val reqRank = getAudioQualityRank(requestedTier)
            if (localRank >= reqRank) {
                return requestedTier
            } else if (localRank > getAudioQualityRank(cellularLimit)) {
                return localTier
            }
        }
        val reqRank = getAudioQualityRank(requestedTier)
        val limitRank = getAudioQualityRank(cellularLimit)
        return if (reqRank > limitRank) cellularLimit else requestedTier
    }

    fun clampCodecSupportedTier(
        tier: AudioQualityTier,
        song: Song?,
    ): AudioQualityTier {
        if (isLocalOrWebDavSong(song)) {
            return tier
        }
        if (!DeviceAudioCapability.isTierSupported(tier)) {
            return AudioQualityTier.HQ
        }
        return tier
    }

    fun getFallbackTier(current: AudioQualityTier): AudioQualityTier? =
        when (current) {
            AudioQualityTier.Master -> AudioQualityTier.Dolby
            AudioQualityTier.Dolby -> AudioQualityTier.DtsX
            AudioQualityTier.DtsX -> AudioQualityTier.Atmos
            AudioQualityTier.Atmos -> AudioQualityTier.Premium
            AudioQualityTier.Premium -> AudioQualityTier.HiRes
            AudioQualityTier.HiRes -> AudioQualityTier.SQ
            AudioQualityTier.SQ -> AudioQualityTier.HQ
            AudioQualityTier.HQ -> AudioQualityTier.Standard
            AudioQualityTier.Standard -> null
        }

    fun shouldTriggerPrefetch(
        durationMs: Long,
        positionMs: Long,
    ): Boolean {
        if (durationMs <= 20_000L) return false
        val remainingMs = durationMs - positionMs
        return remainingMs <= 20_000L || (positionMs.toDouble() / durationMs) >= 0.85
    }
}
