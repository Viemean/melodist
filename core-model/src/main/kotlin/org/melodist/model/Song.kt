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
    val durationSeconds: Int = 0,
    val currentTier: AudioQualityTier = AudioQualityTier.Standard,
    val availableTiers: List<AudioQualityTier> = emptyList(),
    val isVip: Boolean = false,
    val coverUrl: String = "",
    val localFilePath: String? = null,
    val mediaMid: String = "",
    val singerList: List<Artist> = emptyList(),
    val visualMid: String = "",
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
        get() {
            if (coverUrl.isBlank()) return ""
            return if (coverUrl.contains("R1200x1200") || coverUrl.contains("R800x800") || coverUrl.contains("R300x300")) {
                coverUrl.replace(Regex("R[0-9]+x[0-9]+"), "R500x500")
            } else {
                coverUrl
            }
        }

    val playerCoverCandidates: List<String>
        get() {
            if (coverUrl.isBlank()) return emptyList()
            if (coverUrl.startsWith("/") || coverUrl.startsWith("file://")) return listOf(coverUrl)
            val regex = Regex("R[0-9]+x[0-9]+")
            if (coverUrl.contains(regex)) {
                val url1200 = coverUrl.replace(regex, "R1200x1200")
                val url800 = coverUrl.replace(regex, "R800x800")
                val url500 = coverUrl.replace(regex, "R500x500")
                return listOf(url1200, url800, url500).distinct()
            }
            return listOf(coverUrl)
        }
}
