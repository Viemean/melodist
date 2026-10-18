package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.regex.Pattern

private val WX_STATUS_PATTERN = Pattern.compile("window\\.wx_errcode=(\\d+);(?:window\\.wx_code='([^']*)';)?")
private val WX_UUID_PATTERN = Pattern.compile("connect/qrcode/([a-zA-Z0-9_-]+)")

/**
 * 获取微信开放平台网页扫码登录二维码及关联的 uuid。
 *
 * @return 包含二维码图片数据与 uuid 的 [QrCodeInfo]
 * @throws IOException 当获取授权页面或拉取二维码图像失败时抛出
 */
internal suspend fun LoginApiService.fetchWeChatQrCodeImpl(): QrCodeInfo =
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
                val html = resp.body.string()
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
            val bytes = resp.body.bytes()
            QrCodeInfo(bytes, uuid, "image/jpeg")
        }
    }

/**
 * 长轮询检测微信二维码扫描与授权状态，成功时自动执行授权换票与登录更新。
 *
 * @param uuid 微信授权唯一会话标识
 * @return 轮询结果状态 [PollResult]
 */
internal suspend fun LoginApiService.pollWeChatQrStatusImpl(uuid: String): PollResult =
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
            val text = resp.body.string()
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

                408 -> {
                    PollResult(QrStatus.Waiting, "等待手机微信扫码...")
                }

                404 -> {
                    PollResult(QrStatus.Confirming, "已扫码，请在手机上确认授权...")
                }

                402 -> {
                    PollResult(QrStatus.Expired, "二维码已失效")
                }

                403 -> {
                    PollResult(QrStatus.Canceled, "已取消登录")
                }

                else -> {
                    PollResult(QrStatus.Error, "微信状态码: $code")
                }
            }
        }
    }

private suspend fun LoginApiService.exchangeWeChatCode(code: String): Boolean =
    withContext(Dispatchers.IO) {
        val payload =
            """
            {"comm":{"ct":11,"cv":14090008,"v":14090008,"chid":"10003505","tmeAppID":"qqmusic","tmeLoginType":1},"req_0":{"module":"music.login.LoginServer","method":"Login","param":{"code":"$code","strAppid":"wx48db31d50e334801"}}}
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
            data["psrf_wxrefresh_token"]?.jsonPrimitive?.contentOrNull?.let { cookies["refresh_token"] = it }

            val rawAvatar =
                data["headimgurl"]?.jsonPrimitive?.contentOrNull
                    ?: data["avatar"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            val avatar = normalizeHighResAvatar(rawAvatar, isQqAccount = false)

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
