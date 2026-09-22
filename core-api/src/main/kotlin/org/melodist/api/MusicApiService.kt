package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.IOException
import java.util.concurrent.TimeUnit

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

        fun getSingerAvatarUrl(singerMid: String): String = MusicApiVisual.getSingerAvatarUrl(singerMid)

        fun getSingerAvatarCandidates(singerMid: String): List<String> = MusicApiVisual.getSingerAvatarCandidates(singerMid)

        fun getAlbumCoverCandidates(albumMid: String): List<String> = MusicApiVisual.getAlbumCoverCandidates(albumMid)

        fun getAlbumCoverUrl(albumMid: String): String = MusicApiVisual.getAlbumCoverUrl(albumMid)

        fun getSingleCoverCandidates(visualMid: String): List<String> = MusicApiVisual.getSingleCoverCandidates(visualMid)

        fun getSingleCoverUrl(visualMid: String): String = MusicApiVisual.getSingleCoverUrl(visualMid)
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
                val responseBytes = response.body.bytes()
                CryptoUtils.decryptAg1Response(responseBytes)
            }
        }

    /**
     * 发起带通用头与 Cookie 的音乐网关 POST 请求
     */
    suspend fun postGateway(
        jsonPayload: String,
        customCookieHeader: String? = null,
    ): String =
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

            val cookieHeader = customCookieHeader ?: UserSession.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.header("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Gateway error: HTTP ${response.code}")
                }
                response.body.string()
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
                response.body.string()
            }
        }

    /**
     * 安全地确保 MusicKey 有效，自动透传协程取消并记录日志
     */
    suspend fun ensureMusicKeySafe(): Boolean =
        try {
            LoginApiService().ensureMusicKey()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ApiLogger.w("MusicApiService", "Failed to ensure music key", e)
            false
        }
}
