package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.AudioQualityTier
import org.melodist.model.QualityOption

/**
 * 歌曲多音质并发探测与直链解析扩展
 */
suspend fun MusicApiService.probeSongQualities(
    songMid: String,
    mediaMid: String = "",
): List<QualityOption> =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank()) return@withContext emptyList()
        if (!PlaybackCredentialsManager.hasCustomCredentials) {
            try {
                LoginApiService().ensureMusicKey()
            } catch (_: Exception) {
            }
        }

        val targetMediaMid = mediaMid.ifBlank { songMid }
        val uin = PlaybackCredentialsManager.getActiveUin()
        val authst = PlaybackCredentialsManager.getActiveAuthst()
        val cookieHeader = PlaybackCredentialsManager.getActiveCookieHeader()

        val requests =
            listOf(
                Triple("req_master", AudioQualityTier.Master, Pair("AI00", ".flac")),
                Triple("req_premium", AudioQualityTier.Premium, Pair("Q000", ".flac")),
                Triple("req_atmos", AudioQualityTier.Atmos, Pair("Q001", ".flac")),
                Triple("req_dolby", AudioQualityTier.Dolby, Pair("Q000", ".flac")),
                Triple("req_hires", AudioQualityTier.HiRes, Pair("RS01", ".flac")),
                Triple("req_sq", AudioQualityTier.SQ, Pair("F000", ".flac")),
                Triple("req_320", AudioQualityTier.HQ, Pair("M800", ".mp3")),
                Triple("req_128", AudioQualityTier.Standard, Pair("M500", ".mp3")),
            )

        val sb = StringBuilder(1536)
        sb.append("""{"comm":{"uin":"$uin","format":"json","ct":19,"cv":1,"authst":"$authst"}""")
        sb.append(""", "songinfo":{"module":"music.pf_song_detail_svr","method":"get_song_detail_yqq","param":{"song_mid":"$songMid"}}""")

        for (req in requests) {
            val key = req.first
            val prefix = req.third.first
            val ext = req.third.second
            sb.append(
                """, "$key":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"guid":"10000","songmid":["$songMid"],"songtype":[0],"uin":"$uin","loginflag":1,"platform":"20","filename":["$prefix$targetMediaMid$ext"]}}""",
            )
        }
        sb.append("}")

        try {
            val respJson = postGateway(sb.toString(), customCookieHeader = cookieHeader)
            val root = Json.parseToJsonElement(respJson).jsonObject

            val trackInfo =
                root["songinfo"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("track_info")
                    ?.jsonObject
            val interval = trackInfo?.get("interval")?.jsonPrimitive?.longOrNull ?: 0L
            val fileObj = trackInfo?.get("file")?.jsonObject

            val sizeMap = mutableMapOf<AudioQualityTier, Long>()
            var hiresSample = 0
            var hiresBitdepth = 0
            var flacSize = 0L
            var dolbySize = 0L
            if (fileObj != null) {
                val sizeNew = fileObj["size_new"]?.jsonArray
                val masterSize = sizeNew?.getOrNull(0)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Master] = masterSize
                sizeMap[AudioQualityTier.Premium] = sizeNew?.getOrNull(1)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Atmos] = sizeNew?.getOrNull(2)?.jsonPrimitive?.longOrNull ?: 0L

                dolbySize = fileObj["size_dolby"]?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Dolby] = dolbySize

                val hiresRaw =
                    fileObj["size_hires"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                        ?: fileObj["size_96flac"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                        ?: fileObj["size_24bit"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                        ?: sizeNew
                            ?.getOrNull(11)
                            ?.jsonPrimitive
                            ?.longOrNull
                            ?.takeIf { it > 0L }
                        ?: 0L
                flacSize = fileObj["size_flac"]?.jsonPrimitive?.longOrNull ?: 0L
                hiresSample = fileObj["hires_sample"]?.jsonPrimitive?.intOrNull ?: 0
                hiresBitdepth = fileObj["hires_bitdepth"]?.jsonPrimitive?.intOrNull ?: 0

                val isTrueHiRes = hiresRaw > 0L || hiresSample > 48000 || hiresBitdepth > 16
                sizeMap[AudioQualityTier.HiRes] =
                    if (isTrueHiRes) {
                        if (hiresRaw > 0L) hiresRaw else flacSize
                    } else {
                        0L
                    }
                sizeMap[AudioQualityTier.SQ] = flacSize
                sizeMap[AudioQualityTier.HQ] = fileObj["size_320mp3"]?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Standard] = fileObj["size_128mp3"]?.jsonPrimitive?.longOrNull ?: 0L
            }

            val resultList = mutableListOf<QualityOption>()
            for (req in requests) {
                val key = req.first
                val tier = req.second
                val prefix = req.third.first
                val reqData = root[key]?.jsonObject?.get("data")?.jsonObject
                val sip =
                    reqData
                        ?.get("sip")
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val midurlinfo =
                    reqData
                        ?.get("midurlinfo")
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                val purl =
                    midurlinfo
                        ?.get("purl")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                val result = midurlinfo?.get("result")?.jsonPrimitive?.intOrNull ?: 0

                val fileSize = sizeMap[tier] ?: 0L
                val hasValidUrl = purl.length > 5 && sip.isNotEmpty() && result == 0 && purl.contains(prefix, ignoreCase = true)
                val isAvailable =
                    if (tier == AudioQualityTier.Dolby) {
                        dolbySize > 0L && hasValidUrl
                    } else if (fileObj != null) {
                        if (tier == AudioQualityTier.HiRes) {
                            (fileSize > 0L || hiresSample > 48000 || hiresBitdepth > 16) && hasValidUrl
                        } else {
                            fileSize > 0L && hasValidUrl
                        }
                    } else {
                        hasValidUrl
                    }
                val playUrl = if (isAvailable) sip + purl else null
                val format =
                    if (purl.isNotBlank()) {
                        purl.substringBefore('?').substringAfterLast('.', "").uppercase().ifBlank {
                            req.third.second
                                .removePrefix(".")
                                .uppercase()
                        }
                    } else {
                        req.third.second
                            .removePrefix(".")
                            .uppercase()
                    }

                val isMaster = tier == AudioQualityTier.Master
                val isHiRes = tier == AudioQualityTier.HiRes
                val sampleRateHz =
                    if (isHiRes || isMaster) {
                        if (hiresSample > 0) hiresSample else if (isMaster) 96000 else 0
                    } else {
                        0
                    }
                val bitDepth =
                    if (isHiRes || isMaster) {
                        if (hiresBitdepth > 0) hiresBitdepth else 24
                    } else {
                        0
                    }

                var resolvedSize = sizeMap[tier] ?: 0L
                val needsCorrection =
                    isAvailable && playUrl != null && (
                        (tier == AudioQualityTier.HiRes && (resolvedSize <= 0L || (flacSize > 0L && resolvedSize <= flacSize))) ||
                            resolvedSize <= 0L
                    )

                if (needsCorrection) {
                    try {
                        val conn = java.net.URI.create(playUrl).toURL().openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "HEAD"
                        conn.setRequestProperty("Referer", "https://y.qq.com/")
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn.connectTimeout = 3000
                        conn.readTimeout = 3000
                        conn.connect()
                        val len = conn.contentLengthLong
                        conn.disconnect()
                        if (len > 0L) {
                            resolvedSize = len
                        }
                    } catch (_: Exception) {
                    }
                }

                val bitrate =
                    if (sampleRateHz > 0 && bitDepth > 0) {
                        "${sampleRateHz / 1000}kHz/${bitDepth}bit"
                    } else if (resolvedSize > 0L && interval > 0L) {
                        "${Math.round((resolvedSize * 8.0) / interval / 1000.0)}kbps"
                    } else {
                        ""
                    }

                resultList.add(
                    QualityOption(
                        tier = tier,
                        format = format,
                        bitrate = bitrate,
                        sizeBytes = resolvedSize,
                        isAvailable = isAvailable,
                        playUrl = playUrl,
                        sampleRateHz = sampleRateHz,
                        bitDepth = bitDepth,
                    ),
                )
            }

            resultList
        } catch (e: Exception) {
            emptyList()
        }
    }

