package org.melodist.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.QualityResult
import org.melodist.api.getPlayUrl
import org.melodist.data.WebDavManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

sealed class PlaybackTargetResult {
    data class LocalFile(
        val mediaItem: MediaItem,
        val actualTier: AudioQualityTier,
        val localFile: File,
    ) : PlaybackTargetResult()

    data class LocalStream(
        val mediaSource: MediaSource,
        val actualTier: AudioQualityTier,
    ) : PlaybackTargetResult()

    data class WebDavLocalCache(
        val mediaItem: MediaItem,
        val actualTier: AudioQualityTier,
        val localFile: File,
        val serverId: String,
        val relativeHref: String,
    ) : PlaybackTargetResult()

    data class WebDavStream(
        val mediaSource: MediaSource,
        val actualTier: AudioQualityTier,
        val serverId: String,
        val relativeHref: String,
    ) : PlaybackTargetResult()

    data class Online(
        val mediaSource: MediaSource,
        val actualTier: AudioQualityTier,
        val isDirectCached: Boolean,
        val fileCacheFraction: Float,
    ) : PlaybackTargetResult()

    data class Failure(
        val message: String,
        val allowRetry: Boolean = true,
    ) : PlaybackTargetResult()
}

object PlaybackMediaLoader {
    private const val TAG = "PlaybackMediaLoader"

    fun buildMediaItem(
        uri: Uri?,
        song: Song,
        tier: AudioQualityTier? = null,
        metadataBuilder: ((Song) -> androidx.media3.common.MediaMetadata)? = null,
    ): MediaItem {
        val builder =
            MediaItem
                .Builder()
                .setMediaId(song.songMid)
                .setCustomCacheKey(MelodistCacheManager.getCacheKey(song.songMid, tier))
        if (uri != null) {
            builder.setUri(uri)
        }
        if (metadataBuilder != null) {
            builder.setMediaMetadata(metadataBuilder(song))
        }
        return builder.build()
    }

