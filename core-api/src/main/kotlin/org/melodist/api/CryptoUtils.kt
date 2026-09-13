package org.melodist.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val AG1_REQUEST_KEY =
        byteArrayOf(
            189.toByte(),
            48,
            95,
            16,
            208.toByte(),
            255.toByte(),
            116,
            182.toByte(),
            239.toByte(),
            84,
            218.toByte(),
            184.toByte(),
            53,
            181.toByte(),
            225.toByte(),
            207.toByte(),
        )

    private val AG1_RESPONSE_KEY =
        byteArrayOf(
            122,
            63,
            140.toByte(),
            29,
            94,
            155.toByte(),
            47,
            10,
            108,
            77,
            126,
            139.toByte(),
            31,
            58,
            92,
            157.toByte(),
            14,
            43,
            111,
            74,
            129.toByte(),
        )

    private val PART1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
    private val PART2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val SCRAMBLE_VALUES =
        byteArrayOf(
            89,
            39,
            179.toByte(),
            150.toByte(),
            218.toByte(),
            82,
            58,
            252.toByte(),
            177.toByte(),
            52,
            186.toByte(),
            123,
            120,
            64,
            242.toByte(),
            133.toByte(),
            143.toByte(),
            161.toByte(),
            121,
            179.toByte(),
        )

    /**
     * 计算现代网关 zzc 签名
     */
    fun computeZzcSign(text: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val hashBytes = sha1.digest(text.toByteArray(Charsets.UTF_8))
        val hex = hashBytes.joinToString("") { "%02X".format(it) }

        val p1 =
            CharArray(PART1_INDEXES.size) { i ->
                val idx = PART1_INDEXES[i]
                if (idx < hex.length) hex[idx] else '0'
            }

        val p2 =
            CharArray(PART2_INDEXES.size) { i ->
                val idx = PART2_INDEXES[i]
                if (idx < hex.length) hex[idx] else '0'
            }

        val part3 =
            ByteArray(SCRAMBLE_VALUES.size) { i ->
                val b =
                    if (i * 2 + 1 < hex.length) {
                        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    } else {
                        0.toByte()
                    }
                (SCRAMBLE_VALUES[i].toInt() xor b.toInt()).toByte()
            }

        val b64Part =
            Base64
                .getEncoder()
                .encodeToString(part3)
                .replace("/", "")
                .replace("+", "")
                .replace("=", "")

        return "zzc${String(p1)}$b64Part${String(p2)}".lowercase()
    }

    /**
     * 对明文 JSON 使用 AG-1（AES-128-GCM）进行载荷加密
     */
    fun encryptAg1Request(jsonPayload: String): String {
        val plainBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)

        val keySpec = SecretKeySpec(AG1_REQUEST_KEY, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce) // 128 bit tag = 16 bytes
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val cipherBytes = cipher.doFinal(plainBytes)

        val finalData = ByteArray(nonce.size + cipherBytes.size)
        System.arraycopy(nonce, 0, finalData, 0, nonce.size)
        System.arraycopy(cipherBytes, 0, finalData, nonce.size, cipherBytes.size)

        return Base64.getEncoder().encodeToString(finalData)
    }

    /**
     * 解密 AG-1 响应密文流
     */
    fun decryptAg1Response(responseBytes: ByteArray): String {
        val decrypted =
            ByteArray(responseBytes.size) { i ->
                (responseBytes[i].toInt() xor AG1_RESPONSE_KEY[i % AG1_RESPONSE_KEY.size].toInt()).toByte()
            }
        return String(decrypted, Charsets.UTF_8)
    }
}
