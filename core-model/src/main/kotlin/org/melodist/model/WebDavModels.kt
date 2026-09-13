package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
data class WebDavSongCache(
    val serverId: String = "",
    val href: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "WebDAV 专辑",
    val duration: Int = 0,
    val quality: String = "标准",
    val localCachedPath: String? = null,
    val coverPath: String? = null,
    val fileSize: Long = 0L,
    val lastModified: Long? = null,
    val embeddedLyrics: String? = null,
) {
    fun toSong(): Song {
        val hash = (serverId + href).hashCode().toString().replace("-", "n")
        return Song(
            songId = hash.take(8).toLongOrNull(16) ?: 900000L,
            songMid = "webdav_${serverId}_$hash",
            name = title.ifBlank { "未知歌曲" },
            singer = artist.ifBlank { "未知歌手" },
            album = album.ifBlank { "WebDAV 专辑" },
            durationSeconds = duration,
            currentTier = AudioQualityTier.SQ,
            coverUrl =
                if (!coverPath.isNullOrBlank()) {
                    if (coverPath.startsWith("/")) "file://$coverPath" else coverPath
                } else {
                    ""
                },
            localFilePath = localCachedPath,
            mediaMid = href,
        )
    }
}

@Serializable
data class WebDavItem(
    val name: String = "",
    val href: String = "",
    val isDirectory: Boolean = false,
    val contentLength: Long = 0L,
    val lastModified: Long? = null,
) {
    fun toSong(
        serverId: String,
        title: String,
        artist: String,
        localPath: String? = null,
        coverUrl: String? = null,
    ): Song {
        val hash = (serverId + href).hashCode().toString().replace("-", "n")
        return Song(
            songId = hash.take(8).toLongOrNull(16) ?: 900000L,
            songMid = "webdav_${serverId}_$hash",
            name = title.ifBlank { name },
            singer = artist.ifBlank { "未知歌手" },
            album = "WebDAV 云盘",
            currentTier = AudioQualityTier.SQ,
            coverUrl = coverUrl.orEmpty(),
            localFilePath = localPath,
            mediaMid = href,
        )
    }
}

@Serializable
data class WebDavServer(
    val id: String = "",
    val name: String = "我的 WebDAV",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val trustSelfSigned: Boolean = true,
    val rootPath: String = "/",
    val importedPaths: List<String> = emptyList(),
    val cachedSongs: List<WebDavSongCache> = emptyList(),
)

@Serializable
data class WebDavConfig(
    val servers: List<WebDavServer> = emptyList(),
    val activeServerId: String? = null,
)
