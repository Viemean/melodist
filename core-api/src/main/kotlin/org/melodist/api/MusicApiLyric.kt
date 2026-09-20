package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.melodist.model.LyricLine
import java.util.Base64

/**
 * 歌词领域扩展：双语歌词拉取、传统降级接口与智能对齐纠偏
 */

/**
 * 获取歌曲双语同步歌词
 */
suspend fun MusicApiService.getLyrics(
    songMid: String,
    songId: Long = 0L,
    songName: String = "",
    singer: String = "",
): List<LyricLine> =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank() && songId <= 0L && songName.isBlank()) return@withContext emptyList()
        var rawLyric = ""
        var rawTrans = ""

        if (songMid.isNotBlank() || songId > 0L) {
            val payload =
                """
                {"comm":{"ct":24,"cv":0},"playLyricInfo":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"$songMid","songID":$songId,"qrc":0,"trans":1,"roma":1,"isHQ":1}}}
                """.trimIndent()

            try {
                val respJson = postGateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val playLyricInfo = root["playLyricInfo"]?.jsonObject
                val code = playLyricInfo?.get("code")?.jsonPrimitive?.intOrNull ?: 0
                if (code == 0) {
                    val data = playLyricInfo?.get("data")?.jsonObject
                    val b64Lyric = data?.get("lyric")?.jsonPrimitive?.contentOrNull
                    val b64Trans = data?.get("trans")?.jsonPrimitive?.contentOrNull

                    rawLyric = decodeBase64(b64Lyric)
                    rawTrans = decodeBase64(b64Trans)
                }

                // 若主网关返回空，尝试传统歌词接口降级拉取
                if (rawLyric.isBlank() && songMid.isNotBlank()) {
                    val fallback = fetchLegacyLyric(songMid)
                    if (fallback.first.isNotBlank()) {
                        rawLyric = fallback.first
                        if (rawTrans.isBlank()) {
                            rawTrans = fallback.second
                        }
                    }
                }
            } catch (_: Exception) {
                try {
                    if (songMid.isNotBlank()) {
                        val fallback = fetchLegacyLyric(songMid)
                        if (fallback.first.isNotBlank()) {
                            rawLyric = fallback.first
                            rawTrans = fallback.second
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 若本曲未提供有效歌词（如翻唱单曲、未挂载专辑的原声单曲返回 code 24001），通过歌名与歌手进行同名曲目歌词智能匹配
        if (rawLyric.isBlank() && songName.isNotBlank()) {
            try {
                val cleanTitle = songName.replace(Regex("""\s*[\(\[（【].*?[\)\]）】]"""), "").trim().ifBlank { songName.trim() }
                val cleanSinger =
                    singer
                        .substringBefore('/')
                        .substringBefore('&')
                        .substringBefore(',')
                        .trim()
                val queries = mutableListOf<String>()
                if (cleanSinger.isNotBlank() && cleanSinger != "未知歌手" && cleanSinger != "Unknown") {
                    queries.add("$cleanTitle $cleanSinger")
                }
                queries.add(cleanTitle)

                for (q in queries) {
                    val candidates = search(q, page = 1, pageSize = 5)
                    for (cand in candidates) {
                        if (cand.songMid == songMid && songMid.isNotBlank()) continue
                        val candClean = cand.name.replace(Regex("""\s*[\(\[（【].*?[\)\]）】]"""), "").trim()
                        val isTitleMatch =
                            candClean.equals(cleanTitle, ignoreCase = true) ||
                                cand.name.contains(cleanTitle, ignoreCase = true) ||
                                cleanTitle.contains(candClean, ignoreCase = true)
                        if (!isTitleMatch) continue

                        val candLyrics = getLyrics(cand.songMid, cand.songId)
                        if (candLyrics.isNotEmpty()) {
                            return@withContext candLyrics
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (rawLyric.isNotBlank()) {
            LyricParser.parseMergedLyrics(rawLyric, rawTrans)
        } else {
            emptyList()
        }
    }

private suspend fun MusicApiService.fetchLegacyLyric(songMid: String): Pair<String, String> =
    withContext(Dispatchers.IO) {
        val url =
            "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songMid&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf8&notice=0&platform=yqq.json&needNewCode=0"
        try {
            val resp = getUrl(url)
            val root = Json.parseToJsonElement(resp).jsonObject
            val rawLyric = decodeBase64(root["lyric"]?.jsonPrimitive?.contentOrNull)
            val rawTrans = decodeBase64(root["trans"]?.jsonPrimitive?.contentOrNull)
            Pair(rawLyric, rawTrans)
        } catch (_: Exception) {
            Pair("", "")
        }
    }

private fun decodeBase64(source: String?): String {
    if (source.isNullOrBlank()) return ""
    val clean = source.replace("\r", "").replace("\n", "").trim()
    if (clean.isEmpty()) return ""
    return try {
        val bytes =
            try {
                Base64.getDecoder().decode(clean)
            } catch (_: Exception) {
                Base64.getMimeDecoder().decode(clean)
            }
        String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }
}
