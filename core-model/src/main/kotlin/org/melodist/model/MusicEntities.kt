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
    val id: Long = 0L,
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
    val totalSongs: Int = 0,
    val totalAlbums: Int = 0,
    val totalMvs: Int = 0,
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
    val dirShow: Int = 1,
    val creator: String = "",
    val tags: List<String> = emptyList(),
    val playCount: Long = 0L,
    val favCount: Long = 0L,
) {
    val isPrivate: Boolean get() = dirShow == 2
    val isMyFavorite: Boolean get() = dirId == 201L || name == "我喜欢" || name == "我的喜欢"
    val isMillionRecommend: Boolean get() = dirId == 211111L || name == "百万收藏" || name.contains("百万收藏")
    val isCreated: Boolean get() = !isFav
    val title: String get() = name
    val songNum: Int get() = songCount

    val thumbnailPicUrl: String
        get() = CoverUrlResolver.getThumbnailUrl(picUrl)

    val thumbnailCandidates: List<String>
        get() = CoverUrlResolver.getCandidates(picUrl, CoverScenario.THUMBNAIL)

    val detailCoverCandidates: List<String>
        get() = CoverUrlResolver.getCandidates(picUrl, CoverScenario.DETAIL)
}

@Serializable
data class PlaylistDetailResult(
    val playlist: Playlist,
    val songs: List<Song> = emptyList(),
    val totalSongNum: Int = 0,
)

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
    val channels: Int = 2,
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
    val seqNo: String = "",
    val isPraised: Boolean = false,
    val isSelf: Boolean = false,
)

@Serializable
data class CommentPage(
    val totalCount: Int,
    val hotComments: List<SongComment> = emptyList(),
    val comments: List<SongComment> = emptyList(),
    val hasMore: Boolean = false,
    val lastSeqNo: String = "",
)

/**
 * 评论区业务目标实体抽象。
 */
sealed interface CommentTarget {
    val bizType: Int
    val bizId: Long
    val id: String get() = bizId.toString()
    val title: String
    val subtitle: String

    data class Song(
        val song: org.melodist.model.Song,
    ) : CommentTarget {
        override val bizType: Int get() = 1
        override val bizId: Long get() = song.songId
        override val title: String get() = song.name
        override val subtitle: String get() = song.singer
    }

    data class Album(
        val albumId: Long,
        val albumName: String,
        val artistName: String = "",
    ) : CommentTarget {
        override val bizType: Int get() = 2
        override val bizId: Long get() = albumId
        override val title: String get() = albumName
        override val subtitle: String get() = artistName
    }

    data class Playlist(
        val disstid: Long,
        val playlistName: String,
        val creatorNick: String = "",
    ) : CommentTarget {
        constructor(dissId: String, playlistName: String, creatorName: String = "") : this(
            disstid = dissId.toLongOrNull() ?: 0L,
            playlistName = playlistName,
            creatorNick = creatorName,
        )

        override val bizType: Int get() = 3
        override val bizId: Long get() = disstid
        override val title: String get() = playlistName
        override val subtitle: String get() = creatorNick
    }
}
