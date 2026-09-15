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
        val defaultButtons = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
        val favoriteButton =
            defaultButtons.firstOrNull {
                it.sessionCommand?.customAction == PlaybackService.ACTION_TOGGLE_FAVORITE
            } ?: return defaultButtons

        val result = ImmutableList.builder<CommandButton>()
        result.add(favoriteButton)
        for (button in defaultButtons) {
            if (button !== favoriteButton) {
                result.add(button)
            }
        }
        return result.build()
    }
}
