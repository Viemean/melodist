package org.melodist.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserProfile(
    var uin: String = "",
    var nick: String = "",
    var avatarUrl: String = "",
    var musicKey: String = "",
    var isVip: Boolean = false,
    var isHugeVip: Boolean = false,
    var isSvip: Boolean = false,
    var isYearVip: Boolean = false,
    var vipLevel: Int = 0,
    var nextVipLevel: Int = 0,
    var vipUpgradePercent: Float = 0f,
    var vipUpgradeDays: Int = 0,
    var vipExpireAt: String = "",
    var hugeVipExpireAt: String = "",
    var svipExpireAt: String = "",
    var greenVipExpireAt: String = "",
    var isCpLover: Boolean = false,
    var cpLoverExpireAt: String = "",
    var isGroupVip: Boolean = false,
    var groupVipExpireAt: String = "",
    var musicLevel: Int = 0,
    var musicScore: Int = 0,
    var encryptedUin: String = "",
    var cookies: Map<String, String> = emptyMap(),
) {
    val effectiveAvatarUrl: String
        get() {
            if (avatarUrl.isNotBlank() && !avatarUrl.contains("t_user_default")) return avatarUrl
            val qq =
                cookies["pt2gguin"].extractNumericQq()
                    ?: cookies["uin"].extractNumericQq()
                    ?: cookies["qqmusic_uin"].extractNumericQq()
                    ?: uin.extractNumericQq()
            return if (!qq.isNullOrBlank()) {
                "https://q1.qlogo.cn/g?b=qq&nk=$qq&s=640"
            } else {
                avatarUrl
            }
        }
}

private fun String?.extractNumericQq(): String? {
    if (this == null) return null
    val trimmed = this.trim().trimStart('o')
    return if (trimmed.isNotBlank() && trimmed.all { it.isDigit() }) trimmed else null
}

object UserSession {
    private val _profileFlow = MutableStateFlow(UserProfile())
    val profileFlow: StateFlow<UserProfile> = _profileFlow.asStateFlow()

    private val _favoriteSongCount = MutableStateFlow<Int?>(null)
    val favoriteSongCount: StateFlow<Int?> = _favoriteSongCount.asStateFlow()

    /**
     * 更新当前用户收藏歌曲的总数缓存。
     *
     * @param count 收藏歌曲总数
     */
    fun updateFavoriteSongCount(count: Int) {
        _favoriteSongCount.value = count
    }

    var profile: UserProfile
        get() = _profileFlow.value
        set(value) {
            _profileFlow.value = value
        }

    val loginType: Int
        get() {
            val p = _profileFlow.value
            return p.cookies["tmeLoginType"]?.toIntOrNull()
                ?: if (p.musicKey.startsWith("W_X")) 1 else 2
        }

    val isLoggedIn: Boolean
        get() {
            val p = _profileFlow.value
            val hasValidKey =
                p.musicKey.isNotBlank() ||
                    p.cookies.containsKey("qm_keyst") ||
                    p.cookies.containsKey("p_skey") ||
                    p.cookies.containsKey("skey") ||
                    p.cookies.containsKey("qqmusic_key")
            val hasValidId = p.uin.isNotBlank() || p.cookies.containsKey("musicid")
            return hasValidId && hasValidKey
        }

    /**
     * 更新当前登录用户的账号身份信息、凭证与 Cookie 集合。
     *
     * @param uin 用户身份标识 UIN 字符串，为空时将尝试从 Cookie 键中解析
     * @param nick 用户昵称
     * @param musicKey 核心鉴权密钥
     * @param cookies 会话关联的 Cookie 键值字典
     * @param avatarUrl 头像 URL 地址，为空时将根据 QQ 账号自动推导默认地址
     * @param isVip 是否具备有效 VIP 会员资格
     */
    fun update(
        uin: String,
        nick: String,
        musicKey: String,
        cookies: Map<String, String>,
        avatarUrl: String = "",
        isVip: Boolean = false,
    ) {
        val resolvedUin =
            uin.ifBlank {
                cookies["uin"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["qqmusic_uin"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["musicid"]?.takeIf { it.isNotBlank() }
                    ?: cookies["pt2gguin"]?.trimStart('o')?.takeIf { it.isNotBlank() }
                    ?: cookies["openid"]?.takeIf { it.isNotBlank() }
                    ?: ""
            }
        val isWeChatAccount =
            musicKey.startsWith("W_X") ||
                cookies["tmeLoginType"] == "1"

        val resolvedAvatar =
            avatarUrl.ifBlank {
                if (isWeChatAccount) {
                    ""
                } else {
                    val qq =
                        cookies["pt2gguin"].extractNumericQq()
                            ?: cookies["uin"].extractNumericQq()
                            ?: cookies["qqmusic_uin"].extractNumericQq()
                            ?: resolvedUin.extractNumericQq()
                    if (!qq.isNullOrBlank() && qq.length in 5..11) {
                        "https://q1.qlogo.cn/g?b=qq&nk=$qq&s=640"
                    } else {
                        ""
                    }
                }
            }
        profile =
            UserProfile(
                uin = resolvedUin,
                nick = nick.ifBlank { if (resolvedUin.isNotEmpty()) "用户_$resolvedUin" else "已登录用户" },
                musicKey = musicKey,
                cookies = cookies,
                avatarUrl = resolvedAvatar,
                isVip = isVip,
            )
    }

    /**
     * 重置并清空当前登录会话数据与收藏数量缓存。
     */
    fun clear() {
        profile = UserProfile()
        _favoriteSongCount.value = null
    }

    /**
     * 将当前会话中的 Cookie 键值对组装为标准 HTTP `Cookie` 请求头字符串。
     *
     * @return 格式化后的 Cookie 请求头字符串；无任何凭据时返回空字符串
     */
    fun getCookieHeader(): String {
        if (profile.cookies.isEmpty()) {
            val u = profile.uin
            val k = profile.musicKey
            return if (u.isNotBlank() && k.isNotBlank()) {
                "uin=$u; qqmusic_uin=$u; qqmusic_key=$k; qm_keyst=$k"
            } else if (u.isNotBlank()) {
                "uin=$u"
            } else {
                ""
            }
        }
        return profile.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * 将当前用户会话配置导出为 JSON 字符串。
     *
     * @return 序列化后的 JSON 字符串
     */
    fun toJson(): String = jsonHelper.encodeToString(profile)

    /**
     * 从 JSON 字符串解析并还原用户会话状态。
     *
     * @param jsonStr 序列化的用户会话 JSON 文本
     */
    fun fromJson(jsonStr: String) {
        try {
            val loaded = jsonHelper.decodeFromString<UserProfile>(jsonStr)
            if (loaded.uin.isBlank()) {
                val fallbackUin =
                    loaded.cookies["uin"]?.trimStart('o')
                        ?: loaded.cookies["qqmusic_uin"]?.trimStart('o')
                        ?: loaded.cookies["musicid"]
                        ?: loaded.cookies["pt2gguin"]?.trimStart('o')
                        ?: ""
                if (fallbackUin.isNotBlank()) {
                    loaded.uin = fallbackUin
                }
            }
            profile = loaded
        } catch (_: Exception) {
            clear()
        }
    }
}
