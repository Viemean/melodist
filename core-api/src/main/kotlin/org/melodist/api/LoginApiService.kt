package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

enum class QrStatus {
    Waiting,
    Confirming,
    Success,
    Expired,
    Canceled,
    Error,
}

data class QrCodeInfo(
    val imageBytes: ByteArray,
    val identifier: String,
    val mimeType: String = "image/png",
)

data class PollResult(
    val status: QrStatus,
    val message: String,
)

class LoginApiService(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
) {
    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SINGLE_QUOTE_PATTERN = Pattern.compile("'([^']*)'")
        private val WX_STATUS_PATTERN = Pattern.compile("window\\.wx_errcode=(\\d+);(?:window\\.wx_code='([^']*)';)?")
        private val WX_UUID_PATTERN = Pattern.compile("connect/qrcode/([a-zA-Z0-9_-]+)")
        private val UIN_QUERY_PATTERN = Pattern.compile("[?&]uin=([^&]+)")
        private val CODE_PATTERN = Pattern.compile("[?&]code=([^&\\s\"']+)")
    }

    // ================== QQ 扫码登录 ==================

    suspend fun fetchQqQrCode(): QrCodeInfo =
        withContext(Dispatchers.IO) {
            val url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=8&d=72&v=4&t=${Math.random()}&daid=383&pt_3rd_aid=100497308"
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Referer", "https://xui.ptlogin2.qq.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to fetch QQ QR code: ${response.code}")
                val qrsig =
                    extractCookie(response.headers("Set-Cookie"), "qrsig")
                        ?: throw IOException("QQ login response missing qrsig")
                val bytes = response.body?.bytes() ?: throw IOException("Empty QR code response")
                QrCodeInfo(bytes, qrsig, "image/png")
            }
        }

    suspend fun pollQqQrStatus(qrsig: String): PollResult =
        withContext(Dispatchers.IO) {
            val ptqrToken = hashPtqrToken(qrsig)
            val ts = System.currentTimeMillis()
            val url =
                "https://ssl.ptlogin2.qq.com/ptqrlogin?u1=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump" +
                    "&ptqrtoken=$ptqrToken&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052&action=0-0-$ts" +
                    "&js_ver=20102616&js_type=1&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1"

            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Cookie", "qrsig=$qrsig;")
                    .header("Referer", "https://xui.ptlogin2.qq.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val matcher = SINGLE_QUOTE_PATTERN.matcher(text)
                val matches = mutableListOf<String>()
                while (matcher.find()) {
                    matches.add(matcher.group(1))
                }

                if (matches.size < 5) {
                    return@withContext PollResult(QrStatus.Error, "状态响应解析异常")
                }

                val code = matches[0].toIntOrNull() ?: -1
                val message = matches[4]

                when (code) {
                    0 -> {
                        val redirectUrl = matches[2]
                        val nick = if (matches.size >= 6) matches[5] else ""
                        exchangeQqCookies(redirectUrl, qrsig, nick)
                        PollResult(QrStatus.Success, "登录成功")
                    }
                    66 -> PollResult(QrStatus.Waiting, "等待手机 QQ 扫码...")
                    67 -> PollResult(QrStatus.Confirming, "已扫码，请在手机上确认授权...")
                    65 -> PollResult(QrStatus.Expired, "二维码已失效")
                    68 -> PollResult(QrStatus.Canceled, "已取消登录")
                    else -> PollResult(QrStatus.Error, message)
                }
            }
        }

    private suspend fun exchangeQqCookies(
        redirectUrl: String,
        qrsig: String,
        nick: String,
    ) = withContext(Dispatchers.IO) {
        val cookies = mutableMapOf<String, String>()
        var uin = ""

        val uinMatcher = UIN_QUERY_PATTERN.matcher(redirectUrl)
        if (uinMatcher.find()) {
            uin = uinMatcher.group(1).trimStart('o')
        }

        val request =
            Request
                .Builder()
                .url(redirectUrl)
                .header("Cookie", "qrsig=$qrsig;")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

        var jumpUrl: String? = null
        try {
            client.newCall(request).execute().use { resp ->
                resp.headers("Set-Cookie").forEach { sc ->
                    if (!sc.contains("Max-Age=0") && !sc.contains("1970 00:00:00")) {
                        val pair = sc.substringBefore(";").split("=", limit = 2)
                        if (pair.size == 2) {
                            val k = pair[0].trim()
                            val v = pair[1].trim()
                            if (v.isNotEmpty()) {
                                cookies[k] = v
                                if (uin.isEmpty() &&
                                    (
                                        k.equals("uin", ignoreCase = true) ||
                                            k.equals("p_uin", ignoreCase = true) ||
                                            k.equals("pt2gguin", ignoreCase = true)
                                    )
                                ) {
                                    uin = v.trimStart('o')
                                }
                            }
                        }
                    }
                }
                jumpUrl = resp.header("Location")
            }

            // 关键：跟进 302 跳转 Location，p_skey 和会话凭据在此下发
            if (!jumpUrl.isNullOrBlank()) {
                val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val jumpReq =
                    Request
                        .Builder()
                        .url(jumpUrl!!)
                        .header("Cookie", cookieHeader)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()

                client.newCall(jumpReq).execute().use { jumpResp ->
                    jumpResp.headers("Set-Cookie").forEach { sc ->
                        if (!sc.contains("Max-Age=0") && !sc.contains("1970 00:00:00")) {
                            val pair = sc.substringBefore(";").split("=", limit = 2)
                            if (pair.size == 2) {
                                val k = pair[0].trim()
                                val v = pair[1].trim()
                                if (v.isNotEmpty()) {
                                    cookies[k] = v
                                    if (uin.isEmpty() &&
                                        (
                                            k.equals("uin", ignoreCase = true) ||
                                                k.equals("p_uin", ignoreCase = true) ||
                                                k.equals("pt2gguin", ignoreCase = true)
                                        )
                                    ) {
                                        uin = v.trimStart('o')
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (uin.isEmpty()) {
            uin = cookies["uin"]?.trimStart('o')
                ?: cookies["p_uin"]?.trimStart('o')
                ?: cookies["pt2gguin"]?.trimStart('o')
                ?: ""
        }

        val skey = cookies["skey"] ?: cookies["p_skey"] ?: cookies["qqmusic_key"] ?: ""
        if (uin.isNotEmpty()) {
            cookies["uin"] = uin
            cookies["qqmusic_uin"] = uin
        }
        if (skey.isNotEmpty()) {
            cookies["qqmusic_key"] = skey
        }

        val defaultAvatar =
            if (uin.isNotEmpty() && uin.all { it.isDigit() }) {
                "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=640"
            } else {
                ""
            }

        UserSession.update(
            uin = uin,
            nick = nick.ifBlank { if (uin.isNotEmpty()) "QQ用户_$uin" else "已登录用户" },
            musicKey = skey,
            cookies = cookies,
            avatarUrl = defaultAvatar,
        )

        // 第二阶段：自动通过 OAuth2 换取专属 musickey 完整 VIP 凭据
        exchangeOAuthForMusicKey(cookies)
    }

    private fun getACSRFToken(pSkey: String): Int {
        var hash = 5381
        for (ch in pSkey) {
            hash += (hash shl 5) + ch.code
        }
        return hash and 0x7fffffff
    }

    suspend fun exchangeOAuthForMusicKey(cookies: MutableMap<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            val pSkey = cookies["p_skey"] ?: cookies["skey"] ?: return@withContext false
            val gtk = getACSRFToken(pSkey)

            try {
                val formBody =
                    FormBody
                        .Builder()
                        .add("response_type", "code")
                        .add("client_id", "100497308")
                        .add("redirect_uri", "https://y.qq.com/wk_v17/common_login.html?type=QQ&&redirect=")
                        .add("scope", "get_user_info")
                        .add("state", "y_new.top.pop.logout")
                        .add("switch", "")
                        .add("from_ptlogin", "1")
                        .add("src", "1")
                        .add("update_auth", "1")
                        .add("openapi", "8090_1010_1030_1050")
                        .add("g_tk", gtk.toString())
                        .build()

                val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val authReq =
                    Request
                        .Builder()
                        .url("https://graph.qq.com/oauth2.0/authorize")
                        .post(formBody)
                        .header("Cookie", cookieHeader)
                        .header(
                            "Referer",
                            "https://graph.qq.com/oauth2.0/show?which=Login&display=pc&response_type=code&client_id=100497308&redirect_uri=https%3A%2F%2Fy.qq.com%2Fwk_v17%2Fcommon_login.html%3Ftype%3DQQ%26%26redirect%3D&state=y_new.top.pop.logout&display=pc&scope=get_user_info",
                        ).build()

                var code = ""
                client.newCall(authReq).execute().use { authResp ->
                    val location = authResp.header("Location")
                    if (!location.isNullOrEmpty()) {
                        val m = CODE_PATTERN.matcher(location)
                        if (m.find()) {
                            code = m.group(1)
                        }
                    }
                    if (code.isEmpty()) {
                        val body = authResp.body?.string().orEmpty()
                        val m = CODE_PATTERN.matcher(body)
                        if (m.find()) {
                            code = m.group(1)
                        }
                    }
                }

                if (code.isEmpty()) {
                    return@withContext false
                }

                val payload =
                    """
                    {"comm":{"ct":19,"cv":1,"tmeLoginType":"1"},"login":{"module":"QQConnectLogin.LoginServer","method":"QQLogin","param":{"onlyNeedAccessToken":0,"forceRefreshToken":0,"appid":100497308,"code":"$code"}}}
                    """.trimIndent()

                val loginReq =
                    Request
                        .Builder()
                        .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                        .post(payload.toRequestBody(JSON_TYPE))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()

                client.newCall(loginReq).execute().use { loginResp ->
                    val json = loginResp.body?.string().orEmpty()
                    val jsonElement = Json.parseToJsonElement(json).jsonObject
                    val loginObj = jsonElement["login"]?.jsonObject ?: return@withContext false
                    val loginData = loginObj["data"]?.jsonObject ?: return@withContext false

                    val musicId =
                        loginData["str_musicid"]?.jsonPrimitive?.contentOrNull
                            ?: loginData["musicid"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                    val musicKey = loginData["musickey"]?.jsonPrimitive?.contentOrNull ?: ""
                    val openid = loginData["openid"]?.jsonPrimitive?.contentOrNull ?: ""
                    val accessToken = loginData["access_token"]?.jsonPrimitive?.contentOrNull ?: ""
                    val unionid = loginData["unionid"]?.jsonPrimitive?.contentOrNull ?: ""

                    if (musicKey.isNotEmpty()) {
                        if (musicId.isNotEmpty()) {
                            cookies["musicid"] = musicId
                            cookies["uin"] = musicId
                            cookies["qqmusic_uin"] = musicId
                        }
                        cookies["qqmusic_key"] = musicKey
                        cookies["qm_keyst"] = musicKey
                        cookies["qqmusic_version"] = "17"
                        cookies["qqmusic_miniversion"] = "70"
                        cookies["tmeLoginType"] = "1"
                        if (openid.isNotEmpty()) cookies["psrf_qqopenid"] = openid
                        if (accessToken.isNotEmpty()) cookies["psrf_qqaccess_token"] = accessToken
                        if (unionid.isNotEmpty()) cookies["psrf_qqunionid"] = unionid

                        UserSession.update(
                            uin = musicId.ifEmpty { UserSession.profile.uin },
                            nick = UserSession.profile.nick,
                            musicKey = musicKey,
                            cookies = cookies,
                            avatarUrl = UserSession.profile.avatarUrl,
                        )
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                false
            }
        }

    suspend fun ensureMusicKey(): Boolean =
        withContext(Dispatchers.IO) {
            val cookies = UserSession.profile.cookies.toMutableMap()
            val qmKey = cookies["qm_keyst"]
            if (!qmKey.isNullOrEmpty()) {
                return@withContext true
            }
            val pskey = cookies["p_skey"] ?: cookies["skey"]
            if (!pskey.isNullOrEmpty()) {
                return@withContext exchangeOAuthForMusicKey(cookies)
            }
            false
        }

    // ================== 微信扫码登录 ==================

    suspend fun fetchWeChatQrCode(): QrCodeInfo =
        withContext(Dispatchers.IO) {
            val redirectUri = "https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html%3Flogin_type%3D2%26surl%3Dhttps%3A%2F%2Fy.qq.com%2F"
            val href = "https%3A%2F%2Fy.qq.com%2Fmediastyle%2Fmusic_v17%2Fsrc%2Fcss%2Fpopup_wechat.css%23wechat_redirect"
            val url =
                "https://open.weixin.qq.com/connect/qrconnect?appid=wx48db31d50e334801" +
                    "&redirect_uri=$redirectUri&response_type=code&scope=snsapi_login&state=STATE&href=$href"

            val pageReq =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

            val uuid =
                client.newCall(pageReq).execute().use { resp ->
                    val html = resp.body?.string().orEmpty()
                    val matcher = WX_UUID_PATTERN.matcher(html)
                    if (matcher.find()) matcher.group(1) else throw IOException("Failed to extract WeChat uuid")
                }

            val qrReq =
                Request
                    .Builder()
                    .url("https://open.weixin.qq.com/connect/qrcode/$uuid")
                    .header("Referer", "https://open.weixin.qq.com/connect/qrconnect")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

            client.newCall(qrReq).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: throw IOException("Empty WeChat QR bytes")
                QrCodeInfo(bytes, uuid, "image/jpeg")
            }
        }

    suspend fun pollWeChatQrStatus(uuid: String): PollResult =
        withContext(Dispatchers.IO) {
            val ts = System.currentTimeMillis()
            val url = "https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=$uuid&_=$ts"

            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Referer", "https://open.weixin.qq.com/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val matcher = WX_STATUS_PATTERN.matcher(text)
                if (!matcher.find()) {
                    return@withContext PollResult(QrStatus.Error, "微信扫码状态解析异常")
                }

                val code = matcher.group(1).toIntOrNull() ?: -1
                when (code) {
                    405 -> {
                        val wxCode = matcher.group(2).orEmpty()
                        if (wxCode.isNotEmpty() && exchangeWeChatCode(wxCode)) {
                            PollResult(QrStatus.Success, "登录成功")
                        } else {
                            PollResult(QrStatus.Error, "微信授权凭据换取失败")
                        }
                    }
                    408 -> PollResult(QrStatus.Waiting, "等待手机微信扫码...")
                    404 -> PollResult(QrStatus.Confirming, "已扫码，请在手机上确认授权...")
                    402 -> PollResult(QrStatus.Expired, "二维码已失效")
                    403 -> PollResult(QrStatus.Canceled, "已取消登录")
                    else -> PollResult(QrStatus.Error, "微信状态码: $code")
                }
            }
        }

    private suspend fun exchangeWeChatCode(code: String): Boolean =
        withContext(Dispatchers.IO) {
            val payload =
                """
                {"comm":{"ct":11,"cv":14090008,"v":14090008,"chid":"10003505","tmeAppID":"qqmusic","tmeLoginType":1},"req_0":{"module":"music.login.LoginServer","method":"Login","param":{"code":"$code","strAppid":"wx48db31d50e334801"}}}
                """.trimIndent()

            val request =
                Request
                    .Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(payload.toRequestBody(JSON_TYPE))
                    .header("User-Agent", "QQMusic 14090008(android 14)")
                    .build()

            client.newCall(request).execute().use { resp ->
                val json = resp.body?.string().orEmpty()
                val jsonElement = Json.parseToJsonElement(json).jsonObject
                val req0 = jsonElement["req_0"]?.jsonObject ?: return@withContext false
                if (req0["code"]?.jsonPrimitive?.intOrNull != 0) return@withContext false

                val data = req0["data"]?.jsonObject ?: return@withContext false
                val musicId =
                    data["str_musicid"]?.jsonPrimitive?.contentOrNull
                        ?: data["musicid"]?.jsonPrimitive?.contentOrNull
                        ?: return@withContext false
                val musicKey = data["musickey"]?.jsonPrimitive?.contentOrNull ?: return@withContext false
                val nick = data["nick"]?.jsonPrimitive?.contentOrNull ?: "微信用户_$musicId"

                val cookies =
                    mutableMapOf(
                        "musicid" to musicId,
                        "uin" to musicId,
                        "qqmusic_uin" to musicId,
                        "qqmusic_key" to musicKey,
                        "qm_keyst" to musicKey,
                        "tmeLoginType" to "1",
                    )

                data["psrf_wxopenid"]?.jsonPrimitive?.contentOrNull?.let { cookies["openid"] = it }
                data["psrf_wx_access_token"]?.jsonPrimitive?.contentOrNull?.let { cookies["access_token"] = it }

                val rawAvatar =
                    data["headimgurl"]?.jsonPrimitive?.contentOrNull
                        ?: data["avatar"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                val avatar = normalizeHighResAvatar(rawAvatar)

                UserSession.update(
                    uin = musicId,
                    nick = nick,
                    musicKey = musicKey,
                    cookies = cookies,
                    avatarUrl = avatar,
                )
                true
            }
        }

    private fun hashPtqrToken(qrsig: String): Long {
        var e = 0L
        for (ch in qrsig) {
            e += (e shl 5) + ch.code
        }
        return 2147483647L and e
    }

    private fun extractCookie(
        setCookies: List<String>,
        name: String,
    ): String? {
        val prefix = "$name="
        for (sc in setCookies) {
            if (sc.startsWith(prefix, ignoreCase = true)) {
                return sc.substringAfter("=").substringBefore(";").trim()
            }
        }
        return null
    }

    // ================== QQ 音乐官方 App 扫码登录 ==================

    suspend fun fetchOfficialAppQrCode(): QrCodeInfo =
        withContext(Dispatchers.IO) {
            val payload =
                """
                {"comm":{"ct":23,"cv":0},"req_0":{"module":"music.login.LoginServer","method":"CreateQRCode","param":{"tmeAppID":"qqmusic","ct":11,"cv":14090008}}}
                """.trimIndent()

            val request =
                Request
                    .Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(payload.toRequestBody(JSON_TYPE))
                    .header("User-Agent", "QQMusic 14090008(android 14)")
                    .build()

            client.newCall(request).execute().use { resp ->
                val json = resp.body?.string().orEmpty()
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
                QrCodeInfo(bytes, identifier, "image/png")
            }
        }
}
