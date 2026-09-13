package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
data class Album(
    val id: Long,
    val mid: String,
    val title: String,
    val artist: String,
    val songCount: Int,
    val coverUrl: String = "",
    val pubTime: Long = 0L,
) {
    val name: String get() = title
}

@Serializable
data class AlbumDetail(
    val mid: String,
    val name: String,
    val artist: String,
    val publishDate: String,
    val company: String,
    val description: String,
    val songs: List<Song>,
)

@Serializable
data class Artist(
    val id: Long,
    val mid: String,
    val name: String,
    val avatarUrl: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

@Serializable
data class ArtistDetail(
    val mid: String,
    val id: Long,
    val name: String,
    val brief: String,
    val songs: List<Song>,
)

@Serializable
data class Playlist(
    val dirId: Long,
    val name: String,
    val songCount: Int,
    val tid: Long = 0L,
    val isFav: Boolean = false,
    val picUrl: String = "",
) {
    val isMyFavorite: Boolean get() = dirId == 201L || name == "我喜欢" || name == "我的喜欢"
    val isCreated: Boolean get() = !isFav
    val title: String get() = name
    val songNum: Int get() = songCount
}

@Serializable
data class QualityOption(
    val tier: AudioQualityTier,
    val format: String,
    val bitrate: String,
    val sizeBytes: Long,
    val isAvailable: Boolean,
    val playUrl: String? = null,
)

@Serializable
data class SearchPageResult(
    val songs: List<Song>,
    val total: Int,
    val page: Int,
)

@Serializable
data class FavoriteSongsResult(
    val songs: List<Song>,
    val total: Int,
    val hasMore: Boolean,
)
