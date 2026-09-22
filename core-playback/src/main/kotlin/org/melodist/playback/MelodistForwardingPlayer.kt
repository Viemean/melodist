package org.melodist.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import java.util.concurrent.CopyOnWriteArraySet

class MelodistForwardingPlayer(
    player: Player,
) : ForwardingPlayer(player) {
    private val extraListeners = CopyOnWriteArraySet<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        extraListeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        extraListeners.remove(listener)
    }

    private val isRemoteVirtual: Boolean
        get() =
            PlaybackManager.isRemoteActive.value &&
                (PlaybackManager.isSilentKeepAlive.value || !super.getPlayWhenReady() || super.getPlaybackState() == Player.STATE_IDLE)

    fun notifyRemoteStateChanged() {
        val song = PlaybackManager.currentSong.value
        val isPlaying = PlaybackManager.isPlaying.value
        val mediaItem = currentMediaItem
        val metadata = mediaMetadata
        val state = playbackState
        val playWhenReady = playWhenReady
        val timeline = currentTimeline
        for (listener in extraListeners) {
            try {
                listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                listener.onMediaItemTransition(mediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                listener.onMediaMetadataChanged(metadata)
                listener.onPlaybackStateChanged(state)
                listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                listener.onIsPlayingChanged(isPlaying)
            } catch (_: Exception) {
            }
        }
    }

    override fun getCurrentTimeline(): Timeline =
        if (isRemoteVirtual) {
            val item = currentMediaItem
            if (item != null) {
                val durationUs = (PlaybackManager.durationMs.value.coerceAtLeast(0L)) * 1000L
                SinglePeriodTimeline(durationUs, true, false, false, null, item)
            } else {
                Timeline.EMPTY
            }
        } else {
            super.getCurrentTimeline()
        }

    override fun getCurrentPeriodIndex(): Int = if (isRemoteVirtual) 0 else super.getCurrentPeriodIndex()

    override fun getContentPosition(): Long = if (isRemoteVirtual) PlaybackManager.currentPositionMs.value else super.getContentPosition()

    override fun getContentDuration(): Long = if (isRemoteVirtual) PlaybackManager.durationMs.value.coerceAtLeast(0L) else super.getContentDuration()

    override fun getPlaybackState(): Int =
        if (isRemoteVirtual) {
            if (PlaybackManager.currentSong.value != null) Player.STATE_READY else Player.STATE_IDLE
        } else {
            super.getPlaybackState()
        }

    override fun getPlayWhenReady(): Boolean =
        if (isRemoteVirtual) {
            PlaybackManager.isPlaying.value
        } else {
            super.getPlayWhenReady()
        }

    override fun isPlaying(): Boolean =
        if (isRemoteVirtual) {
            PlaybackManager.isPlaying.value
        } else {
            super.isPlaying()
        }

    override fun getCurrentMediaItem(): MediaItem? =
        if (isRemoteVirtual) {
            PlaybackManager.currentSong.value?.let {
                PlaybackManager.buildMediaItemForSong(it, PlaybackManager.remoteDeviceName.value)
            }
        } else {
            super.getCurrentMediaItem()
        }

    override fun getMediaMetadata(): MediaMetadata =
        if (isRemoteVirtual) {
            PlaybackManager.currentSong.value?.let {
                PlaybackManager.buildMediaMetadata(it, PlaybackManager.remoteDeviceName.value)
            } ?: MediaMetadata.EMPTY
        } else {
            super.getMediaMetadata()
        }

    override fun getCurrentPosition(): Long =
        if (isRemoteVirtual) {
            PlaybackManager.currentPositionMs.value
        } else {
            super.getCurrentPosition()
        }

    override fun getDuration(): Long =
        if (isRemoteVirtual) {
            PlaybackManager.durationMs.value.coerceAtLeast(0L)
        } else {
            super.getDuration()
        }

    override fun getCurrentMediaItemIndex(): Int = if (isRemoteVirtual) 0 else super.getCurrentMediaItemIndex()

    override fun getMediaItemCount(): Int =
        if (isRemoteVirtual) {
            if (PlaybackManager.currentSong.value != null) 1 else 0
        } else {
            super.getMediaItemCount()
        }

    override fun getAvailableCommands(): Player.Commands =
        super
            .getAvailableCommands()
            .buildUpon()
            .add(COMMAND_PLAY_PAUSE)
            .add(COMMAND_PREPARE)
            .add(COMMAND_STOP)
            .add(COMMAND_SEEK_TO_NEXT)
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(command: Int): Boolean =
        if (isRemoteVirtual) {
            when (command) {
                COMMAND_PLAY_PAUSE,
                COMMAND_PREPARE,
                COMMAND_STOP,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                -> true
                else -> super.isCommandAvailable(command)
            }
        } else {
            when (command) {
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> PlaybackManager.playlist.value.isNotEmpty()
                else -> super.isCommandAvailable(command)
            }
        }

    override fun play() {
        if (isRemoteVirtual || (super.getCurrentMediaItem() == null && PlaybackManager.currentSong.value != null)) {
            PlaybackManager.togglePlayPause()
        } else {
            super.play()
        }
    }

    override fun pause() {
        if (isRemoteVirtual) {
            PlaybackManager.pause()
        } else {
            super.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (isRemoteVirtual) {
            PlaybackManager.seekTo(positionMs)
        } else {
            super.seekTo(positionMs)
        }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (isRemoteVirtual) {
            PlaybackManager.seekTo(positionMs)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekToNext() {
        PlaybackManager.playNext()
    }

    override fun seekToNextMediaItem() {
        PlaybackManager.playNext()
    }

    override fun seekToPrevious() {
        PlaybackManager.playPrevious()
    }

    override fun seekToPreviousMediaItem() {
        PlaybackManager.playPrevious()
    }
}
