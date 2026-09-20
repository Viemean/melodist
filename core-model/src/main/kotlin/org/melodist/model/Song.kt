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
        get() {
            if (coverUrl.isBlank()) return ""
            val regex = Regex("R[0-9]+x[0-9]+")
            return if (coverUrl.contains(regex)) {
                coverUrl.replace(regex, "R800x800")
            } else {
                coverUrl
            }
        }

    val thumbnailCandidates: List<String>
        get() {
            if (coverUrl.isBlank()) return emptyList()
            if (coverUrl.startsWith("/") || coverUrl.startsWith("file://")) return listOf(coverUrl)
            val regex = Regex("R[0-9]+x[0-9]+")
            if (coverUrl.contains(regex)) {
                val url800 = coverUrl.replace(regex, "R800x800")
                val url500 = coverUrl.replace(regex, "R500x500")
                return listOf(url800, url500)
            }
            return listOf(coverUrl)
        }

    val playerCoverCandidates: List<String>
        get() {
            val list = mutableListOf<String>()
            if (rawCoverUrl.isNotBlank()) {
                list.add(rawCoverUrl)
            }
            if (coverUrl.isNotBlank()) {
                if (coverUrl.startsWith("/") || coverUrl.startsWith("file://")) {
                    val matchingRaw = findLocalRawCover(coverUrl)
                    if (!matchingRaw.isNullOrBlank() && !list.contains(matchingRaw)) {
                        list.add(matchingRaw)
                    }
                    if (!list.contains(coverUrl)) {
                        list.add(coverUrl)
                    }
                } else {
                    val regex = Regex("R[0-9]+x[0-9]+")
                    if (coverUrl.contains(regex)) {
                        val rawUrl = coverUrl.replace(regex, "")
                        val url1200 = coverUrl.replace(regex, "R1200x1200")
                        val url800 = coverUrl.replace(regex, "R800x800")
                        val url500 = coverUrl.replace(regex, "R500x500")
                        listOf(rawUrl, url1200, url800, url500).forEach { u ->
                            if (!list.contains(u)) list.add(u)
                        }
                    } else {
                        if (!list.contains(coverUrl)) {
                            list.add(coverUrl)
                        }
                    }
                }
            }
            return list
        }

    val rawCoverUrlOnly: String?
        get() {
            if (rawCoverUrl.isNotBlank()) return rawCoverUrl
            if (coverUrl.isNotBlank()) {
                if (coverUrl.startsWith("/") || coverUrl.startsWith("file://")) {
                    return findLocalRawCover(coverUrl)
                }
                val regex = Regex("R[0-9]+x[0-9]+")
                if (coverUrl.contains(regex)) {
                    return coverUrl.replace(regex, "")
                }
            }
            return null
        }

    fun resolvePlayerCoverCandidates(
        isCellular: Boolean = false,
        hasRawCache: Boolean = false,
    ): List<String> {
        val candidates = playerCoverCandidates
        if (isCellular && !hasRawCache) {
            val raw = rawCoverUrlOnly
            if (!raw.isNullOrBlank()) {
                return candidates.filter { it != raw }
            }
        }
        return candidates
    }

    val rawCoverCandidates: List<String>
        get() {
            val list = mutableListOf<String>()
            if (rawCoverUrl.isNotBlank()) {
                list.add(rawCoverUrl)
            }
            if (coverUrl.isNotBlank()) {
                if (coverUrl.startsWith("/") || coverUrl.startsWith("file://")) {
                    val matchingRaw = findLocalRawCover(coverUrl)
                    if (!matchingRaw.isNullOrBlank() && !list.contains(matchingRaw)) {
                        list.add(matchingRaw)
                    }
                    if (!list.contains(coverUrl)) {
                        list.add(coverUrl)
                    }
                } else {
                    val regex = Regex("R[0-9]+x[0-9]+")
                    if (coverUrl.contains(regex)) {
                        val rawUrl = coverUrl.replace(regex, "")
                        val url1200 = coverUrl.replace(regex, "R1200x1200")
                        val url800 = coverUrl.replace(regex, "R800x800")
                        listOf(rawUrl, url1200, url800, coverUrl).forEach { u ->
                            if (!list.contains(u)) list.add(u)
                        }
                    } else {
                        if (!list.contains(coverUrl)) {
                            list.add(coverUrl)
                        }
                    }
                }
            }
            return list
        }
}

private fun findLocalRawCover(url: String): String? {
    if (!url.startsWith("/") && !url.startsWith("file://")) return null
    return try {
        val path = if (url.startsWith("file://")) url.removePrefix("file://") else url
        val file = java.io.File(path)
        val parent = file.parentFile ?: return null
        val name = file.name
        val (baseHash, prefix) =
            when {
                name.startsWith("cover_") && !name.startsWith("cover_raw_") -> {
                    name.removePrefix("cover_").substringBeforeLast(".") to "cover_raw_"
                }
                name.startsWith("webdav_") && !name.startsWith("webdav_raw_") -> {
                    name.removePrefix("webdav_").substringBeforeLast(".") to "webdav_raw_"
                }
                else -> return null
            }
        val extensions = listOf("jpg", "png", "webp", "jpeg")
        for (ext in extensions) {
            val candidate = java.io.File(parent, "$prefix$baseHash.$ext")
            if (candidate.exists() && candidate.length() > 512L) {
                return "file://${candidate.absolutePath}"
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}


