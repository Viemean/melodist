package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.MusicPreferenceItem
import org.melodist.model.UserMusicPreference

/**
 * 拉取用户当前听歌偏好与风格设置。
 *
 * @return 听歌偏好数据对象，未登录或网络失败时返回 null
 */
suspend fun MusicApiService.getUserMusicPreference(): UserMusicPreference? =
    withContext(Dispatchers.IO) {
        val uin = getEffectiveUin()
        val numericUin = uin.toLongOrNull() ?: 0L

        val payload =
            buildJsonObject {
                put(
                    "comm",
                    buildJsonObject {
                        put("ct", 11)
                        put("cv", 14090008)
                        put("v", 14090008)
                        put("format", "json")
                        put("qq", numericUin.toString())
                        val musicKey = UserSession.profile.musicKey
                        if (musicKey.isNotBlank()) {
                            put("authst", musicKey)
                        }
                    },
                )
                put(
                    "req_0",
                    buildJsonObject {
                        put("module", "music.recommend.UserProfileSettingSvr")
                        put("method", "GetProfileSwitchRelatedSongs")
                        put("param", buildJsonObject {})
                    },
                )
            }.toString()

        try {
            val responseStr = postGateway(payload)
            parseUserMusicPreference(responseStr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPreference", "getUserMusicPreference failed", e)
            null
        }
    }

/**
 * 提交并保存用户的听歌偏好设置。
 *
 * @param items 各项偏好设置列表
 * @param activeAdjustKey 本次主动变更调整项的 ProfileKey（若有）
 * @return 是否设置成功
 */
suspend fun MusicApiService.setUserMusicPreference(
    items: List<MusicPreferenceItem>,
    activeAdjustKey: String = "",
): Boolean =
    withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false
        val uin = getEffectiveUin()
        val numericUin = uin.toLongOrNull() ?: 0L

        val profilesArray =
            buildJsonArray {
                for (item in items) {
                    add(
                        buildJsonObject {
                            put("ProfileKey", item.profileKey)
                            put("Score", item.currentScore)
                            put("IsAdjust", if (item.profileKey == activeAdjustKey) 1 else 0)
                            put("IsSetBlack", if (item.isBlack) 1 else 0)
                        },
                    )
                }
            }

        val payload =
            buildJsonObject {
                put(
                    "comm",
                    buildJsonObject {
                        put("g_tk", 5381)
                        put("uin", numericUin.toString())
                        put("format", "json")
                        put("inCharset", "utf-8")
                        put("outCharset", "utf-8")
                        put("notice", 0)
                        put("platform", "h5")
                        put("needNewCode", 1)
                    },
                )
                put(
                    "req_0",
                    buildJsonObject {
                        put("module", "music.recommend.UserProfileSettingSvr")
                        put("method", "SetUserProfile")
                        put(
                            "param",
                            buildJsonObject {
                                put("Profiles", profilesArray)
                            },
                        )
                    },
                )
            }.toString()

        try {
            val responseStr = postGateway(payload)
            val root = Json.parseToJsonElement(responseStr).jsonObject
            val req0 = root["req_0"]?.jsonObject ?: return@withContext false
            val code = req0["code"]?.jsonPrimitive?.intOrNull ?: -1
            code == 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPreference", "setUserMusicPreference failed", e)
            false
        }
    }

internal fun parseUserMusicPreference(jsonStr: String): UserMusicPreference? {
    return try {
        val root = Json.parseToJsonElement(jsonStr).jsonObject
        val req0 = root["req_0"]?.jsonObject ?: return null
        val code = req0["code"]?.jsonPrimitive?.intOrNull ?: return null
        if (code != 0 && code != 1) return null

        val data = req0["data"]?.jsonObject ?: return null
        val updateTime = data["UpdateTime"]?.jsonPrimitive?.longOrNull ?: 0L
        val rawScores = data["ProfileScore"]?.jsonArray ?: JsonArray(emptyList())

        val items = mutableListOf<MusicPreferenceItem>()
        for (el in rawScores) {
            val obj = el.jsonObject
            val key = obj["ProfileKey"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = obj["ProfileName"]?.jsonPrimitive?.contentOrNull ?: ""
            val score = obj["CurrentScore"]?.jsonPrimitive?.intOrNull ?: 50
            val isBlack = obj["IsBlack"]?.jsonPrimitive?.booleanOrNull ?: false
            val isChangeProfile = obj["IsChangeProfile"]?.jsonPrimitive?.booleanOrNull ?: false

            items.add(
                MusicPreferenceItem(
                    profileKey = key,
                    profileName = name,
                    currentScore = score,
                    isBlack = isBlack,
                    isChangeProfile = isChangeProfile,
                ),
            )
        }

        UserMusicPreference(
            updateTime = updateTime,
            isExpired = code == 1,
            scores = items,
        )
    } catch (e: Exception) {
        ApiLogger.w("MusicApiPreference", "parseUserMusicPreference error", e)
        null
    }
}
