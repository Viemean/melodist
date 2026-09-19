package org.melodist.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.melodist.api.MusicApiService
import org.melodist.api.probeSongQualities
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class AudioQualityVerdict {
    AUTHENTIC, // 真实无损/高解析
    FAKE_LOSSLESS, // 疑似假无损 (MP3等有损转码)
    UPSAMPLED_HIRES, // 假 Hi-Res (44.1k/48k 升频)
    BANDWIDTH_LIMITED, // 频宽受限 (母带/乐器风格，无高频但非有损硬截断)
    LOSSY, // 标称即为标准有损音质
    INDETERMINATE, // 样本不足或无法提取
}

data class AudioAuditResult(
    val verdict: AudioQualityVerdict,
    val cutoffFrequencyHz: Int = 0,
    val confidenceScore: Float = 0f,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 16,
    val channels: Int = 2,
    val bitrateKbps: Int = 0,
    val description: String = "",
    val details: String = "",
)

/**
 * 工业级假音质（假无损 / 升频）检测与频谱分析引擎
 * 核心设计哲学：
 * 1. 疑罪从无，零误杀：假无损坚决锁定 14.0kHz ~ 19.2kHz 铁证区间（精准抓获 128k/192k/256k MP3/AAC），放弃 20kHz 边缘模糊区，杜绝正版 CD 误杀；
 * 2. 升频假 Hi-Res 优先级前置且锁定 20.0kHz ~ 24.5kHz 物理断崖，彻底消除规则遮蔽；
 * 3. 相对阶跃深度比对（Cliff Depth: pre - post >= 18dB），免疫 Dither 噪声整形；
 * 4. 恒定时域观测窗口（>= 0.4s），自适应 44.1k/96k/192k，避免瞬态空洞；
 * 5. 自然平缓滚降曲目提示性通过（AUTHENTIC + 风格偏暖说明）；
 * 6. 严格按 N^2 归一化功率谱密度（PSD），物理分贝刻度与标准对齐。
 */
object AudioQualityAuditor {
    private const val TAG = "AudioQualityAuditor"
    private const val FFT_SIZE = 4096
    private val auditCache = LruCache<String, AudioAuditResult>(100)

    suspend fun auditSong(
        context: Context,
        song: Song,
        explicitFilePath: String? = null,
    ): AudioAuditResult =
        withContext(Dispatchers.IO) {
            val tier = song.currentTier
            val cacheKey = "${song.songMid}_${tier.name}"
            auditCache.get(cacheKey)?.let { return@withContext it }

            // 1. 标准有损音质直接返回 LOSSY，无需打假
            if (tier == AudioQualityTier.Standard || tier == AudioQualityTier.HQ) {
                val result =
                    AudioAuditResult(
                        verdict = AudioQualityVerdict.LOSSY,
                        sampleRateHz = 44100,
                        bitDepth = 16,
                        description = "标准有损编码（MP3 / AAC）",
                        details = "当前音质级别为标准有损格式，高频低通属于正常声学压缩范畴。",
                    )
                auditCache.put(cacheKey, result)
                return@withContext result
            }

            // 2. 解析文件来源（优先本地/缓存，其次 WebDAV 或在线网络直链）
            val target = resolveAudioSource(context, song, explicitFilePath)
            if (target == null || target.pathOrUrl.isBlank()) {
                return@withContext AudioAuditResult(
                    verdict = AudioQualityVerdict.INDETERMINATE,
                    description = "无法获取音频流进行频谱分析",
                    details = "未找到可读取的本地文件、WebDAV 流或可用流媒体直链。",
                )
            }

            // 3. 执行解码与频谱分析（顶层 8s 协程超时兜底，防止极端畸形流底层挂起）
            val result =
                withTimeoutOrNull(8000L) {
                    if (target.isLocal) {
                        analyzeAudioSource(target.pathOrUrl, song, tier, isLocal = true)
                    } else {
                        auditNetworkStreamWithProbeCache(context, target.pathOrUrl, song, tier, target.authHeader)
                    }
                } ?: AudioAuditResult(
                    verdict = AudioQualityVerdict.INDETERMINATE,
                    description = "音频审计分析超时",
                    details = "分析时间超过 8 秒，已自动终止以释放计算资源。",
                )

            auditCache.put(cacheKey, result)
            result
        }

