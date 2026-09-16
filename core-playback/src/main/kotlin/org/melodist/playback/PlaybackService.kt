package org.melodist.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var forwardingPlayer: MelodistForwardingPlayer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    companion object {
        const val ACTION_TOGGLE_FAVORITE = "org.melodist.playback.ACTION_TOGGLE_FAVORITE"
        const val CHANNEL_ID = "melodist_playback_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val sessionCallback =
        object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val sessionCommands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                        .build()
                val isFav = isCurrentSongFavorite()
                return MediaSession.ConnectionResult
                    .AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .setCustomLayout(listOf(createFavoriteButton(isFav)))
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == ACTION_TOGGLE_FAVORITE) {
                    PlaybackManager.toggleCurrentSongFavorite()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

    override fun onCreate() {
        super.onCreate()
        setupNotificationChannel()

        val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Melodist:PlaybackWakeLock")

        val notificationProvider = MelodistNotificationProvider(this)
        setMediaNotificationProvider(notificationProvider)

        val player = PlaybackManager.getOrCreatePlayer(this)
        val forwarding = MelodistForwardingPlayer(player)
        forwardingPlayer = forwarding

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    setPackage(packageName)
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val sessionActivity =
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val isFav = isCurrentSongFavorite()
        val session =
            MediaSession
                .Builder(this, forwarding)
                .setId("MelodistPlaybackSession")
                .setSessionActivity(sessionActivity)
                .setCallback(sessionCallback)
                .setCustomLayout(listOf(createFavoriteButton(isFav)))
                .build()
        mediaSession = session
        addSession(session)

        serviceScope.launch {
            combine(PlaybackManager.currentSong, PlaybackManager.favoriteSongMids) { song, favs ->
                song != null && favs.contains(song.songMid)
            }.distinctUntilChanged().collect { isFavState ->
                mediaSession?.setCustomLayout(listOf(createFavoriteButton(isFavState)))
            }
        }

        serviceScope.launch {
            combine(
                PlaybackManager.currentSong,
                PlaybackManager.isPlaying,
                PlaybackManager.isRemoteActive,
                PlaybackManager.remoteDeviceName,
            ) { song, isPlaying, isRemote, deviceName ->
                Triple(song?.songMid to isPlaying, isRemote, deviceName)
            }.distinctUntilChanged().collect {
                forwardingPlayer?.notifyRemoteStateChanged()
                mediaSession?.let { sessionToUpdate ->
                    onUpdateNotification(sessionToUpdate, PlaybackManager.shouldHoldForeground())
                }
            }
        }

        serviceScope.launch {
            combine(PlaybackManager.isPlaying, PlaybackManager.isLoading) { playing, loading ->
                playing || loading
            }.distinctUntilChanged().collect { shouldHoldWake ->
                updateWakeLock(shouldHoldWake)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = super.onStartCommand(intent, flags, startId)

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        val shouldHold = PlaybackManager.shouldHoldForeground()
        super.onUpdateNotification(session, startInForegroundRequired || shouldHold)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        updateWakeLock(false)
        serviceScope.cancel()
        forwardingPlayer = null
        mediaSession?.run {
            removeSession(this)
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private fun updateWakeLock(acquire: Boolean) {
        try {
            if (acquire) {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaybackService", "Error toggling wake lock", e)
        }
    }

    private fun isCurrentSongFavorite(): Boolean {
        val song = PlaybackManager.currentSong.value ?: return false
        return PlaybackManager.favoriteSongMids.value.contains(song.songMid)
    }

    private fun createFavoriteButton(isFavorite: Boolean): CommandButton {
        val icon = if (isFavorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
        val displayName = if (isFavorite) "取消收藏" else "收藏"
        return CommandButton
            .Builder(icon)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setDisplayName(displayName)
            .setEnabled(true)
            .build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "媒体播放控制",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "显示正在播放的音乐信息与控制控件"
                    setShowBadge(false)
                }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
