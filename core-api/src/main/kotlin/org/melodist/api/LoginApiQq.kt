package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.regex.Pattern

private val SINGLE_QUOTE_PATTERN = Pattern.compile("'([^']*)'")
private val UIN_QUERY_PATTERN = Pattern.compile("[?&]uin=([^&]+)")
private val CODE_PATTERN = Pattern.compile("[?&]code=([^&\\s\"']+)")

private fun hashPtqrToken(qrsig: String): Long {
    var e = 0L
    for (ch in qrsig) {
        e += (e shl 5) + ch.code
    }
    return 2147483647L and e
}

private fun getACSRFToken(pSkey: String): Int {
    var hash = 5381
    for (ch in pSkey) {
        hash += (hash shl 5) + ch.code
    }
    return hash and 0x7fffffff
}

/**
 * 获取 QQ 互联扫码登录二维码图片及关联标识。
 *
 * @return 包含二维码二进制数据及 qrsig 签名校验凭证的 [QrCodeInfo]
 * @throws IOException 当网络请求失败或响应未包含有效 qrsig 时抛出
 */
internal suspend fun LoginApiService.fetchQqQrCodeImpl(): QrCodeInfo =
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
                LoginApiService.extractCookie(response.headers("Set-Cookie"), "qrsig")
                    ?: throw IOException("QQ login response missing qrsig")
            val bytes = response.body.bytes()
            QrCodeInfo(bytes, qrsig, "image/png")
        }
    }

/**
 * 轮询检查 QQ 扫码登录状态并在扫码成功后自动换取登录态。
 *
 * @param qrsig 二维码关联的签名凭证字符串
 * @return 包含二维码当前状态（等待扫码、已扫码、已失效、登录成功等）的 [PollResult]
 */
internal suspend fun LoginApiService.pollQqQrStatusImpl(qrsig: String): PollResult =
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
            val text = response.body.string()
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

                66 -> {
                    PollResult(QrStatus.Waiting, "等待手机 QQ 扫码...")
                }

                67 -> {
                    PollResult(QrStatus.Confirming, "已扫码，请在手机上确认授权...")
                }

                65 -> {
                    PollResult(QrStatus.Expired, "二维码已失效")
                }

                68 -> {
                    PollResult(QrStatus.Canceled, "已取消登录")
                }

                else -> {
                    PollResult(QrStatus.Error, message)
                }
            }
        }
    }

private suspend fun LoginApiService.exchangeQqCookies(
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

        if (!jumpUrl.isNullOrBlank()) {
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val jumpReq =
                Request
                    .Builder()
                    .url(jumpUrl)
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
    } catch (e: Exception) {
        ApiLogger.w("LoginApiService", "Failed to parse uin from cookie", e)
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

    exchangeOAuthForMusicKey(cookies)
}

/**
 * 利用 QQ 网页登录生成的 `p_skey`/`skey` 凭证换取 QQ 音乐官方 `qm_keyst` / `musickey`。
 *
 * @param cookies 会话 Cookie 字典，换取成功后将直接在其中更新注入新的登录凭证
 * @return 换取成功返回 `true`；缺少关键凭据或网络异常返回 `false`
 */
internal suspend fun LoginApiService.exchangeOAuthForMusicKeyImpl(cookies: MutableMap<String, String>): Boolean =
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
                    val body = authResp.body.string()
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
                    .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

            client.newCall(loginReq).execute().use { loginResp ->
                val json = loginResp.body.string()
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
                    cookies["tmeLoginType"] = "2"
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
        } catch (_: Exception) {
            false
        }
    }

/**
 * 通过 QQ 互联 openid 与 accessToken 刷新登录态并更新 `musickey`。
 *
 * @param openid QQ 互联唯一用户标识
 * @param accessToken QQ 互联访问令牌
 * @return 刷新并更新成功返回 `true`；网络失败或凭证无效返回 `false`
 */
