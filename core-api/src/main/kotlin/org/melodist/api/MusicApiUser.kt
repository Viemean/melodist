package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * 用户画像与 VIP 权益同步扩展
 */

suspend fun MusicApiService.refreshCurrentUserProfile(): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext false
        ensureMusicKeySafe()

        var uin = UserSession.profile.uin
        var authst = UserSession.profile.musicKey
        var loginType = UserSession.loginType

        fun buildProfilePayload(
            u: String,
            a: String,
            lt: Int,
        ): String =
            """
            {
              "comm": {
                "ct": 11,
                "cv": 14090008,
                "v": 14090008,
                "chid": "10003505",
                "tmeAppID": "qqmusic",
                "tmeLoginType": $lt,
                "qq": "$u",
                "authst": "$a"
              },
              "profile": {
                "module": "music.UnifiedHomepage.UnifiedHomepageSrv",
                "method": "GetHomepageHeader",
                "param": { "uin": "$u", "IsQueryTabDetail": 1 }
              },
              "vip": {
                "module": "VipLogin.VipLoginInter",
                "method": "vip_login_base",
                "param": {}
              }
            }
            """.trimIndent()

        try {
            var respJson = postGateway(buildProfilePayload(uin, authst, loginType))
            var root = Json.parseToJsonElement(respJson).jsonObject

            val rootCode = root["code"]?.jsonPrimitive?.intOrNull ?: 0
            val profileCode =
                root["profile"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.intOrNull ?: 0
            val vipCode =
                root["vip"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.intOrNull ?: 0

            if (rootCode == 2000 || profileCode == 2000 || vipCode == 2000) {
                ApiLogger.i("MusicApiUser", "Detected expired credentials (code 2000), attempting auto refresh...")
                if (LoginApiService().forceRefreshMusicKey()) {
                    uin = UserSession.profile.uin
                    authst = UserSession.profile.musicKey
                    loginType = UserSession.loginType
                    respJson = postGateway(buildProfilePayload(uin, authst, loginType))
                    root = Json.parseToJsonElement(respJson).jsonObject
                }
            }

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
                        isQqAccount = loginType != 1,
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
                val svipRoot = vipData["svip"]?.jsonPrimitive?.intOrNull ?: 0
                val identity = vipData["identity"]?.jsonObject
                if (identity != null) {
                    val vip = identity["vip"]?.jsonPrimitive?.intOrNull ?: 0
                    val hugeVip = identity["HugeVip"]?.jsonPrimitive?.intOrNull ?: 0
                    val svipId = identity["svip"]?.jsonPrimitive?.intOrNull ?: 0
                    val isSvip = svipRoot > 0 || svipId > 0
                    val isHuge = hugeVip > 0
                    val isGreen = vip > 0
                    val isYear =
                        (identity["yearflag"]?.jsonPrimitive?.intOrNull ?: 0) > 0 ||
                            (identity["yearffb"]?.jsonPrimitive?.intOrNull ?: 0) > 0

                    val level = identity["level"]?.jsonPrimitive?.intOrNull ?: 0
                    val nextLevel = identity["nextlevel"]?.jsonPrimitive?.intOrNull ?: if (level > 0) level + 1 else 0
                    val upgradePct = identity["upgradepct"]?.jsonPrimitive?.floatOrNull ?: 0f
                    val upgradeDays = identity["upgradeday"]?.jsonPrimitive?.intOrNull ?: 0

                    val hugeEnd = identity["HugeVipEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val overdate = identity["overdate"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val lmEnd = identity["LMEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val eightEnd = identity["eightEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val svipEnd =
                        if (isSvip) {
                            hugeEnd.ifBlank { overdate }
                        } else {
                            ""
                        }

                    val hugeExpire =
                        if (isHuge) {
                            if (isSvip && overdate.isNotBlank()) overdate else hugeEnd.ifBlank { overdate }
                        } else {
                            ""
                        }

                    val greenEnd =
                        if (isGreen && !isHuge && !isSvip) {
                            if (lmEnd.isNotBlank()) lmEnd else overdate
                        } else {
                            ""
                        }

                    val cpLoverFlag = identity["CPLoverFlag"]?.jsonPrimitive?.intOrNull ?: 0
                    val cpLoverEnd = identity["CPLoverEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val groupVipFlag = identity["GroupVipFlag"]?.jsonPrimitive?.intOrNull ?: 0
                    val groupVipEnd = identity["GroupVipEnd"]?.jsonPrimitive?.contentOrNull.orEmpty()

                    val isCpLover = cpLoverFlag > 0
                    val isGroupVip = groupVipFlag > 0

                    newProfile.isVip = isGreen || isHuge || isSvip || isCpLover || isGroupVip
                    newProfile.isHugeVip = isHuge
                    newProfile.isSvip = isSvip
                    newProfile.isYearVip = isYear
                    newProfile.isCpLover = isCpLover
                    newProfile.cpLoverExpireAt = cpLoverEnd
                    newProfile.isGroupVip = isGroupVip
                    newProfile.groupVipExpireAt = groupVipEnd
                    newProfile.vipLevel = level
                    newProfile.nextVipLevel = nextLevel
                    newProfile.vipUpgradePercent = upgradePct
                    newProfile.vipUpgradeDays = upgradeDays
                    newProfile.hugeVipExpireAt = hugeExpire
                    newProfile.svipExpireAt = svipEnd
                    newProfile.greenVipExpireAt = greenEnd
                    newProfile.vipExpireAt = hugeExpire.ifBlank { svipEnd.ifBlank { cpLoverEnd.ifBlank { overdate } } }
                    changed = true
                }

                val userInfo = vipData["userinfo"]?.jsonObject
                if (userInfo != null) {
                    newProfile.musicLevel = userInfo["music_level"]?.jsonPrimitive?.intOrNull ?: 0
                    newProfile.musicScore = userInfo["score"]?.jsonPrimitive?.intOrNull ?: 0
                    changed = true
                }
            }

            if (changed) {
                UserSession.profile = newProfile
            }
            changed
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiUser", "refreshCurrentUserProfile failed", e)
            false
        }
    }

/**
 * 将 QQ 或微信头像 URL 转换为对应平台的高清规格尺寸。
 *
 * @param avatarUrl 原始头像 URL 地址
 * @param uin 账号 UIN 字符串，在头像为空时用于构造默认高清头像地址
 * @param isQqAccount 是否为 QQ 账号体系
 * @return 归一化后的高清头像 URL；无法解析且无有效 UIN 时返回空字符串
 */
fun normalizeHighResAvatar(
    avatarUrl: String,
    uin: String = "",
    isQqAccount: Boolean = true,
): String {
    var url = avatarUrl.trim().replace("http://", "https://")
    if (url.contains("t_user_default")) {
        url = ""
    }
    if (url.contains("qlogo.cn")) {
        // QQ 头像规格提升到 640x640 高清
        url = url.replace(Regex("&s=\\d+"), "&s=640")
        url = url.replace(Regex("/(40|100|140)$"), "/640")
    } else if (url.contains("thirdwx.qlogo.cn") || url.contains("/mmopen/")) {
        // 微信头像规格提升到 /0 最高清原图
        url = url.replace(Regex("/(132|96|64|46)$"), "/0")
    }

    if (isQqAccount && url.isBlank() && uin.isNotBlank() && uin.all { it.isDigit() } && uin.length in 5..11) {
        url = "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=640"
    }
    return url
}
