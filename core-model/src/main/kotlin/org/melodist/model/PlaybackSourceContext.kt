package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface PlaybackSourceContext {
    @Serializable
    data class Album(
        val albumMid: String,
        val albumId: Long = 0L,
    ) : PlaybackSourceContext

    @Serializable
    data class Playlist(
        val id: String,
    ) : PlaybackSourceContext
}