    suspend fun resolveTarget(
        context: Context? = null,
        song: Song,
        targetTier: AudioQualityTier,
        apiService: MusicApiService,
        isSongFavorite: (String) -> Boolean,
        metadataBuilder: ((Song) -> androidx.media3.common.MediaMetadata)? = null,
        prefetchedUrlInfo: Pair<String, QualityResult>? = null,
    ): PlaybackTargetResult =
        withContext(Dispatchers.IO) {
            // 1. 检查纯本地文件及下载管理器中已下载文件
            val isPureLocal = !song.localFilePath.isNullOrBlank() && !song.songMid.startsWith("webdav_")
            if (isPureLocal) {
                val directFile = File(song.localFilePath!!)
                if (directFile.exists() && directFile.isFile) {
                    val mediaItem = buildMediaItem(Uri.fromFile(directFile), song, song.currentTier, metadataBuilder)
                    return@withContext PlaybackTargetResult.LocalFile(
                        mediaItem = mediaItem,
                        actualTier = song.currentTier,
                        localFile = directFile,
                    )
                }
            }
            val completedDownload =
                org.melodist.data.download.DownloadManager
                    .getCompletedDownload(song.songMid)
            if (completedDownload != null) {
                val (downloadFile, downloadTier) = completedDownload
                val mediaItem = buildMediaItem(Uri.fromFile(downloadFile), song, downloadTier, metadataBuilder)
                return@withContext PlaybackTargetResult.LocalFile(
                    mediaItem = mediaItem,
                    actualTier = downloadTier,
                    localFile = downloadFile,
                )
            }

            // 2. 检查本地音乐远程流式播放（局域网代理中转）
            val isLocalStream =
                (song.isLocal || song.songMid.startsWith("local_") || song.songMid.startsWith("pc_local_") || song.mediaMid.contains("/stream/local")) &&
                    (song.mediaMid.startsWith("http://") || song.mediaMid.startsWith("https://"))
            if (isLocalStream) {
                val baseHttpFactory =
                    DefaultHttpDataSource
                        .Factory()
                        .setUserAgent("MelodistTV/1.0 ConnectStream")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(30_000)
                        .setReadTimeoutMs(30_000)
                if (context != null) {
                    val dataSourceFactory = DefaultDataSource.Factory(context, baseHttpFactory)
                    val mediaSource =
                        ProgressiveMediaSource
                            .Factory(dataSourceFactory)
                            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                            .createMediaSource(buildMediaItem(Uri.parse(song.mediaMid), song, song.currentTier, metadataBuilder))
                    return@withContext PlaybackTargetResult.LocalStream(
                        mediaSource = mediaSource,
                        actualTier = song.currentTier,
                    )
                }
            }

            // 本地歌曲既无本地文件也无可用流代理
            if (song.isLocal || song.songMid.startsWith("local_") || song.songMid.startsWith("pc_local_") || song.mediaMid.contains("/stream/local")) {
                return@withContext PlaybackTargetResult.Failure("无法找到本地音频文件或流代理", allowRetry = false)
            }

            // 3. WebDAV 媒体处理
            if (song.songMid.startsWith("webdav_")) {
                val server = WebDavManager.getActiveServer()
                val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
                val localFile =
                    if (server != null && relativeHref.isNotBlank()) {
                        WebDavManager.getLocalCacheFile(server.id, relativeHref)
                    } else {
                        null
                    }

                if (localFile != null && localFile.exists() && localFile.length() > 0L) {
                    val mediaItem = buildMediaItem(Uri.fromFile(localFile), song, AudioQualityTier.SQ, metadataBuilder)
                    return@withContext PlaybackTargetResult.WebDavLocalCache(
                        mediaItem = mediaItem,
                        actualTier = AudioQualityTier.SQ,
                        localFile = localFile,
                        serverId = server?.id.orEmpty(),
                        relativeHref = relativeHref,
                    )
                } else if (server != null && relativeHref.isNotBlank()) {
                    val ctx = context ?: return@withContext PlaybackTargetResult.Failure("缺少 Context 上下文")
                    val (streamUrl, authHeader) = WebDavManager.resolvePlaybackUrl(server, relativeHref)
                    val baseHttpFactory =
                        DefaultHttpDataSource
                            .Factory()
                            .setUserAgent("MelodistTV/1.0 ExoPlayer")
                            .setAllowCrossProtocolRedirects(true)
                            .setConnectTimeoutMs(30_000)
                            .setReadTimeoutMs(30_000)
                    if (!authHeader.isNullOrBlank()) {
                        baseHttpFactory.setDefaultRequestProperties(mapOf("Authorization" to authHeader))
                    }
                    val dataSourceFactory = DefaultDataSource.Factory(ctx, baseHttpFactory)
                    val cachedDataSourceFactory = MelodistCacheManager.buildCacheDataSourceFactory(ctx, dataSourceFactory)
                    val mediaSource =
                        ProgressiveMediaSource
                            .Factory(cachedDataSourceFactory)
                            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                            .createMediaSource(buildMediaItem(Uri.parse(streamUrl), song, AudioQualityTier.SQ, metadataBuilder))
                    return@withContext PlaybackTargetResult.WebDavStream(
                        mediaSource = mediaSource,
                        actualTier = AudioQualityTier.SQ,
                        serverId = server.id,
                        relativeHref = relativeHref,
                    )
                } else {
                    return@withContext PlaybackTargetResult.Failure("无法加载本地或 WebDAV 音频文件")
                }
            }

            // 4. 线上歌曲流式处理
            val higherStereoTier = MelodistCacheManager.findHigherOrEqualStereoCachedTier(song.songMid, targetTier)
            val playUrlInfo =
                if (higherStereoTier != null) {
                    val dummyUri = "https://cache.melodist.internal/${song.songMid}?tier=${higherStereoTier.name}"
                    QualityResult(
                        url = dummyUri,
                        tier = higherStereoTier,
                        badge = AudioQualityTier.getBadge(higherStereoTier),
                    )
                } else {
                    val cachedInfo =
                        if (prefetchedUrlInfo?.first == song.songMid) {
                            prefetchedUrlInfo.second
                        } else {
                            null
                        }
                    if (cachedInfo != null) {
                        cachedInfo
                    } else {
                        try {
                            apiService.getPlayUrl(song.songMid, mediaMid = song.mediaMid, preferredTier = targetTier)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch play URL for ${song.name}", e)
                            null
                        }
                    }
                }

            if (playUrlInfo != null && !playUrlInfo.url.isNullOrBlank()) {
                val ctx = context ?: return@withContext PlaybackTargetResult.Failure("缺少 Context 上下文")
                val isFav = isSongFavorite(song.songMid)
                val isCached = higherStereoTier != null || MelodistCacheManager.isSongTierCached(song.songMid, playUrlInfo.tier)
                val targetCacheKey = MelodistCacheManager.getCacheKey(song.songMid, playUrlInfo.tier)
                val isPrecached = MelodistCacheManager.isKeyCached(targetCacheKey)
                val shouldCache = MelodistCacheManager.shouldCacheSong(song.songMid, isFav, playUrlInfo.tier)
                val fileCacheFraction = if (isCached) 1f else MelodistCacheManager.getSongFileCacheProgress(song.songMid, playUrlInfo.tier).fraction

                val httpFactory =
                    DefaultHttpDataSource
                        .Factory()
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .setAllowCrossProtocolRedirects(true)
                        .setConnectTimeoutMs(30_000)
                        .setReadTimeoutMs(30_000)
                val defaultFactory = DefaultDataSource.Factory(ctx, httpFactory)
                val dsFactory =
                    if (shouldCache || isCached || isPrecached || !MelodistCacheManager.currentProfile.isTvDevice) {
                        MelodistCacheManager.buildCacheDataSourceFactory(ctx, defaultFactory)
                    } else {
                        defaultFactory
                    }

                val mediaSource =
                    ProgressiveMediaSource
                        .Factory(dsFactory)
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
                        .createMediaSource(buildMediaItem(Uri.parse(playUrlInfo.url), song, playUrlInfo.tier, metadataBuilder))

                return@withContext PlaybackTargetResult.Online(
                    mediaSource = mediaSource,
                    actualTier = playUrlInfo.tier,
                    isDirectCached = isCached,
                    fileCacheFraction = fileCacheFraction,
                )
            } else {
                return@withContext PlaybackTargetResult.Failure("无法获取播放直链 (需 VIP 或版权限制)")
            }
        }
}
