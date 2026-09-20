package org.melodist.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import org.melodist.data.AppSettingsManager

object PlaybackEngineFactory {
    private const val TAG = "PlaybackEngineFactory"

    fun createRenderersFactory(context: Context): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(context.applicationContext) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                val settings = AppSettingsManager.settings.value
                val isExclusive = settings.enableUsbExclusive
                val isPassthrough = settings.enableAudioPassthrough || isExclusive
                @Suppress("DEPRECATION")
                val audioCapabilities =
                    if (isPassthrough) {
                        AudioCapabilities.getCapabilities(context)
                    } else {
                        AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
                    }
                @Suppress("DEPRECATION")
                val builder =
                    DefaultAudioSink
                        .Builder(context)
                        .setAudioCapabilities(audioCapabilities)
                        .setAudioProcessors(emptyArray())
                        .setAudioOffloadSupportProvider(
                            androidx.media3.exoplayer.audio
                                .DefaultAudioOffloadSupportProvider(context),
                        ).setEnableFloatOutput(if (isExclusive) true else enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(if (isExclusive) false else enableAudioTrackPlaybackParams)
                return builder.build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        }
    }

    fun buildAudioOffloadPreferences(enabled: Boolean): TrackSelectionParameters.AudioOffloadPreferences {
        val offloadMode =
            if (enabled) {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
        return TrackSelectionParameters.AudioOffloadPreferences
            .Builder()
            .setAudioOffloadMode(offloadMode)
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()
    }

    fun buildExoPlayer(
        context: Context,
        listener: Player.Listener,
        analyticsListener: AnalyticsListener? = null,
    ): ExoPlayer {
        val audioAttributes =
            AudioAttributes
                .Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                .build()

        val httpDataSourceFactory =
            DefaultHttpDataSource
                .Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(30_000)
                .setReadTimeoutMs(30_000)

        val defaultDataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)
        val cachedDataSourceFactory =
            MelodistCacheManager.buildCacheDataSourceFactory(context.applicationContext, defaultDataSourceFactory)

        val mediaSourceFactory =
            DefaultMediaSourceFactory(context.applicationContext)
                .setDataSourceFactory(cachedDataSourceFactory)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))

        val isOffload = AppSettingsManager.settings.value.enableAudioOffload
        val offloadPreferences = buildAudioOffloadPreferences(isOffload)

        val profile = PlaybackProfile.detect(context)
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    profile.minBufferMs,
                    profile.maxBufferMs,
                    2_000,
                    4_000,
                ).setPrioritizeTimeOverSizeThresholds(true)
                .build()

        return ExoPlayer
            .Builder(context.applicationContext, createRenderersFactory(context))
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .apply {
                trackSelectionParameters =
                    trackSelectionParameters
                        .buildUpon()
                        .setAudioOffloadPreferences(offloadPreferences)
                        .build()
                addListener(listener)
                analyticsListener?.let { addAnalyticsListener(it) }
            }
    }
}
