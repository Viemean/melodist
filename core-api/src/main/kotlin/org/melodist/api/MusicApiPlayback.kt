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
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val targetMediaMid = mediaMid.ifBlank { songMid }
        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey

        val requests =
            listOf(
                Triple("req_master", AudioQualityTier.Master, Pair("AI00", ".flac")),
                Triple("req_atmos71", AudioQualityTier.Atmos71, Pair("Q003", ".ogg")),
                Triple("req_atmos51", AudioQualityTier.Atmos51, Pair("Q001", ".flac")),
                Triple("req_dolby", AudioQualityTier.Dolby, Pair("Q000", ".flac")),
                Triple("req_premium", AudioQualityTier.Premium, Pair("AI00", ".flac")),
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
            val respJson = postGateway(sb.toString())
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
            if (fileObj != null) {
                val sizeNew = fileObj["size_new"]?.jsonArray
                sizeMap[AudioQualityTier.Master] = sizeNew?.getOrNull(0)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Atmos51] = sizeNew?.getOrNull(1)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.Atmos71] = sizeNew?.getOrNull(2)?.jsonPrimitive?.longOrNull ?: 0L
                val dolbySize =
                    (fileObj["size_dolby"]?.jsonPrimitive?.longOrNull ?: 0L).let {
                        if (it > 0L) it else sizeNew?.getOrNull(3)?.jsonPrimitive?.longOrNull ?: 0L
                    }
                sizeMap[AudioQualityTier.Dolby] = dolbySize
                sizeMap[AudioQualityTier.Premium] = sizeNew?.getOrNull(5)?.jsonPrimitive?.longOrNull
                    ?: sizeNew?.getOrNull(0)?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.HiRes] = fileObj["size_hires"]?.jsonPrimitive?.longOrNull
                    ?: fileObj["size_96flac"]?.jsonPrimitive?.longOrNull
                    ?: fileObj["size_24bit"]?.jsonPrimitive?.longOrNull ?: 0L
                sizeMap[AudioQualityTier.SQ] = fileObj["size_flac"]?.jsonPrimitive?.longOrNull ?: 0L
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
                val hasFileSize = if (fileObj != null) fileSize > 0L else true
                val hasValidUrl = purl.length > 5 && sip.isNotEmpty() && result == 0 && purl.contains(prefix, ignoreCase = true)
                val isAvailable = hasFileSize && hasValidUrl
                val playUrl = if (isAvailable) sip + purl else null
                val format =
                    when (tier) {
                        AudioQualityTier.Master, AudioQualityTier.HiRes, AudioQualityTier.SQ, AudioQualityTier.Atmos51,
                        AudioQualityTier.Dolby, AudioQualityTier.Premium,
                        -> "FLAC"
                        AudioQualityTier.Atmos71 -> "OGG"
                        else -> "MP3"
                    }
                val bitrate =
                    when (tier) {
                        AudioQualityTier.Master -> "192kHz/24bit"
                        AudioQualityTier.HiRes -> "96kHz/24bit"
                        AudioQualityTier.Atmos71 -> "7.1 全景声"
                        AudioQualityTier.Atmos51 -> "5.1 环绕声"
                        AudioQualityTier.Dolby -> "杜比全景声"
                        AudioQualityTier.Premium -> "臻品音效"
                        AudioQualityTier.SQ -> "无损 CD"
                        AudioQualityTier.HQ -> "320kbps"
                        AudioQualityTier.Standard -> "128kbps"
                    }

                resultList.add(
                    QualityOption(
                        tier = tier,
                        format = format,
                        bitrate = bitrate,
                        sizeBytes = sizeMap[tier] ?: 0L,
                        isAvailable = isAvailable,
                        playUrl = playUrl,
                    ),
                )
            }
            resultList
        } catch (e: Exception) {
            emptyList()
        }
    }
