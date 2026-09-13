package org.melodist.api

import org.melodist.model.AudioQualityTier
import java.nio.charset.Charset

data class ParsedAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val lyrics: String? = null,
    val durationSeconds: Int? = null,
    val sampleRate: Int? = null,
    val bitsPerSample: Int? = null,
    val channels: Int? = null,
    val bitrate: Int? = null,
    val pictureBytes: ByteArray? = null,
    val pictureOffsetInFile: Long? = null,
    val pictureLength: Long? = null,
) {
    fun inferTier(mimeType: String? = null): AudioQualityTier? {
        if (sampleRate == null) return null
        return AudioQualityTier.inferFromAudioFormat(
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample ?: 16,
            channelCount = channels ?: 2,
            mimeType = mimeType ?: "audio/flac",
            bitrate = bitrate ?: 0,
        )
    }
}

/**
 * 轻量纯字节音频头部元数据解析器
 * 针对流式/Range 探测场景，无需完整文件即可解析 FLAC (Vorbis Comment / Picture / StreamInfo) 与 MP3 (ID3v2)，
 * 规避 Android MediaMetadataRetriever 因尾部/大封面截断直接崩溃或报错抛出的问题。
 */
object AudioMetadataParser {
    fun parse(bytes: ByteArray): ParsedAudioMetadata {
        if (bytes.size < 16) return ParsedAudioMetadata()

        // 1. FLAC
        if (bytes[0] == 'f'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() &&
            bytes[3] == 'C'.code.toByte()
        ) {
            return parseFlac(bytes)
        }

        // 2. MP3 (ID3v2)
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return parseId3v2(bytes)
        }

