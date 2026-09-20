package org.melodist.mobile.util

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.io.File

/**
 * 移动端封面缓存与网络策略解析器
 */
object MobileCoverCacheResolver {
    /**
     * 探测指定歌曲是否在本地具备无损原画大图（本地文件已存在或已被 Coil 写入磁盘缓存）
     */
    fun hasRawCoverCache(context: Context, song: Song?): Boolean {
        if (song == null) return false

        // 1. 本地音频或 WebDAV 本地大图文件探测
        if (song.rawCoverUrl.isNotBlank()) {
            val clean = song.rawCoverUrl.substringBefore('?')
            val path = if (clean.startsWith("file://")) clean.removePrefix("file://") else clean
            if (File(path).exists() && File(path).length() > 512L) return true
        }
        val matchingRaw = org.melodist.data.RawCoverHelper.findMatchingRawCoverUrl(song.coverUrl)
        if (!matchingRaw.isNullOrBlank()) {
            val path = matchingRaw.removePrefix("file://")
            if (File(path).exists() && File(path).length() > 512L) return true
        }

        // 2. 在线音乐无损原图在 Coil 磁盘缓存中是否存在
        val rawUrl = song.rawCoverUrlOnly ?: return false
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            val imageLoader = SingletonImageLoader.get(context)
            val diskCache = imageLoader.diskCache ?: return false
            val snapshot = diskCache.openSnapshot(rawUrl)
            if (snapshot != null) {
                snapshot.close()
                return true
            }
        }
        return false
    }

    /**
     * 根据当前移动端网络环境与原图缓存状态，计算用于播放界面的候选封面列表：
     * - 移动网络下无原图缓存：最大加载 1200 分辨率，回退 800 -> 500；
     * - 移动网络下已有原图缓存：直接加载原图；
     * - WiFi 或有线网络下：首选原图直出。
     */
    fun resolveCandidates(context: Context, song: Song?): List<String> {
        if (song == null) return emptyList()
        val isCellular = PlaybackManager.isCellularNetwork()
        return song.resolvePlayerCoverCandidates(isCellular = isCellular)
    }

    /**
     * 当在 WiFi 环境下播放该音乐时，若原图尚未写入磁盘缓存，自动在后台加载并升级原图缓存
     */
    fun upgradeRawCoverOnWifiAsync(
        context: Context,
        song: Song?,
        scope: CoroutineScope? = null,
        onUpgraded: (() -> Unit)? = null,
    ) {
        if (song == null) return
        val isCellular = PlaybackManager.isCellularNetwork()
        if (isCellular) return // 蜂窝移动网络下绝不静默下载原图

        val rawUrl = song.rawCoverUrlOnly ?: return
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return

        val imageLoader = SingletonImageLoader.get(context)
        val diskCache = imageLoader.diskCache
        val snapshot = diskCache?.openSnapshot(rawUrl)
        if (snapshot != null) {
            snapshot.close()
            return // 本地磁盘已有原图缓存，无需重复下载
        }

        val action = {
            val request =
                ImageRequest
                    .Builder(context)
                    .data(rawUrl)
                    .listener(
                        onSuccess = { _, _ ->
                            onUpgraded?.invoke()
                        },
                    ).build()
            imageLoader.enqueue(request)
        }

        if (scope != null) {
            scope.launch(Dispatchers.IO) { action() }
        } else {
            action()
        }
    }
}