    /**
     * 清理进程被强制终结时遗留的孤儿探针临时文件
     */
    fun cleanOrphanProbeFiles(context: Context) {
        try {
            context.cacheDir.listFiles { _, name -> name.startsWith("audit_probe_") }?.forEach {
                it.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun invalidateCache(songMid: String) {
        for (tier in AudioQualityTier.entries) {
            auditCache.remove("${songMid}_${tier.name}")
        }
    }

    data class AudioSourceTarget(
        val pathOrUrl: String,
        val authHeader: String? = null,
        val isLocal: Boolean = false,
    )

    private suspend fun resolveAudioSource(
        context: Context,
        song: Song,
        explicitFilePath: String?,
    ): AudioSourceTarget? {
        // 1. 显式指定的本地路径
        if (!explicitFilePath.isNullOrBlank() && File(explicitFilePath).exists()) {
            return AudioSourceTarget(explicitFilePath, isLocal = true)
        }
        val localPath = song.localFilePath?.takeIf { it.isNotBlank() && File(it).exists() }
        if (localPath != null) {
            return AudioSourceTarget(localPath, isLocal = true)
        }

        // 2. WebDAV 歌曲路径与流解析
        if (song.songMid.startsWith("webdav_")) {
            val server =
                org.melodist.data.WebDavManager
                    .getActiveServer()
            val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
            if (server != null && relativeHref.isNotBlank()) {
                val cached =
                    org.melodist.data.WebDavManager
                        .getLocalCacheFile(server.id, relativeHref)
                if (cached.exists() && cached.length() > 0L) {
                    return AudioSourceTarget(cached.absolutePath, isLocal = true)
                }
                val (streamUrl, authHeader) =
                    org.melodist.data.WebDavManager
                        .resolvePlaybackUrl(server, relativeHref)
                if (streamUrl.isNotBlank()) {
                    return AudioSourceTarget(streamUrl, authHeader = authHeader, isLocal = false)
                }
            }
        }

        // 3. 当前播放器已解析的试听/播放直链
        val currentPlaySong = PlaybackManager.currentSong.value
        if (currentPlaySong?.songMid == song.songMid) {
            val probed = PlaybackManager.probedQualityOptions.value.find { it.tier == song.currentTier }
            val playUrl = probed?.playUrl
            if (!playUrl.isNullOrBlank()) {
                return AudioSourceTarget(playUrl, isLocal = false)
            }
        }

        // 4. QQ 音乐在线 API 探测
        try {
            val api = MusicApiService()
            val probeResults = api.probeSongQualities(song.songMid, song.effectiveMediaMid)
            val matched = probeResults.find { it.tier == song.currentTier }
            val playUrl = matched?.playUrl
            if (!playUrl.isNullOrBlank()) {
                return AudioSourceTarget(playUrl, isLocal = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to probe quality URL for audit: ${e.message}")
        }
        return null
    }

    private val probeHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun auditNetworkStreamWithProbeCache(
        context: Context,
        url: String,
        song: Song,
        tier: AudioQualityTier,
        authHeader: String? = null,
    ): AudioAuditResult {
        var tempFile: File? = null
        try {
            tempFile = File(context.cacheDir, "audit_probe_${System.currentTimeMillis()}_${(1000..9999).random()}.tmp")
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .header("Range", "bytes=0-2621439") // 2.5MB 探针切片，彻底穿透大封面图与前置元数据
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) MelodistMobile/1.0")

            if (!authHeader.isNullOrBlank()) {
                requestBuilder.header("Authorization", authHeader)
            }

            val request = requestBuilder.build()

            probeHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return AudioAuditResult(
                        verdict = AudioQualityVerdict.INDETERMINATE,
                        description = "网络音频流探针获取失败 (HTTP ${response.code})",
                    )
                }
                val body = response.body
                tempFile.outputStream().use { fos ->
                    val buf = ByteArray(16384)
                    var totalRead = 0L
                    val maxBytes = 2621440L // 最多 2.5MB
                    val source = body.byteStream()
                    while (totalRead < maxBytes) {
                        val read = source.read(buf, 0, min(buf.size.toLong(), maxBytes - totalRead).toInt())
                        if (read == -1) break
                        fos.write(buf, 0, read)
                        totalRead += read
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() < 16384L) {
                return AudioAuditResult(
                    verdict = AudioQualityVerdict.INDETERMINATE,
                    description = "探针数据量过小，无法可靠分析",
                )
            }

            return analyzeAudioSource(tempFile.absolutePath, song, tier, isLocal = true, isProbeSlice = true)
        } catch (e: Exception) {
            Log.w(TAG, "Network stream probe failed: ${e.message}")
            return AudioAuditResult(
                verdict = AudioQualityVerdict.INDETERMINATE,
                description = "网络音频流探测超时或异常",
                details = e.message.orEmpty(),
            )
        } finally {
            try {
                tempFile?.delete()
            } catch (_: Exception) {
            }
        }
    }

    data class PersistentCodecState(
        var pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        var channels: Int = 2,
        var detectedBitDepth: Int = 16,
    )

    private fun analyzeAudioSource(
        sourcePath: String,
        song: Song,
        tier: AudioQualityTier,
        isLocal: Boolean,
        isProbeSlice: Boolean = false,
    ): AudioAuditResult {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(sourcePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                return AudioAuditResult(
                    verdict = AudioQualityVerdict.INDETERMINATE,
                    description = "无法识别音频轨道格式",
                )
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
            val sampleRate =
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    44100
                }
            val channelCount =
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    (song.durationSeconds * 1_000_000L)
                }
            val bitrateBps =
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    format.getInteger(MediaFormat.KEY_BIT_RATE)
                } else {
                    0
                }

            val durationSec = durationUs / 1_000_000L
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // 恒定时域窗口：固定保证至少 0.4 秒采样，支持 44.1k/96k/192k 真实声学包络
            val samplesPerChunk = (sampleRate * 0.4f).toInt().coerceAtLeast(FFT_SIZE * 4)

            // 多采样点独立分析与投票决议：
            // 若为 2.5MB 探针切片，在文件第 2~3 秒安全采样（避开前奏静音，同时保证 192k/24-bit 极端码率不触碰 EOF）；本地文件按全曲比例采样
            val candidateSecs =
                when {
                    isProbeSlice -> listOf(2L, 3L)
                    isLocal -> {
                        if (durationSec > 30L) {
                            listOf((durationSec * 0.3).toLong(), (durationSec * 0.5).toLong(), (durationSec * 0.7).toLong())
                        } else if (durationSec > 10L) {
                            listOf((durationSec * 0.4).toLong(), (durationSec * 0.7).toLong())
                        } else {
                            listOf(1L)
                        }
                    }
                    else -> listOf(20L)
                }

            val sliceResults = mutableListOf<AudioAuditResult>()
            val initialBitDepth =
                if (format.containsKey("bits-per-sample")) {
                    format.getInteger("bits-per-sample")
                } else if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 32
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        else -> 16
                    }
                } else if (tier == AudioQualityTier.HiRes || tier == AudioQualityTier.Master) {
                    24
                } else if (sampleRate >= 88200) {
                    24
                } else {
                    16
                }
            val codecState =
                PersistentCodecState(
                    pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
                    channels = channelCount,
                    detectedBitDepth = initialBitDepth,
                )

            for (anchorSec in candidateSecs) {
                val pcmSamples = extractPcmChunkSafe(extractor, codec, anchorSec, codecState, samplesPerChunk)
                if (pcmSamples.isNotEmpty()) {
                    val rms = calculateRms(pcmSamples)
                    if (rms > 0.003f) { // 过滤静音段 (约 -50dBFS)
                        val powerSpec = computeLinearPowerSpectrum(pcmSamples)
                        val dbSpectrum = convertPowerSpectrumsToDb(listOf(powerSpec))
                        val singleResult =
                            evaluateSpectrum(
                                spectrum = dbSpectrum,
                                sampleRate = sampleRate,
                                bitDepth = codecState.detectedBitDepth,
                                channels = codecState.channels,
                                bitrateKbps = if (bitrateBps > 0) bitrateBps / 1000 else 0,
                                tier = tier,
                            )
                        sliceResults.add(singleResult)
                    }
                }
            }

            if (sliceResults.isEmpty()) {
                return AudioAuditResult(
                    verdict = AudioQualityVerdict.INDETERMINATE,
                    sampleRateHz = sampleRate,
                    bitDepth = codecState.detectedBitDepth,
                    channels = codecState.channels,
                    description = "音频采样片段能量不足，无法可靠分析",
                    details = "所提取的采样片段均为静音或极弱音段。",
                )
            }

            return voteAuditResults(sliceResults)
        } catch (e: Exception) {
            Log.e(TAG, "Audio audit failed: ${e.message}", e)
            return AudioAuditResult(
                verdict = AudioQualityVerdict.INDETERMINATE,
                description = "音频解码或频谱计算异常: ${e.message}",
            )
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 多采样点表决决议机制（2/3 投票制，疑罪从无）：
     * 1. 单切片防护：单切片若测出假音质，由于样本置信度不足，疑罪从无降级为 INDETERMINATE，避免单点误杀；
     * 2. 假无损：>= 2 个切片一致测出低通硬截断特征才判定为假无损；
     * 3. 升频假 Hi-Res：>= 2 个切片一致测出标准母带截断才判定为升频；
     * 4. 矛盾切片（如 1 个切片能量弱，另 2 个全频延伸）：疑罪从无，判定为真无损。
     */
    fun voteAuditResults(sliceResults: List<AudioAuditResult>): AudioAuditResult {
        if (sliceResults.isEmpty()) {
            return AudioAuditResult(
                verdict = AudioQualityVerdict.INDETERMINATE,
                description = "样本不足，无法完成多点投票裁决",
            )
        }
        if (sliceResults.size == 1) {
            val single = sliceResults[0]
            return if (single.verdict == AudioQualityVerdict.FAKE_LOSSLESS || single.verdict == AudioQualityVerdict.UPSAMPLED_HIRES) {
                AudioAuditResult(
                    verdict = AudioQualityVerdict.INDETERMINATE,
                    sampleRateHz = single.sampleRateHz,
                    bitDepth = single.bitDepth,
                    channels = single.channels,
                    description = "网络采样样本过少，不足以确诊假音质",
                    details = "仅获取到单个有效音频切片，为防止网络前奏误伤，请下载完整文件后重试审计。",
                )
            } else {
                single
            }
        }

        val fakeList = sliceResults.filter { it.verdict == AudioQualityVerdict.FAKE_LOSSLESS }
        val upsampledList = sliceResults.filter { it.verdict == AudioQualityVerdict.UPSAMPLED_HIRES }
        val authenticList = sliceResults.filter { it.verdict == AudioQualityVerdict.AUTHENTIC }

        // 1. 假无损多数表决（>= 2 个切片命中）
        if (fakeList.size >= 2) {
            return fakeList.maxByOrNull { it.cutoffFrequencyHz } ?: fakeList[0]
        }

        // 2. 升频假 Hi-Res 多数表决（>= 2 个切片命中）
        if (upsampledList.size >= 2) {
            return upsampledList.maxByOrNull { it.cutoffFrequencyHz } ?: upsampledList[0]
        }

        // 3. 疑罪从无机制：若仅有 1 个切片测出假断崖，其余切片具备全频无损延伸特征，判定为真无损
        if (authenticList.isNotEmpty()) {
            val baseAuthentic = authenticList.first()
            val hasDivergence = fakeList.isNotEmpty() || upsampledList.isNotEmpty()
            return if (hasDivergence) {
                baseAuthentic.copy(
                    confidenceScore = 0.95f,
                    details = "个别采样切片能量偏弱，但多数采样点高频频响自然完整，根据疑罪从无原则判定为真无损。",
                )
            } else {
                baseAuthentic
            }
        }

        return sliceResults[0]
    }

    private fun extractPcmChunkSafe(
        extractor: MediaExtractor,
        codec: MediaCodec,
        seekSec: Long,
        codecState: PersistentCodecState,
        maxTargetSamples: Int,
    ): FloatArray {
        extractor.seekTo(seekSec * 1_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()

        val bufferInfo = MediaCodec.BufferInfo()
        val collectedSamples = ArrayList<Float>(maxTargetSamples)
        var sawInputEOS = false
        val timeoutUs = 5000L
        var iterations = 0

        while (collectedSamples.size < maxTargetSamples && iterations < 150) {
            iterations++
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inIndex)
                    if (inputBuf != null) {
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val sampleTime = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex >= 0) {
                val outputBuf = codec.getOutputBuffer(outIndex)
                if (outputBuf != null && bufferInfo.size > 0) {
                    outputBuf.position(bufferInfo.offset)
                    outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                    outputBuf.order(ByteOrder.LITTLE_ENDIAN)

                    decodePcmBufferToFloat(
                        byteBuffer = outputBuf,
                        pcmEncoding = codecState.pcmEncoding,
                        channels = codecState.channels,
                        outputList = collectedSamples,
                        maxSamples = maxTargetSamples,
                    )
                }
                codec.releaseOutputBuffer(outIndex, false)
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outFormat = codec.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    codecState.channels = max(1, outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                }
                if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    codecState.pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                }
                codecState.detectedBitDepth =
                    when (codecState.pcmEncoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> 32
                        AudioFormat.ENCODING_PCM_32BIT -> 32
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                        else -> 16
                    }
            }

            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break
            }
        }

