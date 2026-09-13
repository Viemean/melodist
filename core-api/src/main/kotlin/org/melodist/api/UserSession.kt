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
    var vipLevel: Int = 0,
    var vipExpireAt: String = "",
    var musicLevel: Int = 0,
    var encryptedUin: String = "",
    var cookies: Map<String, String> = emptyMap(),
)

object UserSession {
    private val _profileFlow = MutableStateFlow(UserProfile())
    val profileFlow: StateFlow<UserProfile> = _profileFlow.asStateFlow()

    private val _favoriteSongCount = MutableStateFlow<Int?>(null)
    val favoriteSongCount: StateFlow<Int?> = _favoriteSongCount.asStateFlow()

    fun updateFavoriteSongCount(count: Int) {
        _favoriteSongCount.value = count
    }

    var profile: UserProfile
        get() = _profileFlow.value
        set(value) {
            _profileFlow.value = value
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
                cookies["uin"]?.trimStart('o')
                    ?: cookies["qqmusic_uin"]?.trimStart('o')
                    ?: cookies["musicid"]
                    ?: cookies["pt2gguin"]?.trimStart('o')
                    ?: cookies["openid"]
                    ?: ""
            }
        profile =
            UserProfile(
                uin = resolvedUin,
                nick = nick.ifBlank { if (resolvedUin.isNotEmpty()) "用户_$resolvedUin" else "已登录用户" },
                musicKey = musicKey,
                cookies = cookies,
                avatarUrl = avatarUrl,
                isVip = isVip,
            )
    }

    fun clear() {
        profile = UserProfile()
        _favoriteSongCount.value = null
    }

    fun getCookieHeader(): String = profile.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun toJson(): String = jsonHelper.encodeToString(profile)

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
