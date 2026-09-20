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
    val language: String = "",
    val albumType: String = "",
    val singerList: List<String> = emptyList(),
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
    val description: String = "",
) {
    val isMyFavorite: Boolean get() = dirId == 201L || name == "我喜欢" || name == "我的喜欢"
    val isCreated: Boolean get() = !isFav
    val title: String get() = name
    val songNum: Int get() = songCount

    val thumbnailPicUrl: String
        get() {
            if (picUrl.isBlank()) return ""
            return if (picUrl.contains("R1200x1200") || picUrl.contains("R800x800") || picUrl.contains("R300x300")) {
                picUrl.replace(Regex("R[0-9]+x[0-9]+"), "R500x500")
            } else {
                picUrl
            }
        }
}

@Serializable
data class QualityOption(
    val tier: AudioQualityTier,
    val format: String,
    val bitrate: String,
    val sizeBytes: Long,
    val isAvailable: Boolean,
    val playUrl: String? = null,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
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

@Serializable
data class RecommendShelf(
    val title: String,
    val rawTemplate: String = "",
    val titleContent: String = "",
    val group: Int = 0,
    val style: Int = 0,
    val moreTitle: String = "",
    val moreId: String = "",
    val songs: List<Song> = emptyList(),
)

@Serializable
data class SongComment(
    val commentId: String,
    val nick: String,
    val avatarUrl: String = "",
    val content: String,
    val timeSec: Long = 0L,
    val praiseNum: Int = 0,
    val isHot: Boolean = false,
    val picUrl: String = "",
    val picSize: String = "",
    val location: String = "",
)

@Serializable
data class CommentPage(
    val totalCount: Int,
    val hotComments: List<SongComment> = emptyList(),
    val comments: List<SongComment> = emptyList(),
    val hasMore: Boolean = false,
)
