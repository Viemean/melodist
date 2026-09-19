package org.melodist.api.acr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.melodist.api.MusicApiService
import org.melodist.model.Song
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class AcousticRecognizeResult(
    val success: Boolean,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val song: Song? = null,
    val offsetSeconds: Double = 0.0,
    val errorMessage: String = "",
)

class AcousticRecognizeClient(
    private val client: OkHttpClient = sharedClient,
) {
    companion object {
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
        }

        private const val PROTOCOL_VERSION = 201506
        private const val ENDPOINT = "http://c.y.qq.com/youtu/humming/search"
        private val MEDIA_TYPE_FORM = "application/x-www-form-urlencoded".toMediaType()

        private val OBF_SOURCE = byteArrayOf(41, 42, 40, 5, 34, 51, 59, 53, 55, 51, 5, 41, 42, 63, 63, 62, 5, 59, 52, 62, 40, 53, 51, 62)
        private val OBF_SALT =
            byteArrayOf(
                41,
                42,
                40,
                5,
                34,
                51,
                59,
                53,
                55,
                51,
                5,
                41,
                42,
                63,
                63,
                62,
                5,
                59,
                52,
                62,
                40,
                53,
                51,
                62,
                59,
                110,
                111,
                59,
                107,
                56,
            )
        private val OBF_AES_KEY = byteArrayOf(41, 42, 40, 5, 98, 59, 104, 57, 62, 63, 59, 56, 109, 56, 98, 107)

        private val channelSource by lazy {
            deobfuscate(OBF_SOURCE)
        }
        private val channelSalt by lazy {
            deobfuscate(OBF_SALT)
        }
        private val channelAesKey by lazy {
            ByteArray(OBF_AES_KEY.size) { i -> (OBF_AES_KEY[i].toInt() xor 0x5A).toByte() }
        }

        private fun deobfuscate(bytes: ByteArray): String {
            val res = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 0x5A).toByte() }
            return String(res, Charsets.UTF_8)
        }

        private fun md5Hex(str: String): String {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(str.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(String.format("%02x", b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }

    /**
     * 上传声学特征数据至云端接口进行检索识别
     */
    suspend fun search(
        feature: AcousticFeature,
        sessionId: Long = System.currentTimeMillis(),
    ): AcousticRecognizeResult =
        withContext(Dispatchers.IO) {
            if (feature.data.isEmpty()) {
                return@withContext AcousticRecognizeResult(
                    success = false,
                    errorMessage = "特征数据为空",
                )
            }

            try {
                val timestamp = System.currentTimeMillis()
                val fpType = feature.featureType + 1

                // 1. 生成时间戳签名 MD5
                val signStr = "$channelSalt$timestamp"
                val veriStr = md5Hex(signStr)

                // 2. 构造明文控制头 (以 \0 结尾)
                val header =
                    String.format(
                        Locale.US,
                        "v=%d&source=%s&time=%d&veri_str=%s&cmd=1&info=%.1f,%d,10306&type=0&session_id=%d&feature_type=%d&confidence=%.1f\u0000",
                        PROTOCOL_VERSION,
                        channelSource,
                        timestamp,
                        veriStr,
                        feature.duration,
                        feature.data.size,
                        sessionId,
                        fpType,
                        feature.confidence,
                    )
                val headerBytes = header.toByteArray(Charsets.UTF_8)

                // 3. 拼接 Payload: Header + FeatureBytes
                val rawPayload = ByteArray(headerBytes.size + feature.data.size)
                System.arraycopy(headerBytes, 0, rawPayload, 0, headerBytes.size)
                System.arraycopy(feature.data, 0, rawPayload, headerBytes.size, feature.data.size)

                // 4. AES-128-ECB 加密
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                val keySpec = SecretKeySpec(channelAesKey, "AES")
                cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                val encryptedBody = cipher.doFinal(rawPayload)

                // 5. 构建 HTTP POST 请求
                val requestUrl = "$ENDPOINT?sessionid=$sessionId&recognizetype=1&fpType=$fpType"
                val request =
                    Request
                        .Builder()
                        .url(requestUrl)
                        .addHeader("User-Agent", "MusicRecognition 34}(android 10)")
                        .addHeader("AppId", "85")
                        .addHeader("Cookie", "uin=; ct=3003; cv=10306; recognizetype=1")
                        .post(encryptedBody.toRequestBody(MEDIA_TYPE_FORM))
                        .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext AcousticRecognizeResult(
                            success = false,
                            errorMessage = "HTTP 请求失败: ${response.code}",
                        )
                    }

                    val bodyStr = response.body.string()
                    parseResponse(bodyStr)
                }
            } catch (e: Exception) {
                AcousticRecognizeResult(
                    success = false,
                    errorMessage = "网络异常: ${e.message}",
                )
            }
        }

    /**
     * 解析服务器返回的 JSON 报文
     */
    fun parseResponse(jsonString: String): AcousticRecognizeResult {
        return try {
            val root = Json.parseToJsonElement(jsonString).jsonObject
            val ret = root["ret"]?.jsonPrimitive?.intOrNull ?: -1
            if (ret != 0) {
                return AcousticRecognizeResult(
                    success = false,
                    errorMessage = "服务器未命中歌曲特征",
                )
            }

            // 提取时间偏移量 offset
            var offset = 0.0
            root["results"]?.jsonArray?.firstOrNull()?.jsonObject?.let { firstRes ->
                firstRes["offset"]?.jsonPrimitive?.doubleOrNull?.let { offset = it }
            }

            // 提取歌曲元数据 (songlist)
            val songItem = root["songlist"]?.jsonArray?.firstOrNull()
            if (songItem == null) {
                return AcousticRecognizeResult(
                    success = false,
                    offsetSeconds = offset,
                    errorMessage = "返回数据中未包含歌曲信息",
                )
            }

            val parsedSong = MusicApiService.parseSongFromElement(songItem)
            if (parsedSong == null) {
                return AcousticRecognizeResult(
                    success = false,
                    offsetSeconds = offset,
                    errorMessage = "解析歌曲数据失败",
                )
            }

            AcousticRecognizeResult(
                success = true,
                title = parsedSong.name,
                artist = parsedSong.singer,
                album = parsedSong.album,
                song = parsedSong,
                offsetSeconds = offset,
                errorMessage = "",
            )
        } catch (e: Exception) {
            AcousticRecognizeResult(
                success = false,
                errorMessage = "解析响应失败: ${e.message}",
            )
        }
    }
}
