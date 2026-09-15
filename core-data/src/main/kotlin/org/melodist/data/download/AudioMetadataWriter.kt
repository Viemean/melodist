package org.melodist.data.download

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioMetadataWriter {
    private const val TAG = "AudioMetadataWriter"

    data class MetadataPayload(
        val title: String,
        val artist: String,
        val album: String,
        val lyrics: String? = null,
        val coverBytes: ByteArray? = null,
        val coverMime: String = "image/jpeg",
    )

    /**
     * 将元数据（信息、专辑封面原图、双语歌词）直接内嵌写入音频文件
     */
    fun writeMetadata(
        audioFile: File,
        payload: MetadataPayload,
    ): Boolean {
        if (!audioFile.exists() || !audioFile.isFile || audioFile.length() <= 0L) {
            return false
        }

        val ext = audioFile.extension.lowercase()
        return try {
            when (ext) {
                "flac" -> writeFlacMetadata(audioFile, payload)
                "mp3" -> writeMp3Metadata(audioFile, payload)
                else -> {
                    // 其他格式（如 m4a / ogg / wav），若为 mp3 内容也尝试 mp3 写入，否则仅保留原样
                    if (isMp3Stream(audioFile)) {
                        writeMp3Metadata(audioFile, payload)
                    } else if (isFlacStream(audioFile)) {
                        writeFlacMetadata(audioFile, payload)
                    } else {
                        Log.i(TAG, "Unsupported container format: $ext for tag embedding")
                        true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write metadata for ${audioFile.name}", e)
            false
        }
    }

    private fun isFlacStream(file: File): Boolean =
        try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                if (fis.read(magic) == 4) {
                    magic[0] == 'f'.code.toByte() &&
                        magic[1] == 'L'.code.toByte() &&
                        magic[2] == 'a'.code.toByte() &&
                        magic[3] == 'C'.code.toByte()
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }

    private fun isMp3Stream(file: File): Boolean =
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(3)
                if (fis.read(header) == 3) {
                    (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
                        (header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0)
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }

    // ==========================================
    // FLAC Vorbis Comment & Picture Block 写入
    // ==========================================

    private fun writeFlacMetadata(
        file: File,
        payload: MetadataPayload,
    ): Boolean {
        val tempFile = File(file.parentFile, "${file.name}.meta_tmp")
        try {
            FileInputStream(file).use { fis ->
                // 1. 校验 fLaC 魔数
                val magic = ByteArray(4)
                if (fis.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") {
                    Log.w(TAG, "Not a valid FLAC stream: ${file.name}")
                    return false
                }

                // 2. 读取现有 block，提取必须保留的 STREAMINFO 与其他非 VORBIS/PICTURE 块
                var streamInfoBlock: ByteArray? = null
                val otherBlocks = mutableListOf<ByteArray>()

                var isLast = false
                while (!isLast) {
                    val header = ByteArray(4)
                    if (fis.read(header) != 4) break
                    isLast = (header[0].toInt() and 0x80) != 0
                    val blockType = header[0].toInt() and 0x7F
                    val length =
                        ((header[1].toInt() and 0xFF) shl 16) or
                            ((header[2].toInt() and 0xFF) shl 8) or
                            (header[3].toInt() and 0xFF)

                    val data = ByteArray(length)
                    var readLen = 0
                    while (readLen < length) {
                        val r = fis.read(data, readLen, length - readLen)
                        if (r <= 0) break
                        readLen += r
                    }

                    when (blockType) {
                        0 -> streamInfoBlock = data // STREAMINFO
                        4, 6 -> {
                            // 旧的 VORBIS_COMMENT 或 PICTURE，丢弃替换为最新
                        }
                        else -> {
                            // 保留其他元数据块（如 SEEKTABLE）
                            val fullBlock = ByteArray(4 + length)
                            System.arraycopy(header, 0, fullBlock, 0, 4)
                            fullBlock[0] = (fullBlock[0].toInt() and 0x7F).toByte() // 先清空 isLast
                            System.arraycopy(data, 0, fullBlock, 4, length)
                            otherBlocks.add(fullBlock)
                        }
                    }
                }

                if (streamInfoBlock == null) {
                    Log.w(TAG, "STREAMINFO block missing in ${file.name}")
                    return false
                }

                // 3. 构建新的 VORBIS_COMMENT block
                val vorbisBlockData = buildFlacVorbisComment(payload)
                // 4. 构建新的 PICTURE block (若有)
                val pictureBlockData = payload.coverBytes?.let { buildFlacPictureBlock(it, payload.coverMime) }

                // 组装所有元数据块
                val blocksToWrite = mutableListOf<ByteArray>()

                // STREAMINFO
                blocksToWrite.add(makeFlacBlock(0, streamInfoBlock))
                // 其他块
                blocksToWrite.addAll(otherBlocks)
                // VORBIS_COMMENT
                blocksToWrite.add(makeFlacBlock(4, vorbisBlockData))
                // PICTURE
                if (pictureBlockData != null) {
                    blocksToWrite.add(makeFlacBlock(6, pictureBlockData))
                }

                // 标记最后一个 block 的 isLast bit
                if (blocksToWrite.isNotEmpty()) {
                    val lastIdx = blocksToWrite.lastIndex
                    val lastBlock = blocksToWrite[lastIdx]
                    lastBlock[0] = (lastBlock[0].toInt() or 0x80).toByte()
                }

                // 5. 写入临时文件并拼接后续音频数据帧
                FileOutputStream(tempFile).use { fos ->
                    fos.write("fLaC".toByteArray(Charsets.US_ASCII))
                    for (b in blocksToWrite) {
                        fos.write(b)
                    }

                    // 复制纯音频数据
                    val buf = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (fis.read(buf).also { bytesRead = it } != -1) {
                        fos.write(buf, 0, bytesRead)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (file.delete()) {
                    return tempFile.renameTo(file)
                }
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Error writing FLAC tags", e)
            tempFile.delete()
            return false
        }
    }

    private fun makeFlacBlock(
        type: Int,
        data: ByteArray,
    ): ByteArray {
        val len = data.size
        val block = ByteArray(4 + len)
        block[0] = (type and 0x7F).toByte()
        block[1] = ((len shr 16) and 0xFF).toByte()
        block[2] = ((len shr 8) and 0xFF).toByte()
        block[3] = (len and 0xFF).toByte()
        System.arraycopy(data, 0, block, 4, len)
        return block
    }

    private fun buildFlacVorbisComment(payload: MetadataPayload): ByteArray {
        val comments = mutableListOf<String>()
        if (payload.title.isNotBlank()) comments.add("TITLE=${payload.title}")
        if (payload.artist.isNotBlank()) comments.add("ARTIST=${payload.artist}")
        if (payload.album.isNotBlank()) comments.add("ALBUM=${payload.album}")
        if (!payload.lyrics.isNullOrBlank()) comments.add("LYRICS=${payload.lyrics}")
        comments.add("ENCODER=Melodist")

        val vendor = "Melodist FLAC Writer".toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()

        // 32-bit little-endian vendor length + vendor
        baos.write(
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(vendor.size)
                .array(),
        )
        baos.write(vendor)

        // 32-bit little-endian user comment count
        baos.write(
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(comments.size)
                .array(),
        )

        for (c in comments) {
            val cBytes = c.toByteArray(Charsets.UTF_8)
            baos.write(
                ByteBuffer
                    .allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(cBytes.size)
                    .array(),
            )
            baos.write(cBytes)
        }

        return baos.toByteArray()
    }

    private fun buildFlacPictureBlock(
        coverBytes: ByteArray,
        mimeType: String,
    ): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        val descBytes = ByteArray(0)

        val baos = ByteArrayOutputStream()
        val bb = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)

        // picture type = 3 (Front cover)
        bb.putInt(3)
        // mime length
        bb.putInt(mimeBytes.size)
        baos.write(bb.array(), 0, 8)
        baos.write(mimeBytes)

        bb.clear()
        // description length
        bb.putInt(descBytes.size)
        // width, height, depth, colors (0 = unset)
        bb.putInt(0)
        bb.putInt(0)
        bb.putInt(24)
        bb.putInt(0)
        // picture data length
        bb.putInt(coverBytes.size)
        baos.write(bb.array(), 0, 24)
        baos.write(coverBytes)

        return baos.toByteArray()
    }

    // ==========================================
    // MP3 ID3v2.3 规范写入 (兼容所有设备与播放器)
    // ==========================================

    private fun writeMp3Metadata(
        file: File,
        payload: MetadataPayload,
    ): Boolean {
        val tempFile = File(file.parentFile, "${file.name}.meta_tmp")
        try {
            FileInputStream(file).use { fis ->
                // 检测是否存在旧 ID3v2 头部
                val head = ByteArray(10)
                var oldId3Size = 0L
                if (fis.read(head) == 10) {
                    if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                        // 计算 syncsafe 尺寸
                        val s0 = head[6].toInt() and 0x7F
                        val s1 = head[7].toInt() and 0x7F
                        val s2 = head[8].toInt() and 0x7F
                        val s3 = head[9].toInt() and 0x7F
                        val tagBodySize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
                        oldId3Size = 10L + tagBodySize
                        // 跳过旧 ID3v2
                        fis.skip(tagBodySize.toLong())
                    } else {
                        // 无旧 ID3v2，重新从文件头读取
                        fis.channel.position(0)
                    }
                } else {
                    fis.channel.position(0)
                }

                // 2. 生成新 ID3v2.3 标签二进制
                val newId3Block = buildId3v23Tag(payload)

                // 3. 写入临时文件
                FileOutputStream(tempFile).use { fos ->
                    fos.write(newId3Block)

                    // 复制纯音频帧
                    val buf = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (fis.read(buf).also { bytesRead = it } != -1) {
                        fos.write(buf, 0, bytesRead)
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 0L) {
                if (file.delete()) {
                    return tempFile.renameTo(file)
                }
            }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Error writing MP3 tags", e)
            tempFile.delete()
            return false
        }
    }

    private fun buildId3v23Tag(payload: MetadataPayload): ByteArray {
        val framesBaos = ByteArrayOutputStream()

        // TIT2 (Title)
        if (payload.title.isNotBlank()) {
            framesBaos.write(buildTextFrame("TIT2", payload.title))
        }
        // TPE1 (Artist)
        if (payload.artist.isNotBlank()) {
            framesBaos.write(buildTextFrame("TPE1", payload.artist))
        }
        // TALB (Album)
        if (payload.album.isNotBlank()) {
            framesBaos.write(buildTextFrame("TALB", payload.album))
        }
        // USLT (Lyrics)
        if (!payload.lyrics.isNullOrBlank()) {
            framesBaos.write(buildLyricsFrame(payload.lyrics))
        }
        // APIC (Cover Picture)
        if (payload.coverBytes != null && payload.coverBytes.isNotEmpty()) {
            framesBaos.write(buildPictureFrame(payload.coverBytes, payload.coverMime))
        }

        val frameBytes = framesBaos.toByteArray()
        val tagSize = frameBytes.size

        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 0x03 // v2.3
        header[4] = 0x00
        header[5] = 0x00 // flags
        // syncsafe size (7-bit per byte)
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()

        val fullTag = ByteArray(10 + tagSize)
        System.arraycopy(header, 0, fullTag, 0, 10)
        System.arraycopy(frameBytes, 0, fullTag, 10, tagSize)
        return fullTag
    }

    private fun buildTextFrame(
        frameId: String,
        text: String,
    ): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val dataLen = 1 + textBytes.size // 1 字节 encoding (0x03 = UTF-8)

        val baos = ByteArrayOutputStream()
        baos.write(frameId.toByteArray(Charsets.US_ASCII))
        baos.write(
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(dataLen)
                .array(),
        )
        baos.write(byteArrayOf(0x00, 0x00)) // flags
        baos.write(0x03) // UTF-8
        baos.write(textBytes)
        return baos.toByteArray()
    }

    private fun buildLyricsFrame(lyricsText: String): ByteArray {
        val textBytes = lyricsText.toByteArray(Charsets.UTF_8)
        // 1 字节 encoding (0x03) + 3 字节 lang ("chi") + 1 字节 desc terminator (0x00) + text
        val dataLen = 1 + 3 + 1 + textBytes.size

        val baos = ByteArrayOutputStream()
        baos.write("USLT".toByteArray(Charsets.US_ASCII))
        baos.write(
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(dataLen)
                .array(),
        )
        baos.write(byteArrayOf(0x00, 0x00)) // flags
        baos.write(0x03) // encoding UTF-8
        baos.write("chi".toByteArray(Charsets.US_ASCII)) // language
        baos.write(0x00) // content descriptor terminator
        baos.write(textBytes)
        return baos.toByteArray()
    }

    private fun buildPictureFrame(
        coverBytes: ByteArray,
        mimeType: String,
    ): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.US_ASCII)
        // 1 字节 encoding (0x00 = ISO-8859-1) + mimeBytes + 1 字节 0x00 + 1 字节 picType (0x03) + 1 字节 desc null (0x00) + pictureBytes
        val dataLen = 1 + mimeBytes.size + 1 + 1 + 1 + coverBytes.size

        val baos = ByteArrayOutputStream()
        baos.write("APIC".toByteArray(Charsets.US_ASCII))
        baos.write(
            ByteBuffer
                .allocate(4)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(dataLen)
                .array(),
        )
        baos.write(byteArrayOf(0x00, 0x00)) // flags
        baos.write(0x00) // encoding
        baos.write(mimeBytes)
        baos.write(0x00) // mime terminator
        baos.write(0x03) // picture type: 0x03 = Cover (front)
        baos.write(0x00) // desc terminator
        baos.write(coverBytes)
        return baos.toByteArray()
    }
}
