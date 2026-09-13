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
            val currentProfile = UserSession.profile

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
                val avatar = baseInfo["Avatar"]?.jsonPrimitive?.contentOrNull

                if (!name.isNullOrBlank() && name != currentProfile.nick) {
                    currentProfile.nick = name
                    changed = true
                }
                if (!encUin.isNullOrBlank()) {
                    currentProfile.encryptedUin = encUin
                    changed = true
                }
                if (!avatar.isNullOrBlank() && avatar != currentProfile.avatarUrl) {
                    currentProfile.avatarUrl = avatar
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

                    currentProfile.isVip = isVip
                    currentProfile.vipLevel = level
                    currentProfile.vipExpireAt = expireAt
                    changed = true
                }

                val userInfo = vipData["userinfo"]?.jsonObject
                if (userInfo != null) {
                    currentProfile.musicLevel = userInfo["music_level"]?.jsonPrimitive?.intOrNull ?: 0
                    changed = true
                }
            }

            if (changed) {
                UserSession.profile = currentProfile
            }
            changed
        } catch (e: Exception) {
            false
        }
    }
