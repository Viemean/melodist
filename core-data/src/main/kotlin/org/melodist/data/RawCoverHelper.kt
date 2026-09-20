package org.melodist.data

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object RawCoverHelper {
    private const val TAG = "RawCoverHelper"
    private const val MAX_RAW_COVER_BYTES = 20 * 1024 * 1024 // 20MB 上限

    /**
     * 根据图片二进制头部魔数识别真实格式
     */
    fun detectExtension(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            (bytes[0] == 0xFF.toByte()) &&
            (bytes[1] == 0xD8.toByte()) &&
            (bytes[2] == 0xFF.toByte())
        ) {
            return "jpg"
        }
        if (bytes.size >= 8 &&
            (bytes[0] == 0x89.toByte()) &&
            (bytes[1] == 0x50.toByte()) &&
            (bytes[2] == 0x4E.toByte()) &&
            (bytes[3] == 0x47.toByte())
        ) {
            return "png"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "webp"
        }
        return "jpg"
    }

    /**
     * 无损原样写入原始图片字节（原子写入，零二次压缩损耗）
     */
    fun saveRawCover(
        bytes: ByteArray,
        folder: File,
        baseName: String,
    ): File? {
        if (bytes.size < 512 || bytes.size > MAX_RAW_COVER_BYTES) return null
        folder.mkdirs()
        val ext = detectExtension(bytes)
        val targetFile = File(folder, "$baseName.$ext")
        if (targetFile.exists() && targetFile.length() > 0L) {
            return targetFile
        }

        val tempFile = File(folder, "$baseName.tmp_${System.nanoTime()}")
        return try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            if (tempFile.exists() && tempFile.length() == bytes.size.toLong()) {
                if (targetFile.exists()) targetFile.delete()
                if (tempFile.renameTo(targetFile)) {
                    targetFile
                } else {
                    tempFile.delete()
                    null
                }
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save raw cover", e)
            tempFile.delete()
            null
        }
    }

    /**
     * 给定缩略图路径（如 file:///.../cover_$hash.webp 或 webdav_$hash.webp），
     * 智能探测其同级目录下是否存在无损原画大图
     */
    fun findMatchingRawCoverUrl(thumbnailUrl: String): String? {
        val path =
            if (thumbnailUrl.startsWith("file://")) {
                thumbnailUrl.removePrefix("file://")
            } else if (thumbnailUrl.startsWith("/")) {
                thumbnailUrl
            } else {
                return null
            }

        val thumbFile = File(path)
        val parent = thumbFile.parentFile ?: return null
        val name = thumbFile.name

        val baseHash =
            when {
                name.startsWith("cover_") && !name.startsWith("cover_raw_") -> {
                    name.removePrefix("cover_").substringBeforeLast(".")
                }
                name.startsWith("webdav_") && !name.startsWith("webdav_raw_") -> {
                    name.removePrefix("webdav_").substringBeforeLast(".")
                }
                else -> return null
            }

        val prefix = if (name.startsWith("webdav_")) "webdav_raw_" else "cover_raw_"
        val candidateExtensions = listOf("jpg", "png", "webp", "jpeg")
        for (ext in candidateExtensions) {
            val candidate = File(parent, "$prefix$baseHash.$ext")
            if (candidate.exists() && candidate.length() > 512L) {
                return "file://${candidate.absolutePath}"
            }
        }
        return null
    }

    /**
     * 针对本地音频文件，尝试从内嵌元数据直接提取高清原图
     */
    fun extractRawCoverFromAudio(
        audioFilePath: String,
        coversFolder: File,
        hash: String,
    ): File? {
        val audioFile = File(audioFilePath)
        if (!audioFile.exists() || !audioFile.isFile) return null
        val existing = findMatchingRawCoverUrl("file://${coversFolder.absolutePath}/cover_$hash.webp")
        if (!existing.isNullOrBlank()) {
            return File(existing.removePrefix("file://"))
        }

        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(audioFilePath)
            val picBytes = retriever.embeddedPicture
            retriever.release()
            if (picBytes != null && picBytes.size > 512) {
                saveRawCover(picBytes, coversFolder, "cover_raw_$hash")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract raw cover from audio: $audioFilePath", e)
            null
        }
    }
}
