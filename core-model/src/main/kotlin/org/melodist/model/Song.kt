package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val songId: Long = 0,
    val songMid: String = "",
    val name: String = "",
    val singer: String = "",
    val album: String = "",
    val albumMid: String = "",
    val albumId: Long = 0L,
    val durationSeconds: Int = 0,
    val currentTier: AudioQualityTier = AudioQualityTier.Standard,
    val availableTiers: List<AudioQualityTier> = emptyList(),
    val isVip: Boolean = false,
    val coverUrl: String = "",
    val localFilePath: String? = null,
    val mediaMid: String = "",
    val singerList: List<Artist> = emptyList(),
    val visualMid: String = "",
    val rawCoverUrl: String = "",
) {
    val effectiveMediaMid: String
        get() = mediaMid.ifBlank { songMid }

    val durationFormatted: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }

    val isWebDav: Boolean
        get() = songMid.startsWith("webdav_")

    val isLocal: Boolean
        get() = songMid.startsWith("local_")

    val canShowArtistAlbumDialog: Boolean
        get() = !songMid.startsWith("webdav_") && !songMid.startsWith("local_")

    val thumbnailCoverUrl: String
        get() = CoverUrlResolver.getThumbnailUrl(coverUrl)

    val thumbnailCandidates: List<String>
        get() = CoverUrlResolver.getCandidates(coverUrl, CoverScenario.THUMBNAIL)

    val playerCoverCandidates: List<String>
        get() = CoverUrlResolver.getCandidates(coverUrl, CoverScenario.PLAYER, isCellular = false, explicitRawUrl = rawCoverUrl.ifBlank { null })

    val rawCoverUrlOnly: String?
        get() = CoverUrlResolver.getRawUrlOnly(coverUrl, explicitRawUrl = rawCoverUrl.ifBlank { null })

    fun resolvePlayerCoverCandidates(
        isCellular: Boolean = false,
        hasRawCache: Boolean = false,
    ): List<String> = CoverUrlResolver.getCandidates(coverUrl, CoverScenario.PLAYER, isCellular = isCellular, explicitRawUrl = rawCoverUrl.ifBlank { null })

    val rawCoverCandidates: List<String>
        get() = CoverUrlResolver.getCandidates(coverUrl, CoverScenario.FULLSCREEN_RAW, explicitRawUrl = rawCoverUrl.ifBlank { null })
}
