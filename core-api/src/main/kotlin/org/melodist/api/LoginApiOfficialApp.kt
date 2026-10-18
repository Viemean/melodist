package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * 向 QQ 音乐网关请求官方 App 扫码登录二维码及唯一会话 ID。
 *
 * @return 包含二维码二进制数据及 qrcodeID 的 [QrCodeInfo]
 * @throws IOException 当网关响应异常或未能解析二维码图像 Base64 时抛出
 */
internal suspend fun LoginApiService.fetchOfficialAppQrCodeImpl(): QrCodeInfo =
    withContext(Dispatchers.IO) {
        val payload =
            """
            {"comm":{"ct":11,"cv":14090008},"req_0":{"module":"music.login.LoginServer","method":"CreateQRCode","param":{"tmeAppID":"qqmusic","ct":11,"cv":14090008}}}
            """.trimIndent()

        val request =
            Request
                .Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                .header("User-Agent", "QQMusic 14090008(android 14)")
                .build()

        client.newCall(request).execute().use { resp ->
            val json = resp.body.string()
            val jsonElement = Json.parseToJsonElement(json).jsonObject
            val data =
                jsonElement["req_0"]?.jsonObject?.get("data")?.jsonObject
                    ?: throw IOException("官方扫码响应异常")

            val dataUrl = data["qrcode"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val identifier = data["qrcodeID"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val comma = dataUrl.indexOf(',')
            if (comma < 0 || identifier.isEmpty()) throw IOException("官方登录二维码不完整")

            val bytes =
                java.util.Base64
                    .getDecoder()
                    .decode(dataUrl.substring(comma + 1))

            cancelOfficialAppSession(identifier)
            val session =
                OfficialAppLoginSession(
                    qrcodeId = identifier,
                    client = client,
                    onExchange = { uin, qid, token ->
                        exchangeOfficialAppLogin(uin, qid, token)
                    },
                )
            officialAppSessions[identifier] = session
            session.start()

            QrCodeInfo(bytes, identifier, "image/png")
        }
    }

/**
 * 轮询获取官方 App 扫码后台会话当前的登录授权状态。
 *
 * @param identifier 官方扫码会话 qrcodeID
 * @return 包含扫码授权状态的 [PollResult]；会话已失效时返回错误状态
 */
internal fun LoginApiService.pollOfficialAppQrStatusImpl(identifier: String): PollResult {
    val session = officialAppSessions[identifier] ?: return PollResult(QrStatus.Error, "会话已失效")
    val current = session.resultRef.get()
    if (current.status == QrStatus.Success ||
        current.status == QrStatus.Expired ||
        current.status == QrStatus.Canceled
    ) {
        officialAppSessions.remove(identifier)
        session.close()
    }
    return current
}

/**
 * 取消并主动释放指定的官方 App 扫码长轮询后台会话。
 *
 * @param identifier 目标会话 qrcodeID
 */
internal fun LoginApiService.cancelOfficialAppSessionImpl(identifier: String) {
    officialAppSessions.remove(identifier)?.close()
}

private suspend fun LoginApiService.exchangeOfficialAppLogin(
    uin: String,
    qrcodeId: String,
    token: String,
): Boolean =
    withContext(Dispatchers.IO) {
        val numericUin = uin.trimStart('o').toLongOrNull() ?: 0L
        val payload =
            """
            {"comm":{"ct":11,"cv":14090008,"v":14090008,"chid":"10003505","tmeAppID":"qqmusic","tmeLoginType":6},"req_0":{"module":"music.login.LoginServer","method":"Login","param":{"musicid":$numericUin,"qrCodeID":"$qrcodeId","token":"$token"}}}
            """.trimIndent()

        val request =
            Request
                .Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                .header("User-Agent", "QQMusic 14090008(android 14)")
                .build()

        try {
            client.newCall(request).execute().use { resp ->
                val json = resp.body.string()
                val jsonElement = Json.parseToJsonElement(json).jsonObject
                val req0 = jsonElement["req_0"]?.jsonObject ?: return@withContext false
                if (req0["code"]?.jsonPrimitive?.intOrNull != 0) {
                    ApiLogger.e("LoginApiService", "exchangeOfficialAppLogin rejected: $json")
                    return@withContext false
                }

                val data = req0["data"]?.jsonObject ?: return@withContext false
                val musicId =
                    data["str_musicid"]?.jsonPrimitive?.contentOrNull
                        ?: data["musicid"]?.jsonPrimitive?.contentOrNull
                        ?: uin
                val musicKey = data["musickey"]?.jsonPrimitive?.contentOrNull ?: token
                val nick = data["nick"]?.jsonPrimitive?.contentOrNull ?: "QQ音乐用户_$musicId"
                val rawLoginType = data["loginType"]?.jsonPrimitive?.intOrNull ?: 6

                val cookies =
                    mutableMapOf(
                        "tmeLoginType" to rawLoginType.toString(),
                        "qqmusic_uin" to musicId,
                        "qqmusic_key" to musicKey,
                        "qm_keyst" to musicKey,
                        "musickey" to musicKey,
                        "uin" to musicId,
                        "musicid" to musicId,
                    )
                data["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { cookies["refresh_token"] = it }
                data["refresh_key"]?.jsonPrimitive?.contentOrNull?.let { cookies["refresh_key"] = it }
                data["openid"]?.jsonPrimitive?.contentOrNull?.let { cookies["openid"] = it }
                data["access_token"]?.jsonPrimitive?.contentOrNull?.let { cookies["access_token"] = it }
                data["encryptUin"]?.jsonPrimitive?.contentOrNull?.let { cookies["euin"] = it }

                val rawAvatar =
                    data["headimgurl"]?.jsonPrimitive?.contentOrNull
                        ?: data["avatar"]?.jsonPrimitive?.contentOrNull
                        ?: data["pic"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                val avatar = if (rawAvatar.isNotBlank()) normalizeHighResAvatar(rawAvatar, isQqAccount = rawLoginType != 1) else ""

                UserSession.update(
                    uin = musicId,
                    nick = nick,
                    musicKey = musicKey,
                    cookies = cookies.toMap(),
                    avatarUrl = avatar,
                )
                true
            }
        } catch (e: Exception) {
            ApiLogger.e("LoginApiService", "exchangeOfficialAppLogin exception", e)
            false
        }
    }

/**
 * 使用官方 App 扫码登录下发的 refresh_token 与 refresh_key 刷新 musickey 鉴权凭证。
 *
 * @param musicId 用户识别标识（musicid 或 uin）
 * @param refreshToken 官方登录下发的长效刷新令牌
 * @param refreshKey 官方登录下发的刷新密钥
 * @param musicKey 当前生效的旧 musickey
 * @param loginType 账号登录体系类型
 * @param openid 关联 openid 标识
 * @param accessToken 关联 access_token
 * @return 刷新并写入新凭证成功返回 `true`，失败返回 `false`
 */
internal suspend fun LoginApiService.refreshOfficialAppTokenImpl(
    musicId: String,
    refreshToken: String,
    refreshKey: String = "",
    musicKey: String = "",
    loginType: Int = 2,
    openid: String = "",
    accessToken: String = "",
): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val numericUin = musicId.trimStart('o').toLongOrNull() ?: 0L
            val actualLoginType = if (loginType in listOf(1, 2)) loginType else 2
            val paramObj =
                buildJsonObject {
                    put("musicid", numericUin)
                    put("refresh_token", refreshToken)
                    if (refreshKey.isNotEmpty()) put("refresh_key", refreshKey)
                    if (musicKey.isNotEmpty()) put("musickey", musicKey)
                    if (openid.isNotEmpty()) put("openid", openid)
                    if (accessToken.isNotEmpty()) put("access_token", accessToken)
                    put("loginMode", 2)
                }
            val commObj =
                buildJsonObject {
                    put("ct", 11)
                    put("cv", 14090008)
                    put("v", 14090008)
                    put("chid", "10003505")
                    put("tmeAppID", "qqmusic")
                    put("tmeLoginType", actualLoginType)
                }
            val payload =
                buildJsonObject {
                    put("comm", commObj)
                    put(
                        "req_0",
                        buildJsonObject {
                            put("module", "music.login.LoginServer")
                            put("method", "Login")
                            put("param", paramObj)
                        },
                    )
                }.toString()

            val request =
                Request
                    .Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                    .header("User-Agent", "QQMusic 14090008(android 14)")
                    .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val json = resp.body.string()
                val jsonElement = Json.parseToJsonElement(json).jsonObject
                val req0 = jsonElement["req_0"]?.jsonObject ?: return@withContext false
                if (req0["code"]?.jsonPrimitive?.intOrNull != 0) {
                    ApiLogger.w("LoginApiService", "refreshOfficialAppToken rejected: $json")
                    return@withContext false
                }

                val data = req0["data"]?.jsonObject ?: return@withContext false
                val newMusicKey = data["musickey"]?.jsonPrimitive?.contentOrNull ?: return@withContext false
                if (newMusicKey.isEmpty()) return@withContext false

                val cookies = UserSession.profile.cookies.toMutableMap()
                cookies["musickey"] = newMusicKey
                cookies["qm_keyst"] = newMusicKey
                cookies["qqmusic_key"] = newMusicKey
                data["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { cookies["refresh_token"] = it }
                data["refresh_key"]?.jsonPrimitive?.contentOrNull?.let { cookies["refresh_key"] = it }
                data["openid"]?.jsonPrimitive?.contentOrNull?.let { cookies["openid"] = it }
                data["access_token"]?.jsonPrimitive?.contentOrNull?.let { cookies["access_token"] = it }

                UserSession.update(
                    uin = UserSession.profile.uin,
                    nick = UserSession.profile.nick,
                    musicKey = newMusicKey,
                    cookies = cookies.toMap(),
                    avatarUrl = UserSession.profile.avatarUrl,
                    isVip = UserSession.profile.isVip,
                )
                true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ApiLogger.w("LoginApiService", "refreshOfficialAppToken exception", e)
            false
        }
    }

/**
 * 为独立的 PlaybackCredentials 凭据执行网关续签。
 *
 * @param creds 待续签的播放凭据
 * @return 续签成功后的新凭据，失败时返回 null
 */
suspend fun LoginApiService.refreshPlaybackCredentialsImpl(creds: PlaybackCredentials): PlaybackCredentials? =
    withContext(Dispatchers.IO) {
        val cookies = creds.cookies
        val isWeChat = cookies["tmeLoginType"] == "1"
        val openid = cookies["psrf_qqopenid"] ?: if (!isWeChat) cookies["openid"] else null
        val accessToken = cookies["psrf_qqaccess_token"] ?: if (!isWeChat) cookies["access_token"] else null

        // 1. 若具备 QQ 互联扫码续签票据 (openid + accessToken)
        if (!openid.isNullOrEmpty() && !accessToken.isNullOrEmpty()) {
            val refreshed = refreshQQPlaybackCredentials(creds, openid, accessToken)
            if (refreshed != null) return@withContext refreshed
        }

        // 2. 若具备官方 App 扫码续签票据 (refresh_token)
        val refreshToken = cookies["refresh_token"]
        val musicId = creds.uin.ifBlank { cookies["musicid"] ?: cookies["uin"] ?: "" }
        if (!refreshToken.isNullOrEmpty() && musicId.isNotBlank()) {
            val refreshed = refreshOfficialPlaybackCredentials(creds, musicId, refreshToken)
            if (refreshed != null) return@withContext refreshed
        }
        null
    }

internal suspend fun LoginApiService.refreshOfficialPlaybackCredentials(
    creds: PlaybackCredentials,
    musicId: String,
    refreshToken: String,
): PlaybackCredentials? =
    withContext(Dispatchers.IO) {
        val cookies = creds.cookies
        try {
            val numericUin = musicId.trimStart('o').toLongOrNull() ?: 0L
            val refreshKey = cookies["refresh_key"].orEmpty()
            val currentMusicKey = creds.musicKey.ifBlank { cookies["musickey"] ?: cookies["qm_keyst"] ?: "" }
            val openid = cookies["psrf_qqopenid"] ?: cookies["openid"].orEmpty()
            val accessToken = cookies["psrf_qqaccess_token"] ?: cookies["access_token"].orEmpty()
            val loginType = cookies["tmeLoginType"]?.toIntOrNull() ?: 2
            val actualLoginType = if (loginType in listOf(1, 2)) loginType else 2

            val paramObj =
                buildJsonObject {
                    put("musicid", numericUin)
                    put("refresh_token", refreshToken)
                    if (refreshKey.isNotEmpty()) put("refresh_key", refreshKey)
                    if (currentMusicKey.isNotEmpty()) put("musickey", currentMusicKey)
                    if (openid.isNotEmpty()) put("openid", openid)
                    if (accessToken.isNotEmpty()) put("access_token", accessToken)
                    put("loginMode", 2)
                }
            val commObj =
                buildJsonObject {
                    put("ct", 11)
                    put("cv", 14090008)
                    put("v", 14090008)
                    put("chid", "10003505")
                    put("tmeAppID", "qqmusic")
                    put("tmeLoginType", actualLoginType)
                }
            val payload =
                buildJsonObject {
                    put("comm", commObj)
                    put(
                        "req_0",
                        buildJsonObject {
                            put("module", "music.login.LoginServer")
                            put("method", "Login")
                            put("param", paramObj)
                        },
                    )
                }.toString()

            val request =
                Request
                    .Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                    .header("User-Agent", "QQMusic 14090008(android 14)")
                    .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = resp.body.string()
                val jsonElement = Json.parseToJsonElement(json).jsonObject
                val req0 = jsonElement["req_0"]?.jsonObject ?: return@withContext null
                if (req0["code"]?.jsonPrimitive?.intOrNull != 0) {
                    ApiLogger.w("LoginApiService", "refreshOfficialPlaybackCredentials rejected: $json")
                    return@withContext null
                }
                val data = req0["data"]?.jsonObject ?: return@withContext null
                val newMusicKey = data["musickey"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                if (newMusicKey.isEmpty()) return@withContext null

                val updatedCookies = cookies.toMutableMap()
                updatedCookies["musickey"] = newMusicKey
                updatedCookies["qm_keyst"] = newMusicKey
                updatedCookies["qqmusic_key"] = newMusicKey
                data["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { updatedCookies["refresh_token"] = it }
                data["refresh_key"]?.jsonPrimitive?.contentOrNull?.let { updatedCookies["refresh_key"] = it }
                data["openid"]?.jsonPrimitive?.contentOrNull?.let { updatedCookies["openid"] = it }
                data["access_token"]?.jsonPrimitive?.contentOrNull?.let { updatedCookies["access_token"] = it }

                creds.copy(
                    musicKey = newMusicKey,
                    cookies = updatedCookies.toMap(),
                    updateTime = System.currentTimeMillis(),
                    isRevoked = false,
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ApiLogger.w("LoginApiService", "refreshOfficialPlaybackCredentials exception", e)
            null
        }
    }

internal class OfficialAppLoginSession(
    val qrcodeId: String,
    private val client: OkHttpClient,
    private val onExchange: suspend (String, String, String) -> Boolean,
) {
    val resultRef = AtomicReference(PollResult(QrStatus.Waiting, "等待手机 QQ 音乐扫码..."))
    private var webSocket: WebSocket? = null
    private val isClosed = AtomicBoolean(false)

    fun start() {
        connect(null)
    }

    private fun connect(serverReference: String?) {
        if (isClosed.get()) return
        val path = if (serverReference.isNullOrBlank()) "" else "/$serverReference"
        val request =
            Request
                .Builder()
                .url("wss://mu.y.qq.com:443/ws/handshake$path")
                .header("Origin", "https://y.qq.com")
                .header("Referer", "https://y.qq.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
                ).build()

        webSocket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        val clientId = "${System.currentTimeMillis()}${Random.nextInt(1000, 9999)}"
                        val connectPacket =
                            MqttProtocol.buildConnectPacket(
                                clientId = clientId,
                                authMethod = "pass",
                                userProperties =
                                    listOf(
                                        "tmeAppID" to "qqmusic",
                                        "business" to "management",
                                        "hashTag" to qrcodeId,
                                        "clientTag" to "management.user",
                                        "userID" to qrcodeId,
                                    ),
                            )
                        webSocket.send(connectPacket.toByteString())
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) {
                        if (isClosed.get()) return
                        val msg = MqttProtocol.parsePacket(bytes.toByteArray()) ?: return
                        when {
                            msg.serverReference != null -> {
                                webSocket.close(1000, "redirect")
                                connect(msg.serverReference)
                            }

                            msg.type == 0x20.toByte() -> {
                                val subPacket =
                                    MqttProtocol.buildSubscribePacket(
                                        packetId = 1,
                                        topic = "management.qrcode_login/$qrcodeId",
                                        userProperties =
                                            listOf(
                                                "authorization" to "tmelogin",
                                                "pubsub" to "unicast",
                                            ),
                                    )
                                webSocket.send(subPacket.toByteString())
                            }

                            msg.type == (0x90).toByte() -> {
                                resultRef.set(PollResult(QrStatus.Waiting, "请使用手机 QQ 音乐扫码"))
                            }

                            msg.payload != null -> {
                                handleEvent(msg.userProperties["type"].orEmpty(), msg.payload)
                            }
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        if (!isClosed.get()) {
                            resultRef.set(PollResult(QrStatus.Error, "扫码连接异常: ${t.message ?: "网络错误"}"))
                        }
                    }
                },
            )
    }

    private fun handleEvent(
        type: String,
        payload: ByteArray,
    ) {
        val payloadStr = String(payload, Charsets.UTF_8)
        ApiLogger.d("LoginApiService", "OfficialApp handleEvent: type=$type, payload=$payloadStr")
        when (type) {
            "scanned" -> {
                resultRef.set(PollResult(QrStatus.Confirming, "已扫码，请在手机端确认授权"))
            }

            "cookies" -> {
                try {
                    val json = Json.parseToJsonElement(payloadStr).jsonObject
                    val cookiesObj = json["cookies"]?.jsonObject
                    val uin =
                        cookiesObj
                            ?.get("qqmusic_uin")
                            ?.let {
                                if (it is kotlinx.serialization.json.JsonObject) it["value"]?.jsonPrimitive?.contentOrNull else it.jsonPrimitive.contentOrNull
                            }.orEmpty()
                    val key =
                        cookiesObj
                            ?.get("qqmusic_key")
                            ?.let {
                                if (it is kotlinx.serialization.json.JsonObject) it["value"]?.jsonPrimitive?.contentOrNull else it.jsonPrimitive.contentOrNull
                            }.orEmpty()

                    if (uin.isNotEmpty() && key.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val success = onExchange(uin, qrcodeId, key)
                            ApiLogger.d("LoginApiService", "OfficialApp onExchange result=$success for uin=$uin")
                            if (success) {
                                resultRef.set(PollResult(QrStatus.Success, "登录成功"))
                            } else {
                                resultRef.set(PollResult(QrStatus.Error, "凭据换取失败"))
                            }
                            close()
                        }
                    } else {
                        resultRef.set(PollResult(QrStatus.Error, "登录数据不完整"))
                        close()
                    }
                } catch (e: Exception) {
                    resultRef.set(PollResult(QrStatus.Error, "解析登录数据失败: ${e.message}"))
                    close()
                }
            }

            "canceled" -> {
                resultRef.set(PollResult(QrStatus.Canceled, "用户取消授权"))
                close()
            }

            "timeout" -> {
                resultRef.set(PollResult(QrStatus.Expired, "二维码已过期，点击刷新"))
                close()
            }

            "loginFailed" -> {
                resultRef.set(PollResult(QrStatus.Error, "授权失败"))
                close()
            }
        }
    }

    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                webSocket?.close(1000, "close")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ApiLogger.w("QrStatus", "Operation failed", e)
            }
            webSocket = null
        }
    }
}