data class QualityResult(
    val url: String?,
    val tier: AudioQualityTier,
    val badge: String,
)

/**
 * 探测歌曲各音质档位直链并获取最佳可用播放 URL
 */
suspend fun MusicApiService.getPlayUrl(
    songMid: String,
    mediaMid: String = "",
    preferredTier: AudioQualityTier = AudioQualityTier.SQ,
): QualityResult =
    withContext(Dispatchers.IO) {
        if (!PlaybackCredentialsManager.hasCustomCredentials) {
            try {
                LoginApiService().ensureMusicKey()
            } catch (_: Exception) {
            }
        }

        val targetMediaMid = mediaMid.ifBlank { songMid }
        val uin = PlaybackCredentialsManager.getActiveUin()
        val authst = PlaybackCredentialsManager.getActiveAuthst()
        val cookieHeader = PlaybackCredentialsManager.getActiveCookieHeader()

        val requests =
            listOf(
                Triple("req_master", AudioQualityTier.Master, Pair("AI00", ".flac")),
                Triple("req_premium", AudioQualityTier.Premium, Pair("Q000", ".flac")),
                Triple("req_atmos", AudioQualityTier.Atmos, Pair("Q001", ".flac")),
                Triple("req_dolby", AudioQualityTier.Dolby, Pair("Q000", ".flac")),
                Triple("req_hires", AudioQualityTier.HiRes, Pair("RS01", ".flac")),
                Triple("req_sq", AudioQualityTier.SQ, Pair("F000", ".flac")),
                Triple("req_hq", AudioQualityTier.HQ, Pair("M800", ".mp3")),
                Triple("req_std", AudioQualityTier.Standard, Pair("M500", ".mp3")),
            )

        val sb = StringBuilder(1536)
        sb.append("""{"comm":{"uin":"$uin","format":"json","ct":19,"cv":1,"authst":"$authst"}""")
        sb.append(
            """, "songinfo":{"module":"music.pf_song_detail_svr","method":"get_song_detail_yqq","param":{"song_mid":"$songMid"}}""",
        )
        for (req in requests) {
            val key = req.first
            val prefix = req.third.first
            val ext = req.third.second
            sb.append(
                """, "$key":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"guid":"10000","songmid":["$songMid"],"songtype":[0],"uin":"$uin","loginflag":1,"platform":"20","filename":["$prefix$targetMediaMid$ext"]}}""",
            )
        }
        sb.append("}")

        try {
            val respJson = postGateway(sb.toString(), customCookieHeader = cookieHeader)
            val root = Json.parseToJsonElement(respJson).jsonObject

            val fileObj =
                root["songinfo"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("track_info")
                    ?.jsonObject
                    ?.get("file")
                    ?.jsonObject

            val sizeMap = mutableMapOf<AudioQualityTier, Long>()
            var dolbySize = 0L
            if (fileObj != null) {
                val sizeNew = fileObj["size_new"]?.jsonArray
                val masterSize = sizeNew?.getOrNull(0)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Master] = masterSize
                sizeMap[AudioQualityTier.Premium] = sizeNew?.getOrNull(1)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Atmos] = sizeNew?.getOrNull(2)?.jsonPrimitive?.longOrNull ?: 0L
                dolbySize = fileObj["size_dolby"]?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Dolby] = dolbySize
                val hiresRaw =
                    fileObj["size_hires"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                        ?: fileObj["size_96flac"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                        ?: fileObj["size_24bit"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L }
                        ?: sizeNew
                            ?.getOrNull(11)
                            ?.jsonPrimitive
                            ?.longOrNull
                            ?.takeIf { it > 0L }
                        ?: 0L
                val flacSize = fileObj["size_flac"]?.jsonPrimitive?.longOrNull ?: 0L
                val hiresSample = fileObj["hires_sample"]?.jsonPrimitive?.intOrNull ?: 0
                val hiresBitdepth = fileObj["hires_bitdepth"]?.jsonPrimitive?.intOrNull ?: 0

                val isTrueHiRes = hiresRaw > 0L || hiresSample > 48000 || hiresBitdepth > 16
                sizeMap[AudioQualityTier.HiRes] =
                    if (isTrueHiRes) {
                        if (hiresRaw > 0L) hiresRaw else flacSize
                    } else {
                        0L
                    }
                sizeMap[AudioQualityTier.SQ] = flacSize
                sizeMap[AudioQualityTier.HQ] = fileObj["size_320mp3"]?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Standard] = fileObj["size_128mp3"]?.jsonPrimitive?.longOrNull ?: 0L
            }

            val availableMap = mutableMapOf<AudioQualityTier, String>()
            for (req in requests) {
                val key = req.first
                val tier = req.second
                val prefix = req.third.first
                val reqData = root[key]?.jsonObject?.get("data")?.jsonObject ?: continue
                val sips = reqData["sip"]?.jsonArray
                val sip = sips?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: continue
                val midInfo = reqData["midurlinfo"]?.jsonArray?.firstOrNull()?.jsonObject
                val purl = midInfo?.get("purl")?.jsonPrimitive?.contentOrNull
                val result = midInfo?.get("result")?.jsonPrimitive?.intOrNull ?: 0
                val fileSize = sizeMap[tier] ?: 0L
                val hasValidUrl = !purl.isNullOrBlank() && purl.length > 5 && result == 0 && purl.contains(prefix, ignoreCase = true)
                val isAvailable =
                    if (tier == AudioQualityTier.Dolby) {
                        dolbySize > 0L && hasValidUrl
                    } else if (fileObj != null) {
                        fileSize > 0L && hasValidUrl
                    } else {
                        hasValidUrl
                    }
                if (isAvailable && purl != null) {
                    // 过滤 Android 系统解码器 (c2.android.vorbis.decoder) 无法解码的 12 声道 Q003 Vorbis 流
                    if (purl.contains("Q003", ignoreCase = true) || purl.endsWith(".ogg", ignoreCase = true)) {
                        continue
                    }
                    availableMap[tier] = sip + purl
                }
            }

            if (availableMap.isEmpty() && UserSession.isLoggedIn) {
                val refreshed = LoginApiService().forceRefreshMusicKey()
                if (refreshed) {
                    val newUin = PlaybackCredentialsManager.getActiveUin()
                    val newAuthst = PlaybackCredentialsManager.getActiveAuthst()
                    val newCookieHeader = PlaybackCredentialsManager.getActiveCookieHeader()
                    val retryPayload = sb.toString()
                        .replace(""""uin":"$uin"""", """"uin":"$newUin"""")
                        .replace(""""authst":"$authst"""", """"authst":"$newAuthst"""")
                    try {
                        val retryResp = postGateway(retryPayload, customCookieHeader = newCookieHeader)
                        val retryRoot = Json.parseToJsonElement(retryResp).jsonObject
                        for (req in requests) {
                            val key = req.first
                            val tier = req.second
                            val prefix = req.third.first
                            val reqData = retryRoot[key]?.jsonObject?.get("data")?.jsonObject ?: continue
                            val sips = reqData["sip"]?.jsonArray
                            val sip = sips?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: continue
                            val midInfo = reqData["midurlinfo"]?.jsonArray?.firstOrNull()?.jsonObject
                            val purl = midInfo?.get("purl")?.jsonPrimitive?.contentOrNull
                            val result = midInfo?.get("result")?.jsonPrimitive?.intOrNull ?: 0
                            val fileSize = sizeMap[tier] ?: 0L
                            val hasValidUrl = !purl.isNullOrBlank() && purl.length > 5 && result == 0 && purl.contains(prefix, ignoreCase = true)
                            val isAvailable =
                                if (tier == AudioQualityTier.Dolby) {
                                    dolbySize > 0L && hasValidUrl
                                } else if (fileObj != null) {
                                    fileSize > 0L && hasValidUrl
                                } else {
                                    hasValidUrl
                                }
                            if (isAvailable && purl != null) {
                                if (purl.contains("Q003", ignoreCase = true) || purl.endsWith(".ogg", ignoreCase = true)) continue
                                availableMap[tier] = sip + purl
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 优先检查 preferredTier
            availableMap[preferredTier]?.let { url ->
                return@withContext QualityResult(url, preferredTier, AudioQualityTier.getBadge(preferredTier))
            }

            // 向下音质降级
            val fallbackCandidates =
                when (preferredTier) {
                    AudioQualityTier.Master ->
                        listOf(
                            AudioQualityTier.HiRes,
                            AudioQualityTier.SQ,
                            AudioQualityTier.HQ,
                            AudioQualityTier.Standard,
                        )
                    AudioQualityTier.HiRes ->
                        listOf(
                            AudioQualityTier.SQ,
                            AudioQualityTier.HQ,
                            AudioQualityTier.Standard,
                        )
                    AudioQualityTier.SQ ->
                        listOf(
                            AudioQualityTier.HQ,
                            AudioQualityTier.Standard,
                        )
                    AudioQualityTier.HQ ->
                        listOf(
                            AudioQualityTier.Standard,
                        )
                    AudioQualityTier.Standard -> emptyList()
                    AudioQualityTier.Atmos ->
                        listOf(
                            AudioQualityTier.Dolby,
                            AudioQualityTier.Premium,
                            AudioQualityTier.Master,
                            AudioQualityTier.HiRes,
                            AudioQualityTier.SQ,
                            AudioQualityTier.HQ,
                            AudioQualityTier.Standard,
                        )
                    AudioQualityTier.Dolby ->
                        listOf(
                            AudioQualityTier.Premium,
                            AudioQualityTier.Master,
                            AudioQualityTier.HiRes,
                            AudioQualityTier.SQ,
                            AudioQualityTier.HQ,
                            AudioQualityTier.Standard,
                        )
                    AudioQualityTier.Premium ->
                        listOf(
                            AudioQualityTier.Master,
                            AudioQualityTier.HiRes,
                            AudioQualityTier.SQ,
                            AudioQualityTier.HQ,
                            AudioQualityTier.Standard,
                        )
                }

            for (tier in fallbackCandidates) {
                availableMap[tier]?.let { url ->
                    return@withContext QualityResult(url, tier, AudioQualityTier.getBadge(tier))
                }
            }

            // 兜底保障（若向下候选未命中，则回退到基础可用流）
            val ultimateFallback =
                listOf(
                    AudioQualityTier.SQ,
                    AudioQualityTier.HQ,
                    AudioQualityTier.Standard,
                    AudioQualityTier.HiRes,
                    AudioQualityTier.Master,
                )
            for (tier in ultimateFallback) {
                availableMap[tier]?.let { url ->
                    return@withContext QualityResult(url, tier, AudioQualityTier.getBadge(tier))
                }
            }

            QualityResult(null, AudioQualityTier.Standard, "无音源")
        } catch (e: Exception) {
            QualityResult(null, AudioQualityTier.Standard, "解析失败")
        }
    }

