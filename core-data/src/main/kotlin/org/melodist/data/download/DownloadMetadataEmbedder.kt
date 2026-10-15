package org.melodist.data.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.api.MusicApiService
import org.melodist.api.getLyrics
import org.melodist.data.AppSettingsManager
import org.melodist.data.LyricCacheManager
import org.melodist.data.LyricSaveMode
import org.melodist.model.AudioQualityTier
import org.melodist.model.CoverUrlResolver
import org.melodist.model.Song
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

internal object DownloadMetadataEmbedder {
    private const val TAG = "DownloadMetadataEmbedder"
    private const val MAX_RAW_COVER_BYTES = (2.5 * 1024 * 1024).toLong()
    private const val COVER_COMPRESSION_QUALITY = 88

    fun sanitizeCoverArt(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size <= MAX_RAW_COVER_BYTES) {
            return rawBytes
        }
        return try {
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return rawBytes
            try {
                val bos = ByteArrayOutputStream(rawBytes.size / 4)
                val success = bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_COMPRESSION_QUALITY, bos)
                if (success) {
                    val compressed = bos.toByteArray()
                    if (compressed.isNotEmpty() && compressed.size < rawBytes.size) {
                        Log.i(TAG, "Compressed oversized cover from ${rawBytes.size} bytes to ${compressed.size} bytes (${bitmap.width}x${bitmap.height})")
                        return compressed
                    }
                }
                rawBytes
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            try {
                Log.w(TAG, "Failed to compress oversized cover art, keeping raw bytes", t)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Operation failed", e)
            }
            rawBytes
        }
    }

    fun formatLrcTime(timeMs: Long): String {
        val totalSec = timeMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val ms = (timeMs % 1000) / 10
        return "%02d:%02d.%02d".format(min, sec, ms)
    }

    suspend fun embedMetadata(
        taskId: String,
        song: Song,
        tier: AudioQualityTier,
        file: File,
        apiService: MusicApiService,
        httpClient: OkHttpClient,
        activeCalls: ConcurrentHashMap<String, Call>,
    ) {
        var coverBytes: ByteArray? = null
        var lyricsMerged: String? = null

        val isStandardOrHq = tier == AudioQualityTier.Standard || tier == AudioQualityTier.HQ
        val candidateUrls =
            if (isStandardOrHq) {
                listOf(
                    CoverUrlResolver.replaceDimension(song.coverUrl, 1200),
                    CoverUrlResolver.replaceDimension(song.coverUrl, 800),
                    song.coverUrl,
                )
            } else {
                song.rawCoverCandidates.ifEmpty {
                    listOf(
                        CoverUrlResolver.getRawUrlOnly(song.coverUrl) ?: "",
                        CoverUrlResolver.replaceDimension(song.coverUrl, 1200),
                        song.coverUrl,
                    )
                }
            }.filter { it.isNotBlank() && !it.startsWith("file://") }.distinct()

        for (candidateUrl in candidateUrls) {
            if (!coroutineContext.isActive) break
            try {
                val coverReq = Request.Builder().url(candidateUrl).build()
                val coverCall = httpClient.newCall(coverReq)
                activeCalls[taskId] = coverCall
                val coverResp = coverCall.execute()
                if (coverResp.isSuccessful) {
                    val bodyBytes = coverResp.body.bytes()
                    if (bodyBytes.isNotEmpty()) {
                        coverBytes = sanitizeCoverArt(bodyBytes)
                        break
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to fetch candidate cover from $candidateUrl", e)
            } finally {
                activeCalls.remove(taskId)
            }
        }

        val lyricSettings = AppSettingsManager.settings.value
        val saveMode = lyricSettings.lyricSaveMode
        val includeTranslation = lyricSettings.saveLyricTranslation

        try {
            val cachedLyrics = LyricCacheManager.getLyrics(song.songMid)
            val lyricLines = cachedLyrics ?: apiService.getLyrics(song.songMid, song.songId)
            if (lyricLines.isNotEmpty()) {
                val sb = StringBuilder()
                for (line in lyricLines) {
                    val timeStr = formatLrcTime(line.timestampMs)
                    sb.append("[$timeStr]${line.text}\n")
                    if (includeTranslation && line.hasTranslation && line.transText.isNotBlank()) {
                        sb.append("[$timeStr]${line.transText}\n")
                    }
                }
                lyricsMerged = sb.toString().trim()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching lyrics for download", e)
        }

        if (!lyricsMerged.isNullOrBlank() && (saveMode == LyricSaveMode.LrcFile || saveMode == LyricSaveMode.Both)) {
            try {
                val lrcFile = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                lrcFile.writeText(lyricsMerged, Charsets.UTF_8)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to write companion lrc file for ${file.name}", e)
            }
        }

        try {
            val metadataPayload =
                AudioMetadataWriter.MetadataPayload(
                    title = song.name,
                    artist = song.singer,
                    album = song.album.ifBlank { song.name },
                    lyrics = if (saveMode == LyricSaveMode.Embedded || saveMode == LyricSaveMode.Both) lyricsMerged else null,
                    coverBytes = coverBytes,
                    coverMime = "image/jpeg",
                )
            AudioMetadataWriter.writeMetadata(file, metadataPayload)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to embed metadata, preserving raw audio: ${e.message}")
        }
    }
}
