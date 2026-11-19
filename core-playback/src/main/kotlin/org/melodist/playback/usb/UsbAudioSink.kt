package org.melodist.playback.usb

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import org.melodist.playback.AntiClippingSurroundDownmixer
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val delegate: DefaultAudioSink,
    private val context: Context,
    private val config: UsbAudioSinkConfig = UsbAudioSinkConfig(),
) : ForwardingAudioSink(delegate) {
    private var usbAudioStream: UsbAudioStream? = null
    private var usbStreamingThread: UsbStreamingThread? = null
    private val silentHeartbeat = SilentAudioTrackHeartbeat(context)

    @Volatile
    private var isPlaying: Boolean = false

    @Volatile
    private var isEndOfStreamHandled: Boolean = false

    private var basePresentationTimeUs: Long = C.TIME_UNSET
    private var startFramesWritten: Long = 0L

    private var currentSampleRate: Int = 0
    private var currentChannels: Int = 0
    private var inputChannels: Int = 2
    private var currentEncoding: Int = 0
    private var currentSourceBitDepth: Int = 16
    private var handleBufferCallCount = 0L
    private var isMutedOnDetached = false

    private val usbAudioDevice: UsbAudioDevice by lazy {
        UsbAudioDevice.getInstance(context)
    }

    init {
        usbAudioDevice.onDeviceDetachedListener = {
            tearDownUsbPipeline()
        }
        org.melodist.data.AppSettingsManager.onUsbVolumeControlChangedListener = {
            updateUsbVolume()
        }
        try {
            val volumeObserver =
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        super.onChange(selfChange)
                        updateUsbVolume()
                    }
                }
            context.contentResolver.registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                volumeObserver,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register volume ContentObserver", e)
        }
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        isMutedOnDetached = false
        isEndOfStreamHandled = false
        basePresentationTimeUs = C.TIME_UNSET
        startFramesWritten = 0L
        val inputFormat = audioSinkConfig.format
        val sampleRate = inputFormat.sampleRate
        val channelCount = inputFormat.channelCount
        val pcmEncoding = inputFormat.pcmEncoding

        if (config.bitPerfectEnabled) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                configureUsbBitPerfect(sampleRate, channelCount, pcmEncoding)
            } else {
                tearDownUsbPipeline()
            }
        } else {
            tearDownUsbPipeline()
        }

        super.configure(audioSinkConfig)
        if (config.forceRouteToSpeaker && usbAudioStream != null) {
            routeDelegateToSpeaker()
        } else if (usbAudioStream == null) {
            clearDelegateDevice()
        }
    }

    private fun configureUsbBitPerfect(
        sampleRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        if (sampleRate <= 0 || channelCount <= 0) return

        if (usbAudioStream != null &&
            currentSampleRate == sampleRate &&
            currentChannels == 2 &&
            inputChannels == channelCount &&
            currentEncoding == pcmEncoding
        ) {
            return
        }

        tearDownUsbPipeline()

        val device = usbAudioDevice.findUsbAudioDevice() ?: return
        val info = usbAudioDevice.openDevice(device) ?: return

        val sourceBitDepth =
            when (pcmEncoding) {
                C.ENCODING_PCM_16BIT -> 16
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                C.ENCODING_PCM_FLOAT -> 32
                else -> 16
            }

        // Match the optimal alt setting and hardware bit depth on the DAC
        val (targetAlt, dacBitDepth) = usbAudioDevice.findAltSettingForBitDepth(sourceBitDepth)

        Log.i(
            TAG,
            "Opening direct USB audio: ${info.deviceName}, rate=$sampleRate, inputCh=$channelCount, hwCh=2, " +
                "sourceBitDepth=$sourceBitDepth, dacBitDepth=$dacBitDepth, targetAlt=$targetAlt",
        )

        val stream =
            UsbAudioStream(
                fd = info.fd,
                interfaceId = info.interfaceId,
                endpointOut = info.endpointOutAddress,
                endpointFeedback = info.endpointFeedbackAddress,
                sampleRate = sampleRate,
                channelCount = 2,
                bitDepth = dacBitDepth,
                maxPacketSize = info.maxPacketSize,
            )

        if (!stream.isReady) {
            Log.e(TAG, "Failed to create native UsbAudioStream")
            stream.release()
            return
        }

        usbAudioDevice.setSampleRate(sampleRate)
        if (targetAlt > 0) {
            stream.setAltSetting(targetAlt)
        }

        if (!stream.start()) {
            Log.e(TAG, "Failed to start native UsbAudioStream")
            stream.release()
            return
        }

        usbAudioStream = stream
        currentSampleRate = sampleRate
        currentChannels = 2
        inputChannels = channelCount
        currentEncoding = pcmEncoding
        currentSourceBitDepth = sourceBitDepth
        handleBufferCallCount = 0L

        usbStreamingThread =
            UsbStreamingThread(stream, initiallyPaused = !isPlaying).also {
                it.start()
            }
        silentHeartbeat.start()
        if (isPlaying) {
            silentHeartbeat.resume()
        } else {
            silentHeartbeat.pause()
        }
        org.melodist.playback.PlaybackManager.updateUsbBitPerfectStatus(
            isConnected = true,
            deviceName = info.deviceName,
            sampleRate = sampleRate,
            bitDepth = sourceBitDepth,
            isStreaming = isPlaying,
        )
        updateUsbVolume()
        Log.i(TAG, "USB Bit-Perfect audio sink established successfully")
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val thread = usbStreamingThread
        val stream = usbAudioStream
        if (thread == null || stream == null) {
            if (isMutedOnDetached) {
                buffer.position(buffer.limit())
                return true
            }
            return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        if (thread.queueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
            return false
        }

        handleBufferCallCount++
        if (isEndOfStreamHandled || basePresentationTimeUs == C.TIME_UNSET) {
            isEndOfStreamHandled = false
            basePresentationTimeUs = presentationTimeUs
            startFramesWritten = stream.framesWritten
        }

        val snapshot = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)

        if (currentEncoding == C.ENCODING_PCM_FLOAT) {
            if (inputChannels > 2) {
                val stereo = AntiClippingSurroundDownmixer.downmixFloatToStereo(snapshot, inputChannels)
                thread.enqueue(stereo)
            } else if (inputChannels == 1) {
                val stereo = AntiClippingSurroundDownmixer.duplicateMonoFloat(snapshot)
                thread.enqueue(stereo)
            } else {
                val totalSamples = snapshot.remaining() / 4
                if (totalSamples > 0) {
                    val floatBuf = FloatArray(totalSamples)
                    snapshot.asFloatBuffer().get(floatBuf)
                    thread.enqueue(floatBuf)
                }
            }
        } else {
            val remaining = snapshot.remaining()
            if (remaining > 0) {
                if (inputChannels > 2) {
                    val downmixed =
                        when (currentSourceBitDepth) {
                            16 -> AntiClippingSurroundDownmixer.downmixPcm16ToStereo(snapshot, inputChannels)
                            24 -> AntiClippingSurroundDownmixer.downmixPcm24ToStereo(snapshot, inputChannels)
                            32 -> AntiClippingSurroundDownmixer.downmixPcm32ToStereo(snapshot, inputChannels)
                            else -> AntiClippingSurroundDownmixer.downmixPcm16ToStereo(snapshot, inputChannels)
                        }
                    thread.enqueueRaw(downmixed, currentSourceBitDepth)
                } else if (inputChannels == 1) {
                    val stereo =
                        when (currentSourceBitDepth) {
                            16 -> AntiClippingSurroundDownmixer.duplicateMonoPcm16(snapshot)
                            24 -> AntiClippingSurroundDownmixer.duplicateMonoPcm24(snapshot)
                            32 -> AntiClippingSurroundDownmixer.duplicateMonoPcm32(snapshot)
                            else -> AntiClippingSurroundDownmixer.duplicateMonoPcm16(snapshot)
                        }
                    thread.enqueueRaw(stereo, currentSourceBitDepth)
                } else {
                    val rawBytes = ByteArray(remaining)
                    snapshot.get(rawBytes)
                    thread.enqueueRaw(rawBytes, currentSourceBitDepth)
                }
            }
        }

        buffer.position(buffer.limit())
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val stream = usbAudioStream ?: return delegate.getCurrentPositionUs(sourceEnded)
        val sampleRate = currentSampleRate
        if (sampleRate <= 0) {
            return 0L
        }
        val baseTimeUs = if (basePresentationTimeUs != C.TIME_UNSET) basePresentationTimeUs else 0L
        val currentFrames = stream.framesWritten
        val playedFrames = (currentFrames - startFramesWritten).coerceAtLeast(0L)
        val playedUs = (playedFrames * 1_000_000L) / sampleRate
        return baseTimeUs + playedUs
    }

    override fun handleDiscontinuity() {
        basePresentationTimeUs = C.TIME_UNSET
        delegate.handleDiscontinuity()
    }

    override fun hasPendingData(): Boolean {
        if (usbAudioStream != null) {
            return usbStreamingThread?.hasPendingData() == true
        }
        return delegate.hasPendingData()
    }

    override fun playToEndOfStream() {
        if (usbAudioStream != null) {
            if (isEndOfStreamHandled) return
            isEndOfStreamHandled = true
        }
        delegate.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        if (usbAudioStream != null) {
            return isEndOfStreamHandled && (usbStreamingThread?.hasPendingData() != true)
        }
        return delegate.isEnded
    }

    override fun play() {
        isMutedOnDetached = false
        isPlaying = true
        delegate.play()
        silentHeartbeat.resume()
        usbStreamingThread?.resumeStreaming()
        if (usbAudioStream != null) {
            org.melodist.playback.PlaybackManager.updateUsbBitPerfectStatus(
                isConnected = true,
                sampleRate = currentSampleRate,
                bitDepth = currentSourceBitDepth,
                isStreaming = true,
            )
        }
    }

    override fun pause() {
        isPlaying = false
        delegate.pause()
        silentHeartbeat.pause()
        usbStreamingThread?.pauseStreaming()
        if (usbAudioStream != null) {
            org.melodist.playback.PlaybackManager.updateUsbBitPerfectStatus(
                isConnected = true,
                isStreaming = false,
            )
        }
    }

    override fun flush() {
        isPlaying = false
        isEndOfStreamHandled = false
        basePresentationTimeUs = C.TIME_UNSET
        startFramesWritten = 0L
        delegate.flush()
        silentHeartbeat.pause()
        usbStreamingThread?.flush()
        usbAudioStream?.flush()
    }

    override fun reset() {
        isEndOfStreamHandled = false
        basePresentationTimeUs = C.TIME_UNSET
        startFramesWritten = 0L
        delegate.reset()
        tearDownUsbPipeline()
    }

    private fun tearDownUsbPipeline() {
        val hadUsbPipeline = usbAudioStream != null
        isPlaying = false
        basePresentationTimeUs = C.TIME_UNSET
        startFramesWritten = 0L
        silentHeartbeat.stop()
        usbStreamingThread?.stop()

        usbAudioStream?.let { stream ->
            stream.stop()
            stream.drainUrbs()
            stream.release()
        }
        usbAudioStream = null
        currentSampleRate = 0
        currentChannels = 0
        currentEncoding = 0
        handleBufferCallCount = 0L
        clearDelegateDevice()
        delegate.flush()
        if (hadUsbPipeline) {
            delegate.pause()
            isMutedOnDetached = true
        }
        val device = usbAudioDevice.findUsbAudioDevice()
        val hasPerm = device != null && usbAudioDevice.hasPermission(device)
        org.melodist.playback.PlaybackManager.updateUsbBitPerfectStatus(
            isConnected = hasPerm,
            deviceName = if (hasPerm) device.productName else null,
            isStreaming = false,
        )
    }

    private fun clearDelegateDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            delegate.setPreferredDevice(null)
        }
    }

    private fun routeDelegateToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val speaker =
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            if (speaker != null) {
                delegate.setPreferredDevice(speaker)
                Log.d(TAG, "Routed delegate AudioTrack to built-in speaker")
            }
        }
    }

    private fun updateUsbVolume() {
        val stream = usbAudioStream ?: return
        if (!org.melodist.data.AppSettingsManager.settings.value.enableUsbVolumeControl) {
            stream.setVolume(1.0f)
            return
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val ratio = (current.toFloat() / max.toFloat()).coerceIn(0.0f, 1.0f)
        stream.setVolume(ratio)
    }

    companion object {
        private const val TAG = "UsbAudioSink"
        private const val QUEUE_BACKPRESSURE_THRESHOLD = 96
    }
}