        return collectedSamples.toFloatArray()
    }

    private fun decodePcmBufferToFloat(
        byteBuffer: ByteBuffer,
        pcmEncoding: Int,
        channels: Int,
        outputList: ArrayList<Float>,
        maxSamples: Int,
    ) {
        val ch = max(1, channels)

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuf = byteBuffer.asFloatBuffer()
                val totalFloats = floatBuf.remaining()
                var i = 0
                while (i < totalFloats && outputList.size < maxSamples) {
                    var sum = 0f
                    for (c in 0 until ch) {
                        if (i + c < totalFloats) {
                            sum += floatBuf.get(i + c)
                        }
                    }
                    outputList.add((sum / ch).coerceIn(-1.0f, 1.0f))
                    i += ch
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val intBuf = byteBuffer.asIntBuffer()
                val totalInts = intBuf.remaining()
                var i = 0
                while (i < totalInts && outputList.size < maxSamples) {
                    var sum = 0.0
                    for (c in 0 until ch) {
                        if (i + c < totalInts) {
                            sum += (intBuf.get(i + c) / 2147483648.0)
                        }
                    }
                    outputList.add((sum / ch).toFloat().coerceIn(-1.0f, 1.0f))
                    i += ch
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val bytesPerFrame = ch * 3
                val totalBytes = byteBuffer.remaining()
                var offset = byteBuffer.position()
                val limit = byteBuffer.limit()

                while (offset + bytesPerFrame <= limit && outputList.size < maxSamples) {
                    var sum = 0.0
                    for (c in 0 until ch) {
                        val b0 = byteBuffer.get(offset + c * 3).toInt() and 0xFF
                        val b1 = byteBuffer.get(offset + c * 3 + 1).toInt() and 0xFF
                        val b2 = byteBuffer.get(offset + c * 3 + 2).toInt() // signed byte
                        val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                        sum += (sample24 / 8388608.0)
                    }
                    outputList.add((sum / ch).toFloat().coerceIn(-1.0f, 1.0f))
                    offset += bytesPerFrame
                }
                byteBuffer.position(offset)
            }
            else -> {
                val shortBuf = byteBuffer.asShortBuffer()
                val totalShorts = shortBuf.remaining()
                var i = 0
                while (i < totalShorts && outputList.size < maxSamples) {
                    var sum = 0f
                    for (c in 0 until ch) {
                        if (i + c < totalShorts) {
                            sum += (shortBuf.get(i + c) / 32768.0f)
                        }
                    }
                    outputList.add((sum / ch).coerceIn(-1.0f, 1.0f))
                    i += ch
                }
            }
        }
    }

    private fun calculateRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSquares = 0.0
        for (s in samples) {
            sumSquares += (s * s)
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    /**
     * 在线性功率域（Power Spectrum Density）计算加窗 FFT，严格除以 N^2 归一化
     */
    fun computeLinearPowerSpectrum(samples: FloatArray): FloatArray {
        val numFrames = min(samples.size / FFT_SIZE, 12)
        val spectrumBins = FFT_SIZE / 2
        val accumulatedPower = FloatArray(spectrumBins)
        val normFactor = (FFT_SIZE.toDouble() * FFT_SIZE.toDouble() * max(1, numFrames)).toFloat()

        if (numFrames <= 0) {
            val padded = FloatArray(FFT_SIZE)
            System.arraycopy(samples, 0, padded, 0, min(samples.size, FFT_SIZE))
            applyHanningWindow(padded)
            val real = padded.clone()
            val imag = FloatArray(FFT_SIZE)
            radix2Fft(real, imag)
            val singleNorm = (FFT_SIZE.toDouble() * FFT_SIZE.toDouble()).toFloat()
            for (i in 0 until spectrumBins) {
                accumulatedPower[i] = (real[i] * real[i] + imag[i] * imag[i]) / singleNorm
            }
            return accumulatedPower
        }

        for (f in 0 until numFrames) {
            val frameData = FloatArray(FFT_SIZE)
            System.arraycopy(samples, f * FFT_SIZE, frameData, 0, FFT_SIZE)
            applyHanningWindow(frameData)

            val real = frameData.clone()
            val imag = FloatArray(FFT_SIZE)
            radix2Fft(real, imag)

            for (i in 0 until spectrumBins) {
                accumulatedPower[i] += (real[i] * real[i] + imag[i] * imag[i]) / normFactor
            }
        }

        return accumulatedPower
    }

    /**
     * 线性功率域平均合并，最后统一做对数换算为物理标准 dBFS
     */
    fun convertPowerSpectrumsToDb(spectrums: List<FloatArray>): FloatArray {
        if (spectrums.isEmpty()) return FloatArray(FFT_SIZE / 2) { -120f }
        val size = spectrums[0].size
        val avgPower = FloatArray(size)
        for (spec in spectrums) {
            for (i in 0 until size) {
                avgPower[i] += spec[i] / spectrums.size
            }
        }

        val dbSpectrum = FloatArray(size)
        for (i in 0 until size) {
            val p = max(avgPower[i], 1e-15f)
            dbSpectrum[i] = (10.0 * log10(p.toDouble())).toFloat()
        }
        return dbSpectrum
    }

    /**
     * 移动窗口平滑算法（约 400Hz 窗口），滤除梳状谐波毛刺，提取真实频响包络
     */
    fun smoothSpectrumEnvelope(
        spectrum: FloatArray,
        windowBins: Int = 15,
    ): FloatArray {
        val size = spectrum.size
        val smoothed = FloatArray(size)
        val halfW = windowBins / 2

        for (i in 0 until size) {
            val start = max(0, i - halfW)
            val end = min(size - 1, i + halfW)
            var sum = 0f
            for (k in start..end) {
                sum += spectrum[k]
            }
            smoothed[i] = sum / (end - start + 1)
        }
        return smoothed
    }

    private fun getBandAverageDb(
        spectrum: FloatArray,
        binResHz: Float,
        startHz: Int,
        endHz: Int,
    ): Float {
        val startBin = (startHz / binResHz).toInt().coerceIn(0, spectrum.size - 1)
        val endBin = (endHz / binResHz).toInt().coerceIn(0, spectrum.size - 1)
        if (startBin >= endBin) return spectrum[startBin]
        var sum = 0f
        for (i in startBin..endBin) sum += spectrum[i]
        return sum / (endBin - startBin + 1)
    }

    /**
     * 终极稳健声学判定引擎（疑罪从无，聚焦铁证）：
     * 1. 假无损坚决锁定 14.0kHz ~ 19.2kHz 铁证区间（128k ~ 256k 转码），放弃 20kHz 边缘模糊区，正版 CD 零误杀；
     * 2. 升频假 Hi-Res 优先级前置（20.0k ~ 24.5k），彻底消除规则遮蔽；
     * 3. 相对能量阶深（Cliff Depth: pre - post >= 18dB）比对，免疫 Dither 噪声；
     * 4. 自然平缓滚降曲目提示性通过（AUTHENTIC + 风格偏暖说明）。
     */
    fun evaluateSpectrum(
        spectrum: FloatArray,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        bitrateKbps: Int,
        tier: AudioQualityTier,
    ): AudioAuditResult {
        val nyquistHz = sampleRate / 2
        val binResolutionHz = nyquistHz.toFloat() / spectrum.size

        // 1. 频响包络平滑 (400Hz 窗口)
        val windowBins = max(5, (400 / binResolutionHz).toInt())
        val smoothed = smoothSpectrumEnvelope(spectrum, windowBins)

        // 2. 中频基准能量 (2k~8k)
        val midRefDb = getBandAverageDb(smoothed, binResolutionHz, 2000, 8000)

        // 3. 扫描真正的砖墙硬截断：
        // 上限死锁在 25kHz（因为 MP3/AAC 截断与 44.1k/48k 升频绝不可能高于 24.5kHz，规避硬件 Nyquist 滤波）
        val scanStartBin = (10000 / binResolutionHz).toInt().coerceIn(0, smoothed.size - 1)
        val scanEndBin = ((min(nyquistHz.toFloat(), 25000f)) / binResolutionHz).toInt().coerceIn(0, smoothed.size - 1)
        val stepBins = max(3, (800 / binResolutionHz).toInt())
        val activeSignalThresholdDb = midRefDb - 28f

        var brickwallCutoffHz = 0
        var maxCliffDepthDb = 0f

        for (i in (scanEndBin - stepBins - 1) downTo scanStartBin) {
            val centerFreqHz = ((i + stepBins / 2) * binResolutionHz).toInt()
            val preStartHz = max(2000, centerFreqHz - 600)
            val postStartHz = min(nyquistHz, centerFreqHz + 200)
            val postEndHz = min(nyquistHz, centerFreqHz + 1600)

            val preEnergy = getBandAverageDb(smoothed, binResolutionHz, preStartHz, centerFreqHz)
            val postEnergy = getBandAverageDb(smoothed, binResolutionHz, postStartHz, postEndHz)
            val cliffDepth = preEnergy - postEnergy

            if (smoothed[i] >= activeSignalThresholdDb && cliffDepth >= 18f) {
                brickwallCutoffHz = centerFreqHz
                maxCliffDepthDb = cliffDepth
                break
            }
        }

        // 4. 判定假 Hi-Res (频窗完全闭合：19201Hz ~ 24500Hz，消除规则遮蔽与频段黑洞)
        if (sampleRate >= 88200 && (tier == AudioQualityTier.HiRes || tier == AudioQualityTier.Master)) {
            if (brickwallCutoffHz in 19201..24500 && maxCliffDepthDb >= 18f) {
                val baseRate = if (brickwallCutoffHz <= 22500) "44.1kHz" else "48kHz"
                return AudioAuditResult(
                    verdict = AudioQualityVerdict.UPSAMPLED_HIRES,
                    cutoffFrequencyHz = brickwallCutoffHz,
                    confidenceScore = 0.98f,
                    sampleRateHz = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    bitrateKbps = bitrateKbps,
                    description = "疑似 $baseRate 升频",
                    details = "在 ${(brickwallCutoffHz / 1000.0).format(1)} kHz 检测到标准母带截断，未见有效超声泛音。",
                )
            }
        }

        // 5. 判定假无损：聚焦 14.0kHz ~ 19.2kHz 铁证区间（128k ~ 256k MP3/AAC 转码），放弃 20kHz 边缘模糊区
        if (tier == AudioQualityTier.SQ || tier == AudioQualityTier.HiRes || tier == AudioQualityTier.Master) {
            if (maxCliffDepthDb >= 18f && brickwallCutoffHz in 14000..19200) {
                val (verdictDesc, detailsDesc) =
                    when (brickwallCutoffHz) {
                        in 14000..16800 -> "疑似 128k 有损转码" to "128kbps 低通截断特征"
                        else -> "疑似 192k~256k 有损转码" to "中高码率低通截断特征"
                    }

                return AudioAuditResult(
                    verdict = AudioQualityVerdict.FAKE_LOSSLESS,
                    cutoffFrequencyHz = brickwallCutoffHz,
                    confidenceScore = 0.96f,
                    sampleRateHz = sampleRate,
                    bitDepth = bitDepth,
                    channels = channels,
                    bitrateKbps = bitrateKbps,
                    description = verdictDesc,
                    details = "在 ${(brickwallCutoffHz / 1000.0).format(1)} kHz 检测到陡峭低通截断，符合 $detailsDesc。",
                )
            }
        }

        // 6. 真实无损 / 真实高解析判定（自然平缓衰减曲目提示性通过）
        val isHiResAuthentic = (sampleRate >= 88200 || (sampleRate > 48000 && bitDepth > 16) || bitDepth >= 24) && maxCliffDepthDb < 18f
        val hasUltrasoundEnergy =
            sampleRate >= 88200 && isHiResAuthentic && getBandAverageDb(smoothed, binResolutionHz, 26000, min(nyquistHz, 30000)) > (midRefDb - 42f)
        val highFreqDb = getBandAverageDb(smoothed, binResolutionHz, 19500, min(nyquistHz, 20500))
        val isWarmMaster = sampleRate <= 48000 && highFreqDb < midRefDb - 25f

        return AudioAuditResult(
            verdict = AudioQualityVerdict.AUTHENTIC,
            cutoffFrequencyHz = nyquistHz,
            confidenceScore = 0.99f,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            bitrateKbps = bitrateKbps,
            description = if (isHiResAuthentic) "真高解析度" else "真无损",
            details =
                when {
                    hasUltrasoundEnergy -> "超声泛音自然充沛，完整覆盖高解析频宽。"
                    isHiResAuthentic -> "母带高频自然延伸，未见升频截断。"
                    isWarmMaster -> "高频随母带风格自然滚降，未见有损截断。"
                    else -> "频响完整延伸至 ${(nyquistHz / 1000.0).format(1)} kHz，高频泛音自然。"
                },
        )
    }

    private fun Double.format(digits: Int) = String.format(java.util.Locale.US, "%.${digits}f", this)

    private fun applyHanningWindow(data: FloatArray) {
        val n = data.size
        for (i in 0 until n) {
            val multiplier = 0.5 * (1 - cos(2.0 * PI * i / (n - 1)))
            data[i] = (data[i] * multiplier).toFloat()
        }
    }

    private fun radix2Fft(
        real: FloatArray,
        imag: FloatArray,
    ) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }
}
