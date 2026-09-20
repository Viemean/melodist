package org.melodist.data.pipeline

import android.media.MediaMetadataRetriever
import android.util.Log
import org.melodist.data.CoverCompressor
import java.io.File

data class ParsedAudioMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val pictureBytes: ByteArray? = null,
)

object AudioMetadataPipeline {
    private const val TAG = "AudioMetadataPipeline"

    /**
     * 从文件名推断 (歌曲名, 歌手名)
     */
    fun inferTitleArtist(fileName: String): Pair<String, String> {
        val clean = fileName.substringBeforeLast('.')
        return if (clean.contains(" - ")) {
            val parts = clean.split(" - ", limit = 2)
            val artist = parts[0].trim()
            val title = parts[1].trim()
            Pair(title, artist)
        } else {
            Pair(clean.trim(), "未知歌手")
        }
    }

    /**
     * 从本地音频文件解析元数据与内嵌封面
     */
    fun parseAudioFile(
        file: File,
        retriever: MediaMetadataRetriever? = null,
    ): ParsedAudioMetadata {
        val (inferredTitle, inferredArtist) = inferTitleArtist(file.name)
        val defaultAlbum = file.parentFile?.name ?: "本地音乐"
        val localRetriever = retriever ?: MediaMetadataRetriever()
        val shouldRelease = retriever == null

        return try {
            localRetriever.setDataSource(file.absolutePath)
            parseFromRetriever(
                retriever = localRetriever,
                fallbackTitle = inferredTitle,
                fallbackArtist = inferredArtist,
                fallbackAlbum = defaultAlbum,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting metadata for ${file.name}", e)
            ParsedAudioMetadata(
                title = inferredTitle,
                artist = inferredArtist,
                album = defaultAlbum,
                durationSeconds = 0,
            )
        } finally {
            if (shouldRelease) {
                try {
                    localRetriever.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * 从 MediaMetadataRetriever 中抽取元数据字段与封面
     */
    fun parseFromRetriever(
        retriever: MediaMetadataRetriever,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
    ): ParsedAudioMetadata {
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = fallbackAlbum
        var durationSec = 0

        val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
        val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
        val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()
        val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        val sRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
        val bRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()

        if (!metaTitle.isNullOrBlank()) title = metaTitle
        if (!metaArtist.isNullOrBlank()) artist = metaArtist
        if (!metaAlbum.isNullOrBlank()) album = metaAlbum
        if (!metaDur.isNullOrBlank()) {
            durationSec = (metaDur.toLongOrNull() ?: 0L).toInt() / 1000
        }

        val picBytes = retriever.embeddedPicture

        return ParsedAudioMetadata(
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSec,
            sampleRate = sRate,
            bitrate = bRate,
            pictureBytes = picBytes,
        )
    }

    /**
     * 统一保存 500px WebP 封面缩略图并处理低清替换
     * @return 成功生成或已存在的封面绝对路径；若字节无效或处理失败返回 null
     */
    fun saveThumbnailWebp(
        bytes: ByteArray,
        folder: File,
        baseHash: String,
        prefix: String = "cover_",
    ): String? {
        if (bytes.size < 512) return null
        folder.mkdirs()
        val targetWebp = File(folder, "$prefix$baseHash.webp")
        if (!targetWebp.exists() || targetWebp.length() == 0L || CoverCompressor.isLowResolution(targetWebp)) {
            CoverCompressor.compressToWebp(bytes, targetWebp)
        }
        return if (targetWebp.exists() && targetWebp.length() > 0L) {
            targetWebp.absolutePath
        } else {
            val oldJpg = File(folder, "$prefix$baseHash.jpg")
            if (oldJpg.exists() && oldJpg.length() > 0L) oldJpg.absolutePath else null
        }
    }

    /**
     * 探测同目录下同名 .lrc 歌词文件
     */
    fun detectCompanionLrc(audioFile: File): File? {
        val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
        return if (lrcFile.exists() && lrcFile.isFile && lrcFile.length() > 0L) lrcFile else null
    }

    /**
     * 探测同目录下命名如 cover.jpg/png, folder.jpg/png 等伴随封面
     */
    fun detectCompanionCover(audioFile: File): File? {
        val parent = audioFile.parentFile ?: return null
        val candidates =
            listOf(
                "cover.jpg",
                "cover.png",
                "cover.webp",
                "folder.jpg",
                "folder.png",
                "folder.webp",
                "front.jpg",
                "front.png",
                "front.webp",
            )
        for (name in candidates) {
            val f = File(parent, name)
            if (f.exists() && f.isFile && f.length() > 512L) return f
        }
        return null
    }
}
