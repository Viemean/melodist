package org.melodist.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class PlaybackCredentials(
    val uin: String = "",
    val musicKey: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val isVip: Boolean = false,
    val nick: String = "",
    val updateTime: Long = System.currentTimeMillis(),
)

object PlaybackCredentialsManager {
    private val _credentialsFlow = MutableStateFlow<PlaybackCredentials?>(null)
    val credentialsFlow: StateFlow<PlaybackCredentials?> = _credentialsFlow.asStateFlow()

    val currentCredentials: PlaybackCredentials?
        get() = _credentialsFlow.value

    val hasCustomCredentials: Boolean
        get() = _credentialsFlow.value != null

    fun setCredentials(creds: PlaybackCredentials?) {
        _credentialsFlow.value = creds
    }

    fun clearCredentials() {
        _credentialsFlow.value = null
    }

    // 鉴权 Cookie 精简白名单
    private val ESSENTIAL_COOKIE_KEYS =
        setOf(
            "qm_keyst",
            "qqmusic_key",
            "pskey",
            "p_skey",
            "skey",
            "uin",
            "musicid",
            "wxuin",
            "wxopenid",
            "pt2gguin",
        )

    fun getActiveUin(): String {
        val customUin = _credentialsFlow.value?.uin
        return if (!customUin.isNullOrBlank()) customUin else UserSession.profile.uin.ifBlank { "0" }
    }

    fun getActiveAuthst(): String {
        val customKey = _credentialsFlow.value?.musicKey
        return if (!customKey.isNullOrBlank()) customKey else UserSession.profile.musicKey
    }

    fun getActiveCookieHeader(): String {
        val custom = _credentialsFlow.value
        return if (custom != null && custom.cookies.isNotEmpty()) {
            custom.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            UserSession.getCookieHeader()
        }
    }

    @Serializable
    private data class CompactCredentialsPayload(
        val u: String = "",
        val k: String = "",
        val c: Map<String, String> = emptyMap(),
        val v: Boolean = false,
        val n: String = "",
        val t: Long = 0L,
    )

    private val jsonHelper =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private const val MAGIC_HEADER: Byte = 0x4D // 'M'
    private const val VERSION: Byte = 0x01
    private const val FLAG_PASSWORD_PROTECTED: Byte = 0x01
    private const val FLAG_DEFAULT_PROTECTED: Byte = 0x00

    private val DEFAULT_KEY_SALT =
        byteArrayOf(
            0x59,
            0x75,
            0x7A,
            0x75,
            0x6B,
            0x69,
            0x4D,
            0x65,
            0x6C,
            0x6F,
            0x64,
            0x69,
            0x73,
            0x74,
            0x54,
            0x56,
        )

    private const val DEFAULT_PASSPHRASE = "melodist-playback-token-vault-v1"

