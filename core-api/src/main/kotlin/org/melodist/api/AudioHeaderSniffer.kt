package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.model.AudioQualityTier
import org.melodist.model.QualityOption
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class SniffedAudioSpec(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val totalFileSize: Long,
    val metadataBytes: Long,
    val netAudioBytes: Long,
    val durationSec: Double,
    val calculatedBitrateKbps: Int,
)

object AudioHeaderSniffer {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

    // 缓存已嗅探的 URL 规格，容量 200，避免重复弹窗时再次触发网络请求
    private val specCache =
        object : java.util.LinkedHashMap<String, SniffedAudioSpec>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SniffedAudioSpec>?): Boolean = size > 200
        }

    suspend fun sniff(
        url: String,
        fallbackDurationSec: Int = 0,
    ): SniffedAudioSpec? =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext null
            synchronized(specCache) {
                specCache[url]?.let { return@withContext it }
            }

            try {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Range", "bytes=0-2047")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MelodistTV) AppleWebKit/537.36")
                        .header("Referer", "https://y.qq.com/")
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        return@withContext null
                    }

                    // 1. 从 Content-Range 头提取精确总字节数: "bytes 0-2047/31457280"
                    val contentRange = response.header("Content-Range")
                    val totalSize =
                        contentRange?.substringAfterLast('/')?.toLongOrNull()
                            ?: response.body.contentLength()

                    val respBody = response.body
                    val bytes = respBody.byteStream().use { it.readNBytes(2048) }
                    if (bytes.size < 16) return@withContext null

                    // 2. 解析文件头元数据
                    val parsed = AudioMetadataParser.parse(bytes)
                    val sampleRate = parsed.sampleRate ?: 0
                    val bitDepth = parsed.bitsPerSample ?: 0
                    val channels = parsed.channels ?: 2
                    val metaBytes = parsed.metadataTotalBytes ?: 0L
                    val netAudioBytes = if (totalSize > metaBytes && metaBytes > 0L) totalSize - metaBytes else totalSize

                    val durationSec =
                        when {
                            parsed.durationSeconds != null && parsed.durationSeconds > 0 -> parsed.durationSeconds.toDouble()
                            fallbackDurationSec > 0 -> fallbackDurationSec.toDouble()
                            else -> 0.0
                        }

                    val bitrateKbps =
                        if (durationSec > 0.0 && netAudioBytes > 0L) {
                            ((netAudioBytes * 8.0) / durationSec / 1000.0).roundToInt()
                        } else {
                            0
                        }

                    val result =
                        SniffedAudioSpec(
                            sampleRateHz = sampleRate,
                            bitDepth = bitDepth,
                            channels = channels,
                            totalFileSize = totalSize,
                            metadataBytes = metaBytes,
                            netAudioBytes = netAudioBytes,
                            durationSec = durationSec,
                            calculatedBitrateKbps = bitrateKbps,
                        )

                    synchronized(specCache) {
                        specCache[url] = result
                    }
                    result
                }
            } catch (_: Exception) {
                null
            }
        }

    /**
     * 并发对音质列表进行懒加载嗅探，补全采样率、位深、真实大小以及扣除文件头后的净音频码率
     */
    suspend fun enrichQualityOptions(
        options: List<QualityOption>,
        songDurationSec: Int = 0,
    ): List<QualityOption> =
        withContext(Dispatchers.IO) {
            if (options.isEmpty()) return@withContext options

            val deferreds =
                options.map { option ->
                    async {
                        val playUrl = option.playUrl
                        if (!option.isAvailable || playUrl.isNullOrBlank()) {
                            return@async option
                        }

                        val spec = sniff(playUrl, fallbackDurationSec = songDurationSec) ?: return@async option

                        val finalBitDepth = if (spec.bitDepth > 0) spec.bitDepth else option.bitDepth
                        val finalSampleRate = if (spec.sampleRateHz > 0) spec.sampleRateHz else option.sampleRateHz

                        val needsSizeCorrection =
                            (option.tier == AudioQualityTier.HiRes && (option.sizeBytes <= 0L || (songDurationSec > 0 && option.sizeBytes <= 35_000_000L))) ||
                                option.sizeBytes <= 0L

                        val finalSize =
                            if (needsSizeCorrection && spec.totalFileSize > 0L) {
                                spec.totalFileSize
                            } else {
                                option.sizeBytes
                            }

                        val formattedBitrate =
                            when {
                                option.tier == AudioQualityTier.Standard -> "128 kbps"
                                option.tier == AudioQualityTier.HQ -> "320 kbps"
                                needsSizeCorrection && spec.calculatedBitrateKbps > 0 -> "${spec.calculatedBitrateKbps} kbps"
                                finalSize > 0L && songDurationSec > 0 -> {
                                    val calculated = ((finalSize * 8.0) / songDurationSec / 1000.0).roundToInt()
                                    if (calculated > 0) "$calculated kbps" else option.bitrate
                                }
                                spec.calculatedBitrateKbps > 0 -> "${spec.calculatedBitrateKbps} kbps"
                                else -> option.bitrate
                            }

                        option.copy(
                            sampleRateHz = finalSampleRate,
                            bitDepth = finalBitDepth,
                            sizeBytes = finalSize,
                            bitrate = formattedBitrate,
                        )
                    }
                }

            deferreds.awaitAll()
        }
}
