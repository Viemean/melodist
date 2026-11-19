package org.melodist.playback.usb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log

class SilentAudioTrackHeartbeat(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SilentHeartbeat"
        private const val SAMPLE_RATE = 44100
        private const val BUFFER_SIZE = 4096
    }

    private var audioTrack: AudioTrack? = null

    fun start() {
        if (audioTrack != null) return
        try {
            val silence = ByteArray(BUFFER_SIZE)

            val track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    ).setBufferSizeInBytes(BUFFER_SIZE)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val speaker =
                    am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                if (speaker != null) {
                    track.setPreferredDevice(speaker)
                }
            }

            track.write(silence, 0, silence.size)
            track.setLoopPoints(0, BUFFER_SIZE / 4, -1)
            track.setVolume(0.001f)
            track.play()
            audioTrack = track
            Log.i(TAG, "Static silent heartbeat AudioTrack active in background")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start static silent AudioTrack", e)
        }
    }

    fun pause() {
        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }
    }

    fun resume() {
        try {
            audioTrack?.play()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }
}
