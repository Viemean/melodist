package org.melodist.mobile.util

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.melodist.data.RawCoverHelper
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.io.File

/**
 * 移动端封面缓存与网络策略解析器
 */
object MobileCoverCacheResolver {
    private const val MIN_VALID_COVER_SIZE = 512L

    private fun isLocalCoverValid(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.substringBefore('?')
        val path = if (clean.startsWith("file://")) clean.removePrefix("file://") else clean
        val file = File(path)
        return file.exists() && file.length() > MIN_VALID_COVER_SIZE
    }

    /**
     * 探测指定歌曲是否在本地具备无损原画大图（本地文件已存在或已被 Coil 写入磁盘缓存）
     */
    fun hasRawCoverCache(
        context: Context,
        song: Song?,
    ): Boolean {
        if (song == null) return false

        // 1. 本地音频或 WebDAV 本地大图文件探测
        if (isLocalCoverValid(song.rawCoverUrl)) return true
        val matchingRaw = RawCoverHelper.findMatchingRawCoverUrl(song.coverUrl)
        if (!matchingRaw.isNullOrBlank() && isLocalCoverValid(matchingRaw)) return true

        // 2. 在线音乐无损原图在 Coil 磁盘缓存中是否存在
        val rawUrl = song.rawCoverUrlOnly
        if (rawUrl != null && (rawUrl.startsWith("http://") || rawUrl.startsWith("https://"))) {
            val snapshot = SingletonImageLoader.get(context).diskCache?.openSnapshot(rawUrl)
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
    fun resolveCandidates(song: Song?): List<String> {
        if (song == null) return emptyList()
        val isCellular = PlaybackManager.isCellularNetwork()
        return song.resolvePlayerCoverCandidates(isCellular = isCellular)
    }

    /**
     * 兼容保留重载
     */
    @Deprecated("使用单参数 resolveCandidates(song)", ReplaceWith("resolveCandidates(song)"))
    fun resolveCandidates(
        @Suppress("UNUSED_PARAMETER") context: Context,
        song: Song?,
    ): List<String> = resolveCandidates(song)

    /**
     * 当在 WiFi 环境下播放该音乐时，若原图尚未写入磁盘缓存，自动在后台加载并升级原图缓存
     */
    fun upgradeRawCoverOnWifiAsync(
        context: Context,
        song: Song?,
        scope: CoroutineScope? = null,
        onUpgraded: (() -> Unit)? = null,
    ) {
        val rawUrl = song?.rawCoverUrlOnly
        val shouldSkip =
            song == null ||
                PlaybackManager.isCellularNetwork() ||
                rawUrl.isNullOrBlank() ||
                (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))
        if (shouldSkip) return

        val imageLoader = SingletonImageLoader.get(context)
        val snapshot = imageLoader.diskCache?.openSnapshot(rawUrl.orEmpty())
        if (snapshot != null) {
            snapshot.close()
            return
        }

        val action = {
            val request =
                ImageRequest
                    .Builder(context)
                    .data(rawUrl)
                    .listener(onSuccess = { _, _ -> onUpgraded?.invoke() })
                    .build()
            imageLoader.enqueue(request)
        }

        if (scope != null) {
            scope.launch(Dispatchers.IO) { action() }
        } else {
            action()
        }
    }
}
