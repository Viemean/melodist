package org.melodist.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LoginApiService(
    internal val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .build(),
) {
    internal val officialAppSessions = ConcurrentHashMap<String, OfficialAppLoginSession>()

    companion object {
        internal val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val refreshMutex = Mutex()

        internal fun extractCookie(
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
    }

    suspend fun fetchQqQrCode(): QrCodeInfo = fetchQqQrCodeImpl()

    suspend fun pollQqQrStatus(qrsig: String): PollResult = pollQqQrStatusImpl(qrsig)

    suspend fun exchangeOAuthForMusicKey(cookies: MutableMap<String, String>): Boolean = exchangeOAuthForMusicKeyImpl(cookies)

    suspend fun refreshQQLoginToken(
        openid: String,
        accessToken: String,
    ): Boolean = refreshQQLoginTokenImpl(openid, accessToken)

    suspend fun fetchWeChatQrCode(): QrCodeInfo = fetchWeChatQrCodeImpl()

    suspend fun pollWeChatQrStatus(uuid: String): PollResult = pollWeChatQrStatusImpl(uuid)

    suspend fun fetchOfficialAppQrCode(): QrCodeInfo = fetchOfficialAppQrCodeImpl()

    fun pollOfficialAppQrStatus(identifier: String): PollResult = pollOfficialAppQrStatusImpl(identifier)

    fun cancelOfficialAppSession(identifier: String) {
        cancelOfficialAppSessionImpl(identifier)
    }

    suspend fun refreshOfficialAppToken(
        musicId: String,
        refreshToken: String,
        refreshKey: String,
        musicKey: String,
        loginType: Int,
        openid: String = "",
        accessToken: String = "",
    ): Boolean =
        refreshOfficialAppTokenImpl(
            musicId = musicId,
            refreshToken = refreshToken,
            refreshKey = refreshKey,
            musicKey = musicKey,
            loginType = loginType,
            openid = openid,
            accessToken = accessToken,
        )

    suspend fun refreshPlaybackCredentials(creds: PlaybackCredentials): PlaybackCredentials? = refreshPlaybackCredentialsImpl(creds)

    /**
     * 校验当前会话中的 `qm_keyst` 凭据有效性；已失效或指定强制刷新时，加锁并发安全地执行登录凭据续签。
     *
     * @param forceRefresh 是否强制忽略现有凭证进行续签刷新
     * @return 刷新成功或现有凭据有效返回 `true`；刷新失败返回 `false`
     */
    suspend fun ensureMusicKey(forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val qmKey = UserSession.profile.cookies["qm_keyst"]
            if (!forceRefresh && !qmKey.isNullOrEmpty()) {
                return@withContext true
            }
            refreshMutex.withLock {
                if (!forceRefresh) {
                    val keyAfterLock = UserSession.profile.cookies["qm_keyst"]
                    if (!keyAfterLock.isNullOrEmpty()) {
                        return@withLock true
                    }
                }
                val cookies = UserSession.profile.cookies.toMutableMap()
                val openid = cookies["psrf_qqopenid"]
                val accessToken = cookies["psrf_qqaccess_token"]
                if (!openid.isNullOrEmpty() && !accessToken.isNullOrEmpty()) {
                    val refreshed = refreshQQLoginToken(openid, accessToken)
                    if (refreshed) return@withLock true
                }

                val refreshToken = cookies["refresh_token"]
                val musicId = cookies["musicid"] ?: cookies["uin"] ?: UserSession.profile.uin
                if (!refreshToken.isNullOrEmpty() && musicId.isNotEmpty()) {
                    val refreshKey = cookies["refresh_key"].orEmpty()
                    val musicKey = cookies["musickey"] ?: cookies["qm_keyst"] ?: UserSession.profile.musicKey
                    val rawLoginType = cookies["tmeLoginType"]?.toIntOrNull() ?: UserSession.loginType
                    val refreshed =
                        refreshOfficialAppToken(
                            musicId = musicId,
                            refreshToken = refreshToken,
                            refreshKey = refreshKey,
                            musicKey = musicKey,
                            loginType = rawLoginType,
                            openid = openid.orEmpty(),
                            accessToken = accessToken.orEmpty(),
                        )
                    if (refreshed) return@withLock true
                }

                val pskey = cookies["p_skey"] ?: cookies["skey"]
                if (!pskey.isNullOrEmpty()) {
                    return@withLock exchangeOAuthForMusicKey(cookies)
                }
                false
            }
        }

    /**
     * 强制执行一次音乐鉴权凭证 `qm_keyst` 的续期与刷新。
     *
     * @return 刷新成功返回 `true`，失败返回 `false`
     */
    suspend fun forceRefreshMusicKey(): Boolean = ensureMusicKey(forceRefresh = true)
}
