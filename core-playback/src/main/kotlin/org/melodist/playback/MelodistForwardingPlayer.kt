package org.melodist.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

class MelodistForwardingPlayer(
    player: Player,
) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super
            .getAvailableCommands()
            .buildUpon()
            .add(COMMAND_SEEK_TO_NEXT)
            .add(COMMAND_SEEK_TO_PREVIOUS)
            .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

    override fun isCommandAvailable(command: Int): Boolean =
        when (command) {
            COMMAND_SEEK_TO_NEXT,
            COMMAND_SEEK_TO_PREVIOUS,
            COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> PlaybackManager.playlist.value.isNotEmpty()
            else -> super.isCommandAvailable(command)
        }

    override fun play() {
        if (currentMediaItem == null && PlaybackManager.currentSong.value != null) {
            PlaybackManager.togglePlayPause()
        } else {
            super.play()
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
