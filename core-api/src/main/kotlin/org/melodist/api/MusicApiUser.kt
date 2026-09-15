package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 用户画像与 VIP 权益同步扩展
 */

suspend fun MusicApiService.refreshCurrentUserProfile(): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext false
        try {
            LoginApiService().ensureMusicKey()
        } catch (_: Exception) {
        }

        val uin = UserSession.profile.uin
        val authst = UserSession.profile.musicKey
        val loginType = if (authst.startsWith("W_X")) 1 else 2

        val payload =
            """
            {
              "comm": {
                "ct": 11,
                "cv": 14090008,
                "v": 14090008,
                "chid": "10003505",
                "tmeAppID": "qqmusic",
                "tmeLoginType": $loginType,
                "qq": "$uin",
                "authst": "$authst"
              },
              "profile": {
                "module": "music.UnifiedHomepage.UnifiedHomepageSrv",
                "method": "GetHomepageHeader",
                "param": { "uin": "$uin", "IsQueryTabDetail": 1 }
              },
              "vip": {
                "module": "VipLogin.VipLoginInter",
                "method": "vip_login_base",
                "param": {}
              }
            }
            """.trimIndent()

        try {
            val respJson = postGateway(payload)
            val root = Json.parseToJsonElement(respJson).jsonObject

            var changed = false
            val newProfile = UserSession.profile.copy()

            // 1. 用户基础信息
            val baseInfo =
                root["profile"]
                    ?.jsonObject
                    ?.get("data")
                    ?.jsonObject
                    ?.get("Info")
                    ?.jsonObject
                    ?.get("BaseInfo")
                    ?.jsonObject
            if (baseInfo != null) {
                val name = baseInfo["Name"]?.jsonPrimitive?.contentOrNull
                val encUin = baseInfo["EncryptedUin"]?.jsonPrimitive?.contentOrNull
                val bigAvatar = baseInfo["BigAvatar"]?.jsonPrimitive?.contentOrNull
                val rawAvatar = baseInfo["Avatar"]?.jsonPrimitive?.contentOrNull

                val normalizedAvatar =
                    normalizeHighResAvatar(
                        avatarUrl = bigAvatar ?: rawAvatar.orEmpty(),
                        uin = uin,
                    )

                if (!name.isNullOrBlank() && name != newProfile.nick) {
                    newProfile.nick = name
                    changed = true
                }
                if (!encUin.isNullOrBlank()) {
                    newProfile.encryptedUin = encUin
                    changed = true
                }
                if (normalizedAvatar.isNotBlank() && normalizedAvatar != newProfile.avatarUrl) {
                    newProfile.avatarUrl = normalizedAvatar
                    changed = true
                }
            }

            // 2. VIP 与绿钻信息
            val vipData = root["vip"]?.jsonObject?.get("data")?.jsonObject
            if (vipData != null) {
                val identity = vipData["identity"]?.jsonObject
                if (identity != null) {
                    val vip = identity["vip"]?.jsonPrimitive?.intOrNull ?: 0
                    val hugeVip = identity["HugeVip"]?.jsonPrimitive?.intOrNull ?: 0
                    val svip = identity["svip"]?.jsonPrimitive?.intOrNull ?: 0
                    val isVip = vip > 0 || hugeVip > 0 || svip > 0
                    val level = identity["level"]?.jsonPrimitive?.intOrNull ?: 0
                    val expireAt =
                        identity["HugeVipEnd"]?.jsonPrimitive?.contentOrNull
                            ?: identity["overdate"]?.jsonPrimitive?.contentOrNull ?: ""

                    newProfile.isVip = isVip
                    newProfile.vipLevel = level
                    newProfile.vipExpireAt = expireAt
                    changed = true
                }

                val userInfo = vipData["userinfo"]?.jsonObject
                if (userInfo != null) {
                    newProfile.musicLevel = userInfo["music_level"]?.jsonPrimitive?.intOrNull ?: 0
                    changed = true
                }
            }

            if (changed) {
                UserSession.profile = newProfile
            }
            changed
        } catch (_: Exception) {
            false
        }
    }

fun normalizeHighResAvatar(
    avatarUrl: String,
    uin: String = "",
): String {
    var url = avatarUrl.trim().replace("http://", "https://")
    if (url.contains("qlogo.cn")) {
        // QQ 头像规格提升到 640x640 高清
        url = url.replace(Regex("&s=\\d+"), "&s=640")
        url = url.replace(Regex("/(40|100|140)$"), "/640")
    } else if (url.contains("thirdwx.qlogo.cn") || url.contains("/mmopen/")) {
        // 微信头像规格提升到 /0 最高清原图
        url = url.replace(Regex("/(132|96|64|46)$"), "/0")
    }

    if (url.isBlank() && uin.isNotBlank() && uin.all { it.isDigit() }) {
        url = "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=640"
    }
    return url
}
