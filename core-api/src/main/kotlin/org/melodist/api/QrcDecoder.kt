package org.melodist.api

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object QrcDecoder {
    private const val DES_ENCRYPT = 1
    private const val DES_DECRYPT = 0

    // 非标准 DES 变体 S-Box：S2[23]=15, S4[53]=10
    private val SBOX =
        arrayOf(
            intArrayOf(
                14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
            ),
            intArrayOf(
                15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
                0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
            ),
            intArrayOf(
                10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
            ),
            intArrayOf(
                7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
            ),
            intArrayOf(
                2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
            ),
            intArrayOf(
                12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
            ),
            intArrayOf(
                4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
            ),
            intArrayOf(
                13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
            ),
        )

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    private val KEY_PERM_C =
        intArrayOf(
            56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
        )

    private val KEY_PERM_D =
        intArrayOf(
            62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
        )

    private val KEY_COMPRESSION =
        intArrayOf(
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46,
            54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
        )

    // QRC 固定密钥：!@#)(*$%123ZXC!@!@#)(NHL
    private val QRC_KEY =
        byteArrayOf(
            0x21, 0x40, 0x23, 0x29, 0x28, 0x2a, 0x24, 0x25, 0x31, 0x32, 0x33, 0x5a,
            0x58, 0x43, 0x21, 0x40, 0x21, 0x40, 0x23, 0x29, 0x28, 0x4e, 0x48, 0x4c,
        )

    private fun bitnum(arr: ByteArray, b: Int, c: Int): Int {
        val byteIndex = (b / 32) * 4 + 3 - ((b % 32) / 8)
        val bitShift = 7 - (b % 8)
        val bit = ((arr[byteIndex].toInt() and 0xff) ushr bitShift) and 1
        return bit shl c
    }

    private fun bitnumIntr(a: Int, b: Int, c: Int): Int = (((a ushr (31 - b)) and 1) shl c)

    private fun bitnumIntl(a: Int, b: Int, c: Int): Int = (((a shl b) and 0x80000000.toInt()) ushr c)

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    private fun initialPermutation(input: ByteArray, offset: Int): Pair<Int, Int> {
        val slice = ByteArray(8)
        System.arraycopy(input, offset, slice, 0, 8)
        val s0 =
            bitnum(slice, 57, 31) or bitnum(slice, 49, 30) or bitnum(slice, 41, 29) or bitnum(slice, 33, 28) or
                bitnum(slice, 25, 27) or bitnum(slice, 17, 26) or bitnum(slice, 9, 25) or bitnum(slice, 1, 24) or
                bitnum(slice, 59, 23) or bitnum(slice, 51, 22) or bitnum(slice, 43, 21) or bitnum(slice, 35, 20) or
                bitnum(slice, 27, 19) or bitnum(slice, 19, 18) or bitnum(slice, 11, 17) or bitnum(slice, 3, 16) or
                bitnum(slice, 61, 15) or bitnum(slice, 53, 14) or bitnum(slice, 45, 13) or bitnum(slice, 37, 12) or
                bitnum(slice, 29, 11) or bitnum(slice, 21, 10) or bitnum(slice, 13, 9) or bitnum(slice, 5, 8) or
                bitnum(slice, 63, 7) or bitnum(slice, 55, 6) or bitnum(slice, 47, 5) or bitnum(slice, 39, 4) or
                bitnum(slice, 31, 3) or bitnum(slice, 23, 2) or bitnum(slice, 15, 1) or bitnum(slice, 7, 0)

        val s1 =
            bitnum(slice, 56, 31) or bitnum(slice, 48, 30) or bitnum(slice, 40, 29) or bitnum(slice, 32, 28) or
                bitnum(slice, 24, 27) or bitnum(slice, 16, 26) or bitnum(slice, 8, 25) or bitnum(slice, 0, 24) or
                bitnum(slice, 58, 23) or bitnum(slice, 50, 22) or bitnum(slice, 42, 21) or bitnum(slice, 34, 20) or
                bitnum(slice, 26, 19) or bitnum(slice, 18, 18) or bitnum(slice, 10, 17) or bitnum(slice, 2, 16) or
                bitnum(slice, 60, 15) or bitnum(slice, 52, 14) or bitnum(slice, 44, 13) or bitnum(slice, 36, 12) or
                bitnum(slice, 28, 11) or bitnum(slice, 20, 10) or bitnum(slice, 12, 9) or bitnum(slice, 4, 8) or
                bitnum(slice, 62, 7) or bitnum(slice, 54, 6) or bitnum(slice, 46, 5) or bitnum(slice, 38, 4) or
                bitnum(slice, 30, 3) or bitnum(slice, 22, 2) or bitnum(slice, 14, 1) or bitnum(slice, 6, 0)

        return Pair(s0, s1)
    }

    private fun inversePermutation(s0: Int, s1: Int, out: ByteArray, offset: Int) {
        out[offset + 3] = (bitnumIntr(s1, 7, 7) or bitnumIntr(s0, 7, 6) or bitnumIntr(s1, 15, 5) or bitnumIntr(s0, 15, 4) or bitnumIntr(s1, 23, 3) or bitnumIntr(s0, 23, 2) or bitnumIntr(s1, 31, 1) or bitnumIntr(s0, 31, 0)).toByte()
        out[offset + 2] = (bitnumIntr(s1, 6, 7) or bitnumIntr(s0, 6, 6) or bitnumIntr(s1, 14, 5) or bitnumIntr(s0, 14, 4) or bitnumIntr(s1, 22, 3) or bitnumIntr(s0, 22, 2) or bitnumIntr(s1, 30, 1) or bitnumIntr(s0, 30, 0)).toByte()
        out[offset + 1] = (bitnumIntr(s1, 5, 7) or bitnumIntr(s0, 5, 6) or bitnumIntr(s1, 13, 5) or bitnumIntr(s0, 13, 4) or bitnumIntr(s1, 21, 3) or bitnumIntr(s0, 21, 2) or bitnumIntr(s1, 29, 1) or bitnumIntr(s0, 29, 0)).toByte()
        out[offset + 0] = (bitnumIntr(s1, 4, 7) or bitnumIntr(s0, 4, 6) or bitnumIntr(s1, 12, 5) or bitnumIntr(s0, 12, 4) or bitnumIntr(s1, 20, 3) or bitnumIntr(s0, 20, 2) or bitnumIntr(s1, 28, 1) or bitnumIntr(s0, 28, 0)).toByte()
        out[offset + 7] = (bitnumIntr(s1, 3, 7) or bitnumIntr(s0, 3, 6) or bitnumIntr(s1, 11, 5) or bitnumIntr(s0, 11, 4) or bitnumIntr(s1, 19, 3) or bitnumIntr(s0, 19, 2) or bitnumIntr(s1, 27, 1) or bitnumIntr(s0, 27, 0)).toByte()
        out[offset + 6] = (bitnumIntr(s1, 2, 7) or bitnumIntr(s0, 2, 6) or bitnumIntr(s1, 10, 5) or bitnumIntr(s0, 10, 4) or bitnumIntr(s1, 18, 3) or bitnumIntr(s0, 18, 2) or bitnumIntr(s1, 26, 1) or bitnumIntr(s0, 26, 0)).toByte()
        out[offset + 5] = (bitnumIntr(s1, 1, 7) or bitnumIntr(s0, 1, 6) or bitnumIntr(s1, 9, 5) or bitnumIntr(s0, 9, 4) or bitnumIntr(s1, 17, 3) or bitnumIntr(s0, 17, 2) or bitnumIntr(s1, 25, 1) or bitnumIntr(s0, 25, 0)).toByte()
        out[offset + 4] = (bitnumIntr(s1, 0, 7) or bitnumIntr(s0, 0, 6) or bitnumIntr(s1, 8, 5) or bitnumIntr(s0, 8, 4) or bitnumIntr(s1, 16, 3) or bitnumIntr(s0, 16, 2) or bitnumIntr(s1, 24, 1) or bitnumIntr(s0, 24, 0)).toByte()
    }

    private fun desF(state: Int, key: IntArray): Int {
        val t1 =
            bitnumIntl(state, 31, 0) or ((state and 0xF0000000.toInt()) ushr 1) or bitnumIntl(state, 4, 5) or
                bitnumIntl(state, 3, 6) or ((state and 0x0F000000) ushr 3) or bitnumIntl(state, 8, 11) or
                bitnumIntl(state, 7, 12) or ((state and 0x00F00000) ushr 5) or bitnumIntl(state, 12, 17) or
                bitnumIntl(state, 11, 18) or ((state and 0x000F0000) ushr 7) or bitnumIntl(state, 16, 23)

        val t2 =
            bitnumIntl(state, 15, 0) or ((state and 0x0000F000) shl 15) or bitnumIntl(state, 20, 5) or
                bitnumIntl(state, 19, 6) or ((state and 0x00000F00) shl 13) or bitnumIntl(state, 24, 11) or
                bitnumIntl(state, 23, 12) or ((state and 0x000000F0) shl 11) or bitnumIntl(state, 28, 17) or
                bitnumIntl(state, 27, 18) or ((state and 0x0000000F) shl 9) or bitnumIntl(state, 0, 23)

        val lrgstate =
            intArrayOf(
                (t1 ushr 24) and 0xFF,
                (t1 ushr 16) and 0xFF,
                (t1 ushr 8) and 0xFF,
                (t2 ushr 24) and 0xFF,
                (t2 ushr 16) and 0xFF,
                (t2 ushr 8) and 0xFF,
            )
        for (i in 0 until 6) {
            lrgstate[i] = lrgstate[i] xor key[i]
        }

        val s =
            (SBOX[0][sboxBit(lrgstate[0] ushr 2)] shl 28) or
                (SBOX[1][sboxBit(((lrgstate[0] and 0x03) shl 4) or (lrgstate[1] ushr 4))] shl 24) or
                (SBOX[2][sboxBit(((lrgstate[1] and 0x0F) shl 2) or (lrgstate[2] ushr 6))] shl 20) or
                (SBOX[3][sboxBit(lrgstate[2] and 0x3F)] shl 16) or
                (SBOX[4][sboxBit(lrgstate[3] ushr 2)] shl 12) or
                (SBOX[5][sboxBit(((lrgstate[3] and 0x03) shl 4) or (lrgstate[4] ushr 4))] shl 8) or
                (SBOX[6][sboxBit(((lrgstate[4] and 0x0F) shl 2) or (lrgstate[5] ushr 6))] shl 4) or
                SBOX[7][sboxBit(lrgstate[5] and 0x3F)]

        return bitnumIntl(s, 15, 0) or bitnumIntl(s, 6, 1) or bitnumIntl(s, 19, 2) or bitnumIntl(s, 20, 3) or
            bitnumIntl(s, 28, 4) or bitnumIntl(s, 11, 5) or bitnumIntl(s, 27, 6) or bitnumIntl(s, 16, 7) or
            bitnumIntl(s, 0, 8) or bitnumIntl(s, 14, 9) or bitnumIntl(s, 22, 10) or bitnumIntl(s, 25, 11) or
            bitnumIntl(s, 4, 12) or bitnumIntl(s, 17, 13) or bitnumIntl(s, 30, 14) or bitnumIntl(s, 9, 15) or
            bitnumIntl(s, 1, 16) or bitnumIntl(s, 7, 17) or bitnumIntl(s, 23, 18) or bitnumIntl(s, 13, 19) or
            bitnumIntl(s, 31, 20) or bitnumIntl(s, 26, 21) or bitnumIntl(s, 2, 22) or bitnumIntl(s, 8, 23) or
            bitnumIntl(s, 18, 24) or bitnumIntl(s, 12, 25) or bitnumIntl(s, 29, 26) or bitnumIntl(s, 5, 27) or
            bitnumIntl(s, 21, 28) or bitnumIntl(s, 10, 29) or bitnumIntl(s, 3, 30) or bitnumIntl(s, 24, 31)
    }

    private fun desCrypt(input: ByteArray, inOffset: Int, schedule: Array<IntArray>, output: ByteArray, outOffset: Int) {
        val (initialS0, initialS1) = initialPermutation(input, inOffset)
        var s0 = initialS0
        var s1 = initialS1
        for (i in 0 until 15) {
            val prev = s1
            s1 = desF(s1, schedule[i]) xor s0
            s0 = prev
        }
        s0 = desF(s1, schedule[15]) xor s0
        inversePermutation(s0, s1, output, outOffset)
    }

    private fun keySchedule(key: ByteArray, keyOffset: Int, mode: Int): Array<IntArray> {
        val keySlice = ByteArray(8)
        System.arraycopy(key, keyOffset, keySlice, 0, 8)
        val schedule = Array(16) { IntArray(6) }
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitnum(keySlice, KEY_PERM_C[i], 31 - i)
            d = d or bitnum(keySlice, KEY_PERM_D[i], 31 - i)
        }
        for (i in 0 until 16) {
            c = (((c shl KEY_RND_SHIFT[i]) or (c ushr (28 - KEY_RND_SHIFT[i]))) and 0xFFFFFFF0.toInt())
            d = (((d shl KEY_RND_SHIFT[i]) or (d ushr (28 - KEY_RND_SHIFT[i]))) and 0xFFFFFFF0.toInt())
            val togen = if (mode == DES_DECRYPT) 15 - i else i
            for (j in 0 until 24) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8))
            }
            for (j in 24 until 48) {
                schedule[togen][j / 8] = schedule[togen][j / 8] or bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8))
            }
        }
        return schedule
    }

    private fun tripleDesKeySetup(key: ByteArray, mode: Int): Array<Array<IntArray>> {
        return if (mode == DES_ENCRYPT) {
            arrayOf(
                keySchedule(key, 0, DES_ENCRYPT),
                keySchedule(key, 8, DES_DECRYPT),
                keySchedule(key, 16, DES_ENCRYPT),
            )
        } else {
            arrayOf(
                keySchedule(key, 16, DES_DECRYPT),
                keySchedule(key, 8, DES_ENCRYPT),
                keySchedule(key, 0, DES_DECRYPT),
            )
        }
    }

    private fun tripleDesCrypt(input: ByteArray, inOffset: Int, schedule: Array<Array<IntArray>>, output: ByteArray, outOffset: Int) {
        val buf1 = ByteArray(8)
        val buf2 = ByteArray(8)
        desCrypt(input, inOffset, schedule[0], buf1, 0)
        desCrypt(buf1, 0, schedule[1], buf2, 0)
        desCrypt(buf2, 0, schedule[2], output, outOffset)
    }

    private val cachedDecryptSchedule = tripleDesKeySetup(QRC_KEY, DES_DECRYPT)

    /**
     * 解密 QRC 十六进制密文字符串并使用 Inflater 解压缩为 XML/QRC 明文。
     *
     * @param hexData 十六进制加密字符串
     * @return 解密并解压后的 UTF-8 歌词文本，失败返回空字符串
     */
    fun decodeQrc(hexData: String?): String {
        if (hexData.isNullOrBlank() || hexData.length % 2 != 0) return ""
        val encrypted =
            try {
                hexStringToByteArray(hexData.trim())
            } catch (_: Exception) {
                return ""
            }
        if (encrypted.isEmpty()) return ""

        val decrypted = ByteArray(encrypted.size)
        val blockCount = encrypted.size / 8
        for (i in 0 until blockCount) {
            tripleDesCrypt(encrypted, i * 8, cachedDecryptSchedule, decrypted, i * 8)
        }

        return try {
            val inflater = Inflater()
            inflater.setInput(decrypted)
            val outputStream = ByteArrayOutputStream(decrypted.size * 2)
            val buffer = ByteArray(4096)
            while (!inflater.finished() && !inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count == 0) break
                outputStream.write(buffer, 0, count)
            }
            if (outputStream.size() == 0 && inflater.needsInput()) {
                // 部分流末尾缺失校验位，尝试获取已解压部分
                val count = inflater.inflate(buffer)
                if (count > 0) outputStream.write(buffer, 0, count)
            }
            inflater.end()
            outputStream.toString("UTF-8")
        } catch (_: Exception) {
            ""
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