internal suspend fun LoginApiService.refreshQQLoginTokenImpl(
    openid: String,
    accessToken: String,
): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"
            val payload =
                """{"comm":{"ct":19,"cv":1,"tmeLoginType":"1"},"login":{"module":"QQConnectLogin.LoginServer","method":"QQLogin","param":{"onlyNeedAccessToken":0,"forceRefreshToken":1,"appid":100497308,"openid":"$openid","access_token":"$accessToken"}}}"""

            val requestBuilder =
                Request
                    .Builder()
                    .url(url)
                    .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .header("Origin", "https://y.qq.com")

            val cookieHeader = UserSession.getCookieHeader()
            if (cookieHeader.isNotBlank()) {
                requestBuilder.header("Cookie", cookieHeader)
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val json = resp.body.string()
                val jsonElement = Json.parseToJsonElement(json).jsonObject
                val loginObj = jsonElement["login"]?.jsonObject ?: return@withContext false
                val code = loginObj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
                if (code != 0) return@withContext false
                val loginData = loginObj["data"]?.jsonObject ?: return@withContext false

                val musicId =
                    loginData["str_musicid"]?.jsonPrimitive?.contentOrNull
                        ?: loginData["musicid"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                val musicKey = loginData["musickey"]?.jsonPrimitive?.contentOrNull ?: ""
                val newOpenid = loginData["openid"]?.jsonPrimitive?.contentOrNull ?: openid
                val newAccessToken = loginData["access_token"]?.jsonPrimitive?.contentOrNull ?: accessToken
                val unionid = loginData["unionid"]?.jsonPrimitive?.contentOrNull ?: ""

                if (musicKey.isNotEmpty()) {
                    val cookies = UserSession.profile.cookies.toMutableMap()
                    if (musicId.isNotEmpty()) {
                        cookies["musicid"] = musicId
                        cookies["uin"] = musicId
                        cookies["qqmusic_uin"] = musicId
                    }
                    cookies["qqmusic_key"] = musicKey
                    cookies["qm_keyst"] = musicKey
                    cookies["qqmusic_version"] = "17"
                    cookies["qqmusic_miniversion"] = "70"
                    cookies["tmeLoginType"] = "2"
                    if (newOpenid.isNotEmpty()) cookies["psrf_qqopenid"] = newOpenid
                    if (newAccessToken.isNotEmpty()) cookies["psrf_qqaccess_token"] = newAccessToken
                    if (unionid.isNotEmpty()) cookies["psrf_qqunionid"] = unionid

                    UserSession.update(
                        uin = musicId.ifEmpty { UserSession.profile.uin },
                        nick = UserSession.profile.nick,
                        musicKey = musicKey,
                        cookies = cookies,
                        avatarUrl = UserSession.profile.avatarUrl,
                        isVip = UserSession.profile.isVip,
                    )
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ApiLogger.w("LoginApiService", "refreshQQLoginToken failed", e)
            false
        }
    }

internal suspend fun LoginApiService.refreshQQPlaybackCredentials(
    creds: PlaybackCredentials,
    openid: String,
    accessToken: String,
): PlaybackCredentials? =
    withContext(Dispatchers.IO) {
        try {
            val url = "https://u.y.qq.com/cgi-bin/musicu.fcg"
            val payload =
                """{"comm":{"ct":19,"cv":1,"tmeLoginType":"1"},"login":{"module":"QQConnectLogin.LoginServer","method":"QQLogin","param":{"onlyNeedAccessToken":0,"forceRefreshToken":1,"appid":100497308,"openid":"$openid","access_token":"$accessToken"}}}"""

            val request =
                Request
                    .Builder()
                    .url(url)
                    .post(payload.toRequestBody(LoginApiService.JSON_TYPE))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://y.qq.com/")
                    .header("Origin", "https://y.qq.com")
                    .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = resp.body.string()
                val jsonElement = Json.parseToJsonElement(json).jsonObject
                val loginObj = jsonElement["login"]?.jsonObject ?: return@withContext null
                if (loginObj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != 0) return@withContext null
                val loginData = loginObj["data"]?.jsonObject ?: return@withContext null

                val newMusicKey = loginData["musickey"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
                if (newMusicKey.isEmpty()) return@withContext null

                val updatedCookies = creds.cookies.toMutableMap()
                updatedCookies["musickey"] = newMusicKey
                updatedCookies["qm_keyst"] = newMusicKey
                updatedCookies["qqmusic_key"] = newMusicKey
                loginData["openid"]?.jsonPrimitive?.contentOrNull?.let { updatedCookies["psrf_qqopenid"] = it }
                loginData["access_token"]?.jsonPrimitive?.contentOrNull?.let { updatedCookies["psrf_qqaccess_token"] = it }

                val musicId =
                    loginData["str_musicid"]?.jsonPrimitive?.contentOrNull
                        ?: loginData["musicid"]?.jsonPrimitive?.contentOrNull
                        ?: creds.uin

                creds.copy(
                    uin = musicId.ifBlank { creds.uin },
                    musicKey = newMusicKey,
                    cookies = updatedCookies.toMap(),
                    updateTime = System.currentTimeMillis(),
                    isRevoked = false,
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ApiLogger.w("LoginApiService", "refreshQQPlaybackCredentials exception", e)
            null
        }
    }
