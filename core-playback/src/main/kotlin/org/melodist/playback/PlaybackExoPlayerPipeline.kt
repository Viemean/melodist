package org.melodist.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import org.melodist.data.AppSettingsManager

/**
 * 负责 ExoPlayer 实例的生命周期、AudioOffload 配置、USB DAC 独占音频路由与管道重置
 */
class PlaybackExoPlayerPipeline(
    private val getContext: () -> Context?,
    private val usbRouter: UsbAudioRouter,
    private val playerListener: androidx.media3.common.Player.Listener,
    private val onPlayerCreated: (ExoPlayer) -> Unit,
) {
    @Volatile
    private var exoPlayer: ExoPlayer? = null

    val player: ExoPlayer?
        get() = exoPlayer

    private val analyticsListener =
        object : AnalyticsListener {
            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                Log.i(
                    "MelodistPlayback",
                    "AudioTrack initialized: rate=${audioTrackConfig.sampleRate}, enc=${audioTrackConfig.encoding}, ch=${audioTrackConfig.channelConfig}, offload=${audioTrackConfig.offload}",
                )
            }
        }

    fun buildExoPlayer(context: Context): ExoPlayer {
        return PlaybackEngineFactory.buildExoPlayer(
            context = context,
            listener = playerListener,
            analyticsListener = analyticsListener,
        ).also {
            updateUsbExclusiveRouting()
        }
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        return exoPlayer ?: synchronized(this) {
            exoPlayer ?: buildExoPlayer(context).also {
                exoPlayer = it
                onPlayerCreated(it)
            }
        }
    }

    fun applyAudioOffloadPreferences(enabled: Boolean) {
        val p = exoPlayer ?: return
        val offloadMode =
            if (enabled) {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
        val offloadPreferences =
            TrackSelectionParameters.AudioOffloadPreferences
                .Builder()
                .setAudioOffloadMode(offloadMode)
                .setIsGaplessSupportRequired(false)
                .setIsSpeedChangeSupportRequired(false)
                .build()
        p.trackSelectionParameters =
            p.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
        Log.i("MelodistPlayback", "Applied audio offload preferences: enabled=$enabled")
    }

    fun resetPlayerPipeline(restoreMediaItem: Boolean = true) {
        val context = getContext() ?: return
        val p = exoPlayer
        val pos = p?.currentPosition ?: 0L
        val wasPlaying = p?.isPlaying == true
        val item = p?.currentMediaItem

        p?.release()
        exoPlayer =
            buildExoPlayer(context).apply {
                if (restoreMediaItem && item != null) {
                    setMediaItem(item, pos)
                    prepare()
                    if (wasPlaying) {
                        play()
                    }
                }
            }
        onPlayerCreated(exoPlayer!!)
        updateUsbExclusiveRouting()
        Log.i("MelodistPlayback", "Player pipeline reset with updated audio configuration (restoreMediaItem=$restoreMediaItem)")
    }

    fun reloadAudioPipeline() {
        val p = exoPlayer ?: return
        val pos = p.currentPosition
        val wasPlaying = p.isPlaying
        val item = p.currentMediaItem ?: return
        p.setMediaItem(item, pos)
        p.prepare()
        if (wasPlaying) {
            p.play()
        }
        Log.i("MelodistPlayback", "Audio pipeline reloaded at position: $pos ms (playing: $wasPlaying)")
    }

    fun updateUsbExclusiveRouting(
        targetRate: Int = 0,
        channelCount: Int = 0,
        pcmEncoding: Int = 0,
    ) {
        usbRouter.updateUsbExclusiveRouting(
            context = getContext(),
            player = exoPlayer,
            isUsbExclusive = AppSettingsManager.settings.value.enableUsbExclusive,
            targetRate = targetRate,
            channelCount = channelCount,
            pcmEncoding = pcmEncoding,
        )
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
