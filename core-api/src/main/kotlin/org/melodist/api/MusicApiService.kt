package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.melodist.model.AudioQualityTier
import org.melodist.model.LyricLine
import org.melodist.model.Song
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

data class QualityResult(
    val url: String?,
    val tier: AudioQualityTier,
    val badge: String,
)

class MusicApiService(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build(),
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        private const val API_ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val AG1_ENDPOINT = "https://u6.y.qq.com/cgi-bin/musics.fcg"

        /**
         * 解析 Song 实体
         */
        fun parseSongFromElement(element: JsonElement): Song? {
            val rawObj =
                try {
                    element.jsonObject
                } catch (_: Exception) {
                    return null
                }
            val track = rawObj["track"]?.jsonObject ?: rawObj

            val songMid =
                track["mid"]?.jsonPrimitive?.contentOrNull
                    ?: track["songmid"]?.jsonPrimitive?.contentOrNull
                    ?: track["song_mid"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            if (songMid.isBlank()) return null

            val songId =
                track["id"]?.jsonPrimitive?.longOrNull
                    ?: track["songid"]?.jsonPrimitive?.longOrNull
                    ?: track["song_id"]?.jsonPrimitive?.longOrNull
                    ?: 0L

            val title =
                track["title"]?.jsonPrimitive?.contentOrNull
                    ?: track["name"]?.jsonPrimitive?.contentOrNull
                    ?: track["songname"]?.jsonPrimitive?.contentOrNull
                    ?: "未知曲目"

            val albumObj = track["album"]?.jsonObject
            val albumName =
                albumObj?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: track["albumname"]?.jsonPrimitive?.contentOrNull
                    ?: (track["album"]?.jsonPrimitive?.contentOrNull ?: "")
            val albumMid =
                albumObj?.get("mid")?.jsonPrimitive?.contentOrNull
                    ?: albumObj?.get("pmid")?.jsonPrimitive?.contentOrNull
                    ?: track["albummid"]?.jsonPrimitive?.contentOrNull
                    ?: ""

            val duration = track["interval"]?.jsonPrimitive?.intOrNull ?: 0

            val singerList = mutableListOf<org.melodist.model.Artist>()
            track["singer"]?.jsonArray?.forEach { sElem ->
                val sObj = sElem.jsonObject
                val sName =
                    sObj["name"]?.jsonPrimitive?.contentOrNull
                        ?: sObj["singer_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sMid =
                    sObj["mid"]?.jsonPrimitive?.contentOrNull
                        ?: sObj["singer_mid"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sId =
                    sObj["id"]?.jsonPrimitive?.longOrNull
                        ?: sObj["singer_id"]?.jsonPrimitive?.longOrNull ?: 0L
                if (sName.isNotBlank()) {
                    val avatar = if (sMid.isNotBlank()) getSingerAvatarUrl(sMid) else ""
                    singerList.add(org.melodist.model.Artist(id = sId, mid = sMid, name = sName, avatarUrl = avatar))
                }
            }

            val singers =
                if (singerList.isNotEmpty()) {
                    singerList.joinToString(" / ") { it.name }
                } else {
                    track["singer_name"]?.jsonPrimitive?.contentOrNull ?: "未知歌手"
                }

            val mediaMid =
                track["file"]
                    ?.jsonObject
                    ?.get("media_mid")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: track["strMediaMid"]?.jsonPrimitive?.contentOrNull
                    ?: songMid

            val vsArray = track["vs"]?.jsonArray
            val vsList = vsArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val visualMid =
                if (vsList.size > 1 && vsList[1].isNotBlank()) {
                    vsList[1]
                } else {
                    vsList.firstOrNull { it.isNotBlank() }.orEmpty()
                }

            val albumCover = getAlbumCoverUrl(albumMid)
            val coverUrl =
                if (albumCover.isNotBlank()) {
                    albumCover
                } else if (visualMid.isNotBlank()) {
                    getSingleCoverUrl(visualMid)
                } else {
                    ""
                }

            return Song(
                songId = songId,
                songMid = songMid,
                name = title,
                singer = singers,
                album = albumName,
                albumMid = albumMid,
                durationSeconds = duration,
                currentTier = AudioQualityTier.SQ,
                coverUrl = coverUrl,
                mediaMid = mediaMid,
                singerList = singerList,
                visualMid = visualMid,
            )
        }

        /**
         * 获取歌手高清头像 URL
         */
        fun getSingerAvatarUrl(singerMid: String): String = getSingerAvatarCandidates(singerMid).firstOrNull().orEmpty()

        /**
         * 获取歌手写真多清晰度与多 CDN 降级候选列表 (500x500 -> 300x300 -> 150x150)
         */
        fun getSingerAvatarCandidates(singerMid: String): List<String> {
            if (singerMid.isBlank()) return emptyList()
            return listOf(
                "https://y.gtimg.cn/music/photo_new/T001R500x500M000$singerMid.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T001R500x500M000$singerMid.jpg?max_age=2592000",
                "https://y.gtimg.cn/music/photo_new/T001R300x300M000$singerMid.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T001R300x300M000$singerMid.jpg?max_age=2592000",
                "https://y.gtimg.cn/music/photo_new/T001R150x150M000$singerMid.jpg?max_age=2592000",
            )
        }

        /**
         * 获取专辑封面多清晰度与多 CDN 降级候选列表 (1200x1200 -> 800x800 -> gtimg -> 500x500 -> 300x300)
         */
        fun getAlbumCoverCandidates(albumMid: String): List<String> {
            if (albumMid.isBlank()) return emptyList()
            val rawMid = if (albumMid.contains('_')) albumMid.substringBefore('_') else albumMid
            return listOf(
                "https://y.qq.com/music/photo_new/T002R1200x1200M000$albumMid.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T002R800x800M000$albumMid.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T002R800x800M000${rawMid}_1.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T002R800x800M000${rawMid}_2.jpg?max_age=2592000",
                "https://y.gtimg.cn/music/photo_new/T002R1200x1200M000$albumMid.jpg?max_age=2592000",
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg?max_age=2592000",
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000${rawMid}_1.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T002R500x500M000$albumMid.jpg?max_age=2592000",
                "https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg?max_age=2592000",
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

        private val visualMidCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    /**
     * 针对无专辑或未带封面的单曲，通过歌曲详情接口动态解析单曲专属视觉 MID (vs[1])
     */
    suspend fun getSongVisualMid(songMid: String): String? =
        withContext(Dispatchers.IO) {
            if (songMid.isBlank() || songMid.startsWith("webdav_")) return@withContext null
            visualMidCache[songMid]?.let { return@withContext it }

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
                    visualMidCache[songMid] = visualMid
                    return@withContext visualMid
                }
            } catch (_: Exception) {
            }
            null
        }

    /**
     * 歌曲全局搜索 (基于 AG-1 安全网关)
     */
    suspend fun search(
        query: String,
        page: Int = 1,
        pageSize: Int = 30,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val escapedQuery = Json.encodeToString(query)
            val payload =
                """
                {
                  "music.search.SearchCgiService": {
                    "module": "music.search.SearchCgiService",
                    "method": "DoSearchForQQMusicDesktop",
                    "param": {
                      "query": $escapedQuery,
                      "page_num": $page,
                      "num_per_page": $pageSize,
                      "search_type": 0
                    }
                  }
                }
                """.trimIndent()

            try {
                val respJson = postAg1Gateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val svc = root["music.search.SearchCgiService"]?.jsonObject
                val data = svc?.get("data")?.jsonObject
                val body = data?.get("body")?.jsonObject
                val songObj = body?.get("song")?.jsonObject
                val songList = songObj?.get("list")?.jsonArray ?: return@withContext emptyList()

                songList.mapNotNull { parseSongFromElement(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * 发起带 AG-1 流量加密的安全网关 POST 请求并自动解密返回
     */
    suspend fun postAg1Gateway(jsonPayload: String): String =
        withContext(Dispatchers.IO) {
            val sign = CryptoUtils.computeZzcSign(jsonPayload)
            val encryptedBody = CryptoUtils.encryptAg1Request(jsonPayload)
            val ts = System.currentTimeMillis()
            val url = "$AG1_ENDPOINT?_=$ts&encoding=ag-1&sign=$sign"

            val body = encryptedBody.toRequestBody(FORM_MEDIA_TYPE)
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MelodistTV) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .header("Origin", "https://y.qq.com")

            val cookieHeader = UserSession.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.header("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("AG-1 Gateway error: HTTP ${response.code}")
                }
                val responseBytes = response.body?.bytes() ?: byteArrayOf()
                CryptoUtils.decryptAg1Response(responseBytes)
            }
        }

    /**
     * 发起带通用头与 Cookie 的音乐网关 POST 请求
     */
    suspend fun postGateway(jsonPayload: String): String =
        withContext(Dispatchers.IO) {
            val sign = CryptoUtils.computeZzcSign(jsonPayload)
            val url = "$API_ENDPOINT?_=$sign"

            val body = jsonPayload.toRequestBody(JSON_MEDIA_TYPE)
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MelodistTV) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")

            val cookieHeader = UserSession.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.header("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Gateway error: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }

    /**
     * 发起带通用头与 Cookie 的通用 GET 请求
     */
    suspend fun getUrl(url: String): String =
        withContext(Dispatchers.IO) {
            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MelodistTV) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")

            val cookieHeader = UserSession.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.header("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP error: ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }

    /**
     * 获取“我的喜欢”歌单真实歌曲列表 (dirid = 201)
     */
    suspend fun getFavoriteSongs(
        page: Int = 1,
        pageSize: Int = 50,
    ): List<Song> =
        withContext(Dispatchers.IO) {
            try {
                LoginApiService().ensureMusicKey()
            } catch (_: Exception) {
            }

            val uin = UserSession.profile.uin.ifBlank { "0" }
            val authst = UserSession.profile.musicKey

            val payload =
                """
                {
                  "comm": { "uin": "$uin", "format": "json", "ct": 19, "cv": 1, "authst": "$authst" },
                  "req_fav": {
                    "module": "music.musicasset.PlaylistDetailRead",
                    "method": "GetUniformSongDetailInfo",
                    "param": { "uin": "$uin", "dirid": 201, "bPaged": true, "offset": ${(page - 1) * pageSize}, "size": $pageSize }
                  }
                }
                """.trimIndent()

            try {
                val respJson = postGateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val favData = root["req_fav"]?.jsonObject?.get("data")?.jsonObject ?: return@withContext emptyList()
                val songArray = favData["list"]?.jsonArray ?: return@withContext emptyList()

                val result = mutableListOf<Song>()
                for (elem in songArray) {
                    val rawObj = elem.jsonObject
                    // 兼容 track 嵌套与平铺结构
                    val item = rawObj["track"]?.jsonObject ?: rawObj

                    val songId =
                        item["id"]?.jsonPrimitive?.longOrNull
                            ?: item["songid"]?.jsonPrimitive?.longOrNull
                            ?: item["song_id"]?.jsonPrimitive?.longOrNull
                            ?: continue

                    val songMid =
                        item["mid"]?.jsonPrimitive?.contentOrNull
                            ?: item["songmid"]?.jsonPrimitive?.contentOrNull
                            ?: item["song_mid"]?.jsonPrimitive?.contentOrNull
                            ?: continue

                    val name =
                        item["name"]?.jsonPrimitive?.contentOrNull
                            ?: item["title"]?.jsonPrimitive?.contentOrNull
                            ?: item["songname"]?.jsonPrimitive?.contentOrNull
                            ?: "未知曲目"

                    val singers =
                        item["singer"]
                            ?.jsonArray
                            ?.mapNotNull { sElem ->
                                val sObj = sElem.jsonObject
                                sObj["name"]?.jsonPrimitive?.contentOrNull ?: sObj["singer_name"]?.jsonPrimitive?.contentOrNull
                            }?.joinToString(" / ") ?: "未知歌手"

                    val albumObj = item["album"]?.jsonObject
                    val albumName =
                        albumObj?.get("name")?.jsonPrimitive?.contentOrNull
                            ?: item["albumname"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                    val albumMid =
                        albumObj?.get("mid")?.jsonPrimitive?.contentOrNull
                            ?: item["albummid"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                    val duration = item["interval"]?.jsonPrimitive?.intOrNull ?: 0

                    val coverUrl = getAlbumCoverUrl(albumMid)

                    result.add(
                        Song(
                            songId = songId,
                            songMid = songMid,
                            name = name,
                            singer = singers,
                            album = albumName,
                            albumMid = albumMid,
                            durationSeconds = duration,
                            currentTier = AudioQualityTier.SQ,
                            coverUrl = coverUrl,
                        ),
                    )
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        }

    /**
     * 探测歌曲各音质档位直链并获取最佳可用播放 URL
     */
    suspend fun getPlayUrl(
        songMid: String,
        mediaMid: String = "",
        preferredTier: AudioQualityTier = AudioQualityTier.SQ,
    ): QualityResult =
        withContext(Dispatchers.IO) {
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
                    Triple("req_atmos71", AudioQualityTier.Atmos71, Pair("Q001", ".flac")),
                    Triple("req_atmos51", AudioQualityTier.Atmos51, Pair("Q001", ".flac")),
                    Triple("req_dolby", AudioQualityTier.Dolby, Pair("Q000", ".flac")),
                    Triple("req_premium", AudioQualityTier.Premium, Pair("AI00", ".flac")),
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
                    val hasFileSize = if (fileObj != null) fileSize > 0L else true
                    if (hasFileSize &&
                        !purl.isNullOrBlank() &&
                        purl.length > 5 &&
                        result == 0 &&
                        purl.contains(prefix, ignoreCase = true)
                    ) {
                        // 过滤 Android 系统解码器 (c2.android.vorbis.decoder) 无法解码的 12 声道 Q003 Vorbis 流
                        if (purl.contains("Q003", ignoreCase = true) || purl.endsWith(".ogg", ignoreCase = true)) {
                            continue
                        }
                        availableMap[tier] = sip + purl
                    }
                }

                // 优先检查 preferredTier
                availableMap[preferredTier]?.let { url ->
                    return@withContext QualityResult(url, preferredTier, AudioQualityTier.getBadge(preferredTier))
                }

                // 向下级音质降级
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
                        AudioQualityTier.Atmos71 ->
                            listOf(
                                AudioQualityTier.Atmos51,
                                AudioQualityTier.Dolby,
                                AudioQualityTier.Premium,
                                AudioQualityTier.Master,
                                AudioQualityTier.HiRes,
                                AudioQualityTier.SQ,
                                AudioQualityTier.HQ,
                                AudioQualityTier.Standard,
                            )
                        AudioQualityTier.Atmos51 ->
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

    /**
     * 获取歌曲双语同步歌词
     */
    suspend fun getLyrics(
        songMid: String,
        songId: Long = 0L,
    ): List<LyricLine> =
        withContext(Dispatchers.IO) {
            if (songMid.isBlank() && songId <= 0L) return@withContext emptyList()
            val payload =
                """
                {"comm":{"ct":24,"cv":0},"playLyricInfo":{"module":"music.musichallSong.PlayLyricInfo","method":"GetPlayLyricInfo","param":{"songMID":"$songMid","songID":$songId,"qrc":0,"trans":1,"roma":1,"isHQ":1}}}
                """.trimIndent()

            try {
                val respJson = postGateway(payload)
                val root = Json.parseToJsonElement(respJson).jsonObject
                val data = root["playLyricInfo"]?.jsonObject?.get("data")?.jsonObject

                val b64Lyric = data?.get("lyric")?.jsonPrimitive?.contentOrNull
                val b64Trans = data?.get("trans")?.jsonPrimitive?.contentOrNull

                var rawLyric = decodeBase64(b64Lyric)
                var rawTrans = decodeBase64(b64Trans)

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

                if (rawLyric.isNotBlank()) {
                    LyricParser.parseMergedLyrics(rawLyric, rawTrans)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                // 网关异常时走传统接口降级
                try {
                    if (songMid.isNotBlank()) {
                        val fallback = fetchLegacyLyric(songMid)
                        if (fallback.first.isNotBlank()) {
                            return@withContext LyricParser.parseMergedLyrics(fallback.first, fallback.second)
                        }
                    }
                } catch (_: Exception) {
                }
                emptyList()
            }
        }

    private suspend fun fetchLegacyLyric(songMid: String): Pair<String, String> =
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
}
