package org.melodist.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import java.io.File

/**
 * 负责构建 Android Media3 所需的 MediaItem 与 MediaMetadata
 */
object PlaybackMediaItemFactory {

    fun buildMediaMetadata(song: Song, remoteDeviceName: String? = null): MediaMetadata {
        val albumDesc = if (!remoteDeviceName.isNullOrBlank()) {
            val base = if (song.album.isNotBlank()) song.album else "单曲"
            "$base · 正在 $remoteDeviceName 播放"
        } else {
            song.album
        }

        val builder =
            MediaMetadata
                .Builder()
                .setTitle(song.name)
                .setArtist(song.singer)
                .setDisplayTitle(song.name)
                .setAlbumTitle(albumDesc)

        val coverUrl = song.coverUrl
        if (coverUrl.isNotBlank()) {
            builder.setArtworkUri(Uri.parse(coverUrl))
            if (coverUrl.startsWith("file://")) {
                try {
                    val file = File(coverUrl.removePrefix("file://").substringBefore('?'))
                    if (file.exists() && file.length() in 1..(2 * 1024 * 1024)) {
                        builder.setArtworkData(file.readBytes(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return builder.build()
    }

    fun buildMediaItem(
        uri: Uri?,
        song: Song,
        tier: AudioQualityTier? = null,
    ): MediaItem {
        val builder =
            MediaItem
                .Builder()
                .setMediaId(song.songMid)
                .setCustomCacheKey(MelodistCacheManager.getCacheKey(song.songMid, tier))
                .setMediaMetadata(buildMediaMetadata(song))
        if (uri != null) {
            builder.setUri(uri)
        }
        return builder.build()
    }

    fun buildMediaItemForSong(song: Song, remoteDeviceName: String? = null): MediaItem {
        val uri = if (song.coverUrl.isNotBlank()) {
            try {
                Uri.parse(song.coverUrl)
            } catch (_: Exception) {
                Uri.EMPTY
            }
        } else {
            Uri.EMPTY
        }
        return MediaItem
            .Builder()
            .setUri(uri)
            .setMediaId(song.songMid)
            .setMediaMetadata(buildMediaMetadata(song, remoteDeviceName))
            .build()
    }
}