    /**
     * 导出播放凭证为 URL-safe Base64 紧凑文本
     */
    fun exportToken(
        profile: UserProfile,
        password: String? = null,
    ): String {
        val cleanCookies = profile.cookies.filterKeys { it in ESSENTIAL_COOKIE_KEYS }
        val payload =
            CompactCredentialsPayload(
                u = profile.uin,
                k = profile.musicKey,
                c = cleanCookies,
                v = profile.isVip,
                n = profile.nick,
                t = System.currentTimeMillis(),
            )
        val jsonStr = jsonHelper.encodeToString(payload)
        val rawBytes = jsonStr.toByteArray(Charsets.UTF_8)
        val compressed = compressDeflate(rawBytes)

        val hasPassword = !password.isNullOrBlank()
        val random = SecureRandom()
        val salt =
            if (hasPassword) {
                ByteArray(16).also { random.nextBytes(it) }
            } else {
                DEFAULT_KEY_SALT
            }

        val secretKey = deriveKey(if (hasPassword) password else DEFAULT_PASSPHRASE, salt)
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(compressed)

        val outSize = 1 + 1 + 1 + (if (hasPassword) 16 else 0) + 12 + encrypted.size
        val bb = ByteBuffer.allocate(outSize)
        bb.put(MAGIC_HEADER)
        bb.put(VERSION)
        bb.put(if (hasPassword) FLAG_PASSWORD_PROTECTED else FLAG_DEFAULT_PROTECTED)
        if (hasPassword) {
            bb.put(salt)
        }
        bb.put(iv)
        bb.put(encrypted)

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bb.array())
    }

    /**
     * 判断凭证是否被密码加密
     */
    fun isTokenPasswordProtected(tokenText: String): Boolean {
        val bytes = decodeTokenBytes(tokenText)
        if (bytes.size < 3 || bytes[0] != MAGIC_HEADER) {
            throw IllegalArgumentException("无效的播放凭证格式")
        }
        return bytes[2] == FLAG_PASSWORD_PROTECTED
    }

    /**
     * 导入并解析播放凭据
     */
    fun importToken(
        tokenText: String,
        password: String? = null,
    ): PlaybackCredentials {
        val bytes = decodeTokenBytes(tokenText)
        if (bytes.size < 3 + 12 + 16 || bytes[0] != MAGIC_HEADER) {
            throw IllegalArgumentException("无效的播放凭证格式")
        }
        val isProtected = bytes[2] == FLAG_PASSWORD_PROTECTED
        val bb = ByteBuffer.wrap(bytes)
        bb.get() // magic
        bb.get() // version
        bb.get() // flag

        val salt =
            if (isProtected) {
                if (password.isNullOrBlank()) {
                    throw IllegalArgumentException("此播放凭据受密码保护，请输入解密密码")
                }
                ByteArray(16).also { bb.get(it) }
            } else {
                DEFAULT_KEY_SALT
            }

        val iv = ByteArray(12).also { bb.get(it) }
        val encrypted = ByteArray(bb.remaining()).also { bb.get(it) }

        val secretKey = deriveKey(if (isProtected) password!! else DEFAULT_PASSPHRASE, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val compressed =
            try {
                cipher.doFinal(encrypted)
            } catch (_: Exception) {
                throw IllegalArgumentException("解密失败，密码错误或凭据已损坏")
            }

        val jsonBytes = decompressDeflate(compressed)
        val jsonStr = String(jsonBytes, Charsets.UTF_8)
        val compact = jsonHelper.decodeFromString<CompactCredentialsPayload>(jsonStr)

        if (compact.u.isBlank() && compact.k.isBlank() && compact.c.isEmpty()) {
            throw IllegalArgumentException("凭据中未包含有效的账号信息")
        }

        val creds =
            PlaybackCredentials(
                uin = compact.u,
                musicKey = compact.k,
                cookies = compact.c,
                isVip = compact.v,
                nick = compact.n,
                updateTime = if (compact.t > 0L) compact.t else System.currentTimeMillis(),
            )
        setCredentials(creds)
        return creds
    }

    private fun decodeTokenBytes(tokenText: String): ByteArray {
        val clean = tokenText.trim()
        return try {
            Base64.getUrlDecoder().decode(clean)
        } catch (_: Exception) {
            Base64.getDecoder().decode(clean)
        }
    }

    private fun deriveKey(
        passphrase: String,
        salt: ByteArray,
    ): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 1000, 256)
        val secretKeyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(secretKeyBytes, "AES")
    }

    private fun compressDeflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val baos = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(512)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            baos.write(buffer, 0, count)
        }
        deflater.end()
        return baos.toByteArray()
    }

    private fun decompressDeflate(input: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(input)
        val baos = ByteArrayOutputStream(input.size * 2)
        val buffer = ByteArray(512)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            baos.write(buffer, 0, count)
        }
        inflater.end()
        return baos.toByteArray()
    }

    fun toJson(): String? = _credentialsFlow.value?.let { jsonHelper.encodeToString(it) }

    fun fromJson(jsonStr: String?) {
        if (jsonStr.isNullOrBlank()) {
            _credentialsFlow.value = null
            return
        }
        try {
            _credentialsFlow.value = jsonHelper.decodeFromString<PlaybackCredentials>(jsonStr)
        } catch (_: Exception) {
            _credentialsFlow.value = null
        }
    }
}
