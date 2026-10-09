package org.melodist.playback

import androidx.media3.common.MediaMetadata
import org.melodist.model.Song

/**
 * 本地与 WebDAV 音频元数据轻量清洗与合并器。
 * 纯内存轻量处理 ExoPlayer 原生解出的 MediaMetadata，无额外网络与文件 I/O 开销。
 */
object PlaybackMetadataCoordinator {
    // 1. 带明确标点（. - _）的音轨编号，如 "01. ", "02 - ", "12 - ", "12_", "1. "
    private val TRACK_DELIMITER_REGEX = Regex("""^\d{1,3}[ \t]*[\.\-_]+[ \t]*""")
    // 2. 以 0 开头的二或三位序号加空格，如 "01 ", "002 "
    private val TRACK_ZERO_PADDED_REGEX = Regex("""^0\d{1,2}[ \t]+""")
    // 3. Track 关键字前缀，如 "Track 01 - ", "track1 "
    private val TRACK_WORD_REGEX = Regex("""^[Tt]rack\s*\d{1,3}[\.\s\-_]*[ \t]*""")

    fun isTrackNumberPrefixed(name: String): Boolean {
        val trimmed = name.trim()
        return TRACK_DELIMITER_REGEX.containsMatchIn(trimmed) ||
            TRACK_ZERO_PADDED_REGEX.containsMatchIn(trimmed) ||
            TRACK_WORD_REGEX.containsMatchIn(trimmed)
    }

    /**
     * 清理歌曲标题中包含的前缀音轨编号（例如："02. BLUE (蓝色)" -> "BLUE (蓝色)"）
     */
    fun cleanTrackNumber(name: String): String {
        var clean = name.trim()
        clean = clean.replace(TRACK_DELIMITER_REGEX, "")
        clean = clean.replace(TRACK_ZERO_PADDED_REGEX, "")
        clean = clean.replace(TRACK_WORD_REGEX, "")
        return clean.trim().ifBlank { name }
    }

    /**
     * 判定歌曲是否需要富化或修正元数据
     */
    fun needsEnrichment(song: Song): Boolean {
        val isLocalOrWebDav = song.isLocal || !song.localFilePath.isNullOrBlank() || song.songMid.startsWith("webdav_")
        if (!isLocalOrWebDav) return false

        val hasUnknownArtist = song.singer.isBlank() || song.singer == "未知歌手" || song.singer == "WebDAV 音频" || song.singer.equals("Unknown", ignoreCase = true)
        val hasUnknownAlbum = song.album.isBlank() || song.album == "本地音频" || song.album == "WebDAV 云盘" || song.album == "本地音乐" || song.album == "WebDAV 专辑"
        val hasTrackNumberInTitle = isTrackNumberPrefixed(song.name)

        return hasUnknownArtist || hasUnknownAlbum || hasTrackNumberInTitle
    }

    /**
     * 将解析出的元数据（标签/头部）合并到当前歌曲中。
     * @return 若发生有效修正返回更新后的 Song，否则返回 null。
     */
    fun enrichSongMetadata(
        currentSong: Song,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ): Song? {
        val rawTitle = title?.trim()
        val rawArtist = artist?.trim()
        val rawAlbum = album?.trim()

        val isUnknownArtist = currentSong.singer.isBlank() || currentSong.singer == "未知歌手" || currentSong.singer == "WebDAV 音频" || currentSong.singer.equals("Unknown", ignoreCase = true)
        val hasTrackNumber = isTrackNumberPrefixed(currentSong.name)

        val candidateArtist = if (!rawArtist.isNullOrBlank() && rawArtist != "未知歌手" && rawArtist != "WebDAV 音频") rawArtist else null
        val candidateTitle = if (!rawTitle.isNullOrBlank()) cleanTrackNumber(rawTitle) else if (hasTrackNumber) cleanTrackNumber(currentSong.name) else null
        val candidateAlbum = if (!rawAlbum.isNullOrBlank()) rawAlbum else null

        val newArtist = candidateArtist ?: (if (isUnknownArtist) null else currentSong.singer)
        val newTitle = candidateTitle ?: currentSong.name
        val newAlbum = candidateAlbum ?: currentSong.album

        val isTitleChanged = newTitle != currentSong.name
        val isArtistChanged = newArtist != null && newArtist != currentSong.singer
        val isAlbumChanged = newAlbum != currentSong.album

        if (!isTitleChanged && !isArtistChanged && !isAlbumChanged) return null

        return currentSong.copy(
            name = newTitle,
            singer = newArtist ?: currentSong.singer,
            album = newAlbum,
        )
    }

    /**
     * 将 ExoPlayer 底层解码器解析得到的 MediaMetadata 合并到当前歌曲中。
     * @return 若发生有效修正返回更新后的 Song，否则返回 null。
     */
    fun enrichFromExoMetadata(
        currentSong: Song,
        mediaMetadata: MediaMetadata,
    ): Song? {
        if (!needsEnrichment(currentSong)) return null
        return enrichSongMetadata(
            currentSong = currentSong,
            title = mediaMetadata.title?.toString(),
            artist = mediaMetadata.artist?.toString(),
            album = mediaMetadata.albumTitle?.toString(),
        )
    }
}
