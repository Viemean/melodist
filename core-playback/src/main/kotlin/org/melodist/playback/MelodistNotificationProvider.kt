package org.melodist.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import org.melodist.core.playback.R

@OptIn(UnstableApi::class)
class MelodistNotificationProvider(
    context: Context,
) : DefaultMediaNotificationProvider(
        context,
        NotificationIdProvider { PlaybackService.NOTIFICATION_ID },
        PlaybackService.CHANNEL_ID,
        androidx.media3.session.R.string.default_notification_channel_name,
    ) {
    init {
        setSmallIcon(R.drawable.ic_notification)
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val effectiveCommands = playerCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()
        val defaultButtons = super.getMediaButtons(session, effectiveCommands, customLayout, showPauseButton)

        val prevButton = defaultButtons.firstOrNull {
            it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS || it.playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        } ?: CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setDisplayName("上一首")
            .setEnabled(true)
            .build()

        val playPauseButton = defaultButtons.firstOrNull {
            it.playerCommand == Player.COMMAND_PLAY_PAUSE
        } ?: CommandButton.Builder(if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName(if (showPauseButton) "暂停" else "播放")
            .setEnabled(true)
            .build()

        val nextButton = defaultButtons.firstOrNull {
            it.playerCommand == Player.COMMAND_SEEK_TO_NEXT || it.playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        } ?: CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName("下一首")
            .setEnabled(true)
            .build()

        val favoriteButton = defaultButtons.firstOrNull {
            it.sessionCommand?.customAction == PlaybackService.ACTION_TOGGLE_FAVORITE
        }

        val result = ImmutableList.builder<CommandButton>()
        result.add(prevButton)
        result.add(playPauseButton)
        result.add(nextButton)
        if (favoriteButton != null) {
            result.add(favoriteButton)
        }

        for (button in defaultButtons) {
            if (button !== prevButton && button !== playPauseButton && button !== nextButton && button !== favoriteButton) {
                result.add(button)
            }
        }
        return result.build()
    }
}
