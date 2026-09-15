package org.melodist.mobile.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import org.melodist.model.Playlist

sealed interface ScreenDestination {
    data class PlaylistDetail(
        val playlist: Playlist,
    ) : ScreenDestination

    data class ArtistDetail(
        val artistMid: String,
        val artistName: String = "",
    ) : ScreenDestination

    data class AlbumDetail(
        val albumMid: String,
        val albumName: String = "",
    ) : ScreenDestination

    data object FavoriteAlbums : ScreenDestination
}

interface AppNavigationController {
    fun navigateToPlaylist(
        playlist: Playlist,
        clearStack: Boolean = false,
    )

    fun navigateToArtist(
        artistMid: String,
        artistName: String = "",
        clearStack: Boolean = false,
    )

    fun navigateToAlbum(
        albumMid: String,
        albumName: String = "",
        clearStack: Boolean = false,
    )

    fun navigateToFavoriteAlbums(clearStack: Boolean = false)

    fun navigateBack(): Boolean

    val currentDestination: ScreenDestination?
}

val LocalAppNavigation =
    compositionLocalOf<AppNavigationController> {
        object : AppNavigationController {
            override fun navigateToPlaylist(
                playlist: Playlist,
                clearStack: Boolean,
            ) {}

            override fun navigateToArtist(
                artistMid: String,
                artistName: String,
                clearStack: Boolean,
            ) {}

            override fun navigateToAlbum(
                albumMid: String,
                albumName: String,
                clearStack: Boolean,
            ) {}

            override fun navigateToFavoriteAlbums(clearStack: Boolean) {}

            override fun navigateBack(): Boolean = false

            override val currentDestination: ScreenDestination? = null
        }
    }
