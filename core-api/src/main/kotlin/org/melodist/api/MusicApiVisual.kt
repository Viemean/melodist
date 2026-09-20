package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 视觉资产扩展：单曲视觉 MID、歌手头像与专辑封面降级候选链
 */
object MusicApiVisual {
    private val visualMidCache = ConcurrentHashMap<String, String>()

    fun getSingerAvatarUrl(singerMid: String): String = getSingerAvatarCandidates(singerMid).firstOrNull().orEmpty()

    fun getSingerAvatarCandidates(singerMid: String): List<String> {
        if (singerMid.isBlank()) return emptyList()
        return listOf(
            "https://y.gtimg.cn/music/photo_new/T001R800x800M000$singerMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T001R800x800M000$singerMid.jpg?max_age=2592000",
            "https://y.gtimg.cn/music/photo_new/T001R500x500M000$singerMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T001R500x500M000$singerMid.jpg?max_age=2592000",
            "https://y.gtimg.cn/music/photo_new/T001R300x300M000$singerMid.jpg?max_age=2592000",
        )
    }

    /**
     * 获取专辑封面多清晰度与多 CDN 降级候选列表 (原图直出 -> 1200x1200 -> 800x800 -> gtimg -> 500x500)
     */
    fun getAlbumCoverCandidates(albumMid: String): List<String> {
        if (albumMid.isBlank()) return emptyList()
        val rawMid = if (albumMid.contains('_')) albumMid.substringBefore('_') else albumMid
        return listOf(
            "https://y.qq.com/music/photo_new/T002R1200x1200M000$albumMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T002R800x800M000$albumMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T002R800x800M000${rawMid}_1.jpg?max_age=2592000",
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000${rawMid}_2.jpg?max_age=2592000",
            "https://y.gtimg.cn/music/photo_new/T002R1200x1200M000$albumMid.jpg?max_age=2592000",
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T002R500x500M000$albumMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T002M000$albumMid.jpg?max_age=2592000",
        )
    }

    fun getAlbumCoverUrl(albumMid: String): String = getAlbumCoverCandidates(albumMid).firstOrNull().orEmpty()

    /**
     * 获取单曲专属视觉封面候选列表 (T062 前缀: 1200x1200 -> 800x800 -> gtimg -> 500x500 -> 原画)
     */
    fun getSingleCoverCandidates(visualMid: String): List<String> {
        if (visualMid.isBlank()) return emptyList()
        return listOf(
            "https://y.qq.com/music/photo_new/T062R1200x1200M000$visualMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T062R800x800M000$visualMid.jpg?max_age=2592000",
            "https://y.gtimg.cn/music/photo_new/T062R1200x1200M000$visualMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T062R500x500M000$visualMid.jpg?max_age=2592000",
            "https://y.qq.com/music/photo_new/T062M000$visualMid.jpg?max_age=2592000",
        )
    }

    fun getSingleCoverUrl(visualMid: String): String = getSingleCoverCandidates(visualMid).firstOrNull().orEmpty()

    fun cacheVisualMid(songMid: String, visualMid: String) {
        visualMidCache[songMid] = visualMid
    }

    fun getCachedVisualMid(songMid: String): String? = visualMidCache[songMid]
}

/**
 * 针对无专辑或未带封面的单曲，通过歌曲详情接口动态解析单曲专属视觉 MID (vs[1])
 */
suspend fun MusicApiService.getSongVisualMid(songMid: String): String? =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank() || songMid.startsWith("webdav_")) return@withContext null
        MusicApiVisual.getCachedVisualMid(songMid)?.let { return@withContext it }

        val uin = UserSession.profile.uin.ifBlank { "0" }
        val authst = UserSession.profile.musicKey
        val payload =
            """
            {
              "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst" },
              "songinfo": {
                "module": "music.pf_song_detail_svr",
                "method": "get_song_detail_yqq",
                "param": { "song_mid": "$songMid" }
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject
            val songInfo = root["songinfo"]?.jsonObject
            val data = songInfo?.get("data")?.jsonObject
            val trackInfo = data?.get("track_info")?.jsonObject
            val vsArray = trackInfo?.get("vs")?.jsonArray
            val vsList = vsArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val visualMid =
                if (vsList.size > 1 && vsList[1].isNotBlank()) {
                    vsList[1]
                } else {
                    vsList.firstOrNull { it.isNotBlank() }
                }
            if (!visualMid.isNullOrBlank()) {
                MusicApiVisual.cacheVisualMid(songMid, visualMid)
                return@withContext visualMid
            }
        } catch (_: Exception) {
        }
        null
    }