        return ParsedAudioMetadata()
    }

    private fun parseFlac(bytes: ByteArray): ParsedAudioMetadata {
        var offset = 4
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var lyrics: String? = null
        var sampleRate: Int? = null
        var bitsPerSample: Int? = null
        var channels: Int? = null
        var durationSec: Int? = null
        var picBytes: ByteArray? = null
        var picOffset: Long? = null
        var picLen: Long? = null

        while (offset + 4 <= bytes.size) {
            val b0 = bytes[offset].toInt() and 0xFF
            val isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val length =
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)

            val blockStart = offset + 4
            val blockEnd = blockStart + length

            if (blockType == 0 && blockStart + 18 <= bytes.size) { // STREAMINFO
                val b10 = bytes[blockStart + 10].toLong() and 0xFF
                val b11 = bytes[blockStart + 11].toLong() and 0xFF
                val b12 = bytes[blockStart + 12].toLong() and 0xFF
                val b13 = bytes[blockStart + 13].toLong() and 0xFF
                val b14 = bytes[blockStart + 14].toLong() and 0xFF
                val b15 = bytes[blockStart + 15].toLong() and 0xFF
                val b16 = bytes[blockStart + 16].toLong() and 0xFF
                val b17 = bytes[blockStart + 17].toLong() and 0xFF

                val num64 =
                    (b10 shl 56) or (b11 shl 48) or (b12 shl 40) or (b13 shl 32) or
                        (b14 shl 24) or (b15 shl 16) or (b16 shl 8) or b17

                val sRate = (num64 ushr 44).toInt()
                val chs = (((num64 ushr 41) and 0x07).toInt()) + 1
                val bps = (((num64 ushr 36) and 0x1F).toInt()) + 1
                val totalSamples = num64 and 0x0FFFFFFFFFL

                sampleRate = sRate
                channels = chs
                bitsPerSample = bps
                if (sRate > 0 && totalSamples > 0) {
                    durationSec = (totalSamples / sRate).toInt()
                }
            } else if (blockType == 4) { // VORBIS_COMMENT
                val limit = blockEnd.coerceAtMost(bytes.size)
                if (blockStart + 4 <= limit) {
                    val vendorLen = readInt32LE(bytes, blockStart)
                    var pos = blockStart + 4 + vendorLen
                    if (pos + 4 <= limit) {
                        val commentCount = readInt32LE(bytes, pos)
                        pos += 4
                        for (i in 0 until commentCount) {
                            if (pos + 4 > limit) break
                            val commentLen = readInt32LE(bytes, pos)
                            pos += 4
                            if (pos + commentLen > limit) break
                            val commentStr = String(bytes, pos, commentLen, Charsets.UTF_8)
                            pos += commentLen

                            val eqIdx = commentStr.indexOf('=')
                            if (eqIdx > 0) {
                                val key = commentStr.substring(0, eqIdx).trim().uppercase()
                                val value = commentStr.substring(eqIdx + 1).trim()
                                when (key) {
                                    "TITLE" -> if (title.isNullOrBlank()) title = value
                                    "ARTIST" -> if (artist.isNullOrBlank()) artist = value
                                    "ALBUM" -> if (album.isNullOrBlank()) album = value
                                    "LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS" -> {
                                        if (lyrics.isNullOrBlank()) lyrics = value
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (blockType == 6) { // PICTURE
                var pos = blockStart
                if (pos + 8 <= bytes.size) {
                    pos += 4 // skip picture type
                    val mimeLen = readInt32BE(bytes, pos)
                    pos += 4
                    if (pos + mimeLen + 4 <= bytes.size) {
                        pos += mimeLen
                        val descLen = readInt32BE(bytes, pos)
                        pos += 4
                        pos += descLen + 16 // skip description + width(4) + height(4) + depth(4) + colors(4)
                        if (pos + 4 <= bytes.size) {
                            val dataLen = readInt32BE(bytes, pos)
                            pos += 4
                            picOffset = pos.toLong()
                            picLen = dataLen.toLong()
                            if (pos + dataLen <= bytes.size && dataLen > 0) {
                                picBytes = bytes.copyOfRange(pos, pos + dataLen)
                            }
                        }
                    }
                }
            }

            offset = blockEnd
            if (isLast) break
        }

        return ParsedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            lyrics = lyrics,
            durationSeconds = durationSec,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channels = channels,
            pictureBytes = picBytes,
            pictureOffsetInFile = picOffset,
            pictureLength = picLen,
        )
    }

    private fun parseId3v2(bytes: ByteArray): ParsedAudioMetadata {
        val version = bytes[3].toInt() and 0xFF
        val tagSize =
            ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)

        val totalLimit = (10 + tagSize).coerceAtMost(bytes.size)
        var pos = 10

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var lyrics: String? = null
        var picBytes: ByteArray? = null
        var picOffset: Long? = null
        var picLen: Long? = null

        while (pos + 10 <= totalLimit) {
            val frameId = String(bytes, pos, 4, Charsets.ISO_8859_1)
            if (frameId.any { it !in 'A'..'Z' && it !in '0'..'9' }) {
                break // 遇到填充 0x00 结束
            }

            val frameSize =
                if (version == 4) {
                    ((bytes[pos + 4].toInt() and 0x7F) shl 21) or
                        ((bytes[pos + 5].toInt() and 0x7F) shl 14) or
                        ((bytes[pos + 6].toInt() and 0x7F) shl 7) or
                        (bytes[pos + 7].toInt() and 0x7F)
                } else {
                    readInt32BE(bytes, pos + 4)
                }

            pos += 10
            if (frameSize <= 0) continue

            val frameDataLimit = (pos + frameSize).coerceAtMost(bytes.size)
            if (pos < frameDataLimit) {
                when (frameId) {
                    "TIT2" -> {
                        val text = decodeId3Text(bytes, pos, frameDataLimit - pos)
                        if (!text.isNullOrBlank() && title.isNullOrBlank()) title = text
                    }
                    "TPE1" -> {
                        val text = decodeId3Text(bytes, pos, frameDataLimit - pos)
                        if (!text.isNullOrBlank() && artist.isNullOrBlank()) artist = text
                    }
                    "TALB" -> {
                        val text = decodeId3Text(bytes, pos, frameDataLimit - pos)
                        if (!text.isNullOrBlank() && album.isNullOrBlank()) album = text
                    }
                    "USLT" -> {
                        // USLT: encoding(1) + language(3) + desc\0 + lyrics
                        if (frameDataLimit - pos > 4) {
                            val encoding = bytes[pos].toInt() and 0xFF
                            var cursor = pos + 4
                            val charset = getId3Charset(encoding)
                            // 跳过 description
                            cursor = skipId3StringNullTerminator(bytes, cursor, frameDataLimit, encoding)
                            if (cursor < frameDataLimit) {
                                val text =
                                    try {
                                        String(bytes, cursor, frameDataLimit - cursor, charset).trim().trimEnd('\u0000')
                                    } catch (_: Exception) {
                                        null
                                    }
                                if (!text.isNullOrBlank() && lyrics.isNullOrBlank()) lyrics = text
                            }
                        }
                    }
                    "APIC" -> {
                        // APIC: encoding(1) + mime\0 + picType(1) + desc\0 + picData
                        var cursor = pos + 1
                        while (cursor < frameDataLimit && bytes[cursor] != 0.toByte()) {
                            cursor++
                        }
                        cursor++ // skip null
                        if (cursor < frameDataLimit) {
                            cursor++ // skip picType
                            val encoding = bytes[pos].toInt() and 0xFF
                            cursor = skipId3StringNullTerminator(bytes, cursor, frameDataLimit, encoding)
                            if (cursor < frameDataLimit) {
                                val remainingInFile = frameSize - (cursor - pos)
                                picOffset = cursor.toLong()
                                picLen = remainingInFile.toLong()
                                if (cursor + remainingInFile <= bytes.size && remainingInFile > 0) {
                                    picBytes = bytes.copyOfRange(cursor, cursor + remainingInFile)
                                }
                            }
                        }
                    }
                }
            }

            pos += frameSize
        }

        return ParsedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            lyrics = lyrics,
            pictureBytes = picBytes,
            pictureOffsetInFile = picOffset,
            pictureLength = picLen,
        )
    }

    private fun decodeId3Text(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): String? {
        if (length <= 1) return null
        val encoding = bytes[offset].toInt() and 0xFF
        val charset = getId3Charset(encoding)
        return try {
            String(bytes, offset + 1, length - 1, charset).trim().trimEnd('\u0000')
        } catch (_: Exception) {
            null
        }
    }

    private fun getId3Charset(encoding: Int): Charset =
        when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }

    private fun skipId3StringNullTerminator(
        bytes: ByteArray,
        start: Int,
        limit: Int,
        encoding: Int,
    ): Int {
        var cursor = start
        if (encoding == 1 || encoding == 2) {
            while (cursor + 1 < limit) {
                if (bytes[cursor] == 0.toByte() && bytes[cursor + 1] == 0.toByte()) {
                    return cursor + 2
                }
                cursor += 2
            }
        } else {
            while (cursor < limit) {
                if (bytes[cursor] == 0.toByte()) {
                    return cursor + 1
                }
                cursor++
            }
        }
        return cursor
    }

    private fun readInt32LE(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt32BE(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
