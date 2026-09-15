package org.melodist.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbAudioRouter(
    private val onDeviceStateChanged: (needReload: Boolean) -> Unit,
) {
    private val _activeUsbDeviceName = MutableStateFlow<String?>(null)
    val activeUsbDeviceName: StateFlow<String?> = _activeUsbDeviceName.asStateFlow()

    private var registeredCallback = false
    private var configuredMixerSampleRate = 0
    private var configuredMixerEncoding = 0

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                val hasUsb = addedDevices?.any { isUsbAudioDevice(it) } == true
                if (hasUsb) {
                    onDeviceStateChanged(true)
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val hasUsb = removedDevices?.any { isUsbAudioDevice(it) } == true
                if (hasUsb) {
                    onDeviceStateChanged(true)
                }
            }
        }

    fun register(context: Context) {
        if (!registeredCallback) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
            registeredCallback = true
        }
    }

    fun unregister(context: Context) {
        if (registeredCallback) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            registeredCallback = false
        }
    }

    fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    fun findUsbAudioDevice(audioManager: AudioManager?): AudioDeviceInfo? {
        if (audioManager == null) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull { isUsbAudioDevice(it) }
    }

    fun configureBitPerfectMixer(
        audioManager: AudioManager,
        usbDevice: AudioDeviceInfo,
        targetRate: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false

        val sampleRate = if (targetRate > 0) targetRate else 44100
        val targetEncoding =
            when (pcmEncoding) {
                C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
                C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }

        if (configuredMixerSampleRate == sampleRate && configuredMixerEncoding == targetEncoding) {
            return false
        }

        try {
            val mediaAttributes =
                android.media.AudioAttributes
                    .Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()

            val supportedMixers = audioManager.getSupportedMixerAttributes(usbDevice)
            Log.e(
                "MelodistPlayback",
                "USB DAC [${usbDevice.productName}] reported ${supportedMixers.size} supported mixer configurations",
            )
            for (m in supportedMixers) {
                Log.e(
                    "MelodistPlayback",
                    "DAC mixer config: behavior=${m.mixerBehavior}, rate=${m.format.sampleRate}, enc=${m.format.encoding}, ch=0x${Integer.toHexString(
                        m.format.channelMask,
                    )}",
                )
            }

            var bestMixer =
                supportedMixers.firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                        it.format.sampleRate == sampleRate &&
                        it.format.encoding == targetEncoding
                }

            if (bestMixer == null) {
                bestMixer =
                    supportedMixers.firstOrNull {
                        it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                            it.format.sampleRate == sampleRate
                    }
            }

            val finalMixer =
                bestMixer ?: run {
                    val format =
                        AudioFormat
                            .Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(targetEncoding)
                            .build()
                    AudioMixerAttributes
                        .Builder(format)
                        .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                        .build()
                }

            val applied = audioManager.setPreferredMixerAttributes(mediaAttributes, usbDevice, finalMixer)
            Log.e(
                "MelodistPlayback",
                "Applied Bit-Perfect mixer: success=$applied, rate=${finalMixer.format.sampleRate}Hz, enc=${finalMixer.format.encoding}, behavior=${finalMixer.mixerBehavior}",
            )

            val rateChanged = configuredMixerSampleRate != 0 && configuredMixerSampleRate != finalMixer.format.sampleRate
            configuredMixerSampleRate = finalMixer.format.sampleRate
            configuredMixerEncoding = finalMixer.format.encoding
            return rateChanged
        } catch (e: Exception) {
            Log.e("MelodistPlayback", "Failed to configure Bit-Perfect mixer attributes", e)
            return false
        }
    }

    fun updateUsbExclusiveRouting(
        context: Context?,
        player: ExoPlayer?,
        isUsbExclusive: Boolean,
        targetRate: Int = 0,
        channelCount: Int = 0,
        pcmEncoding: Int = 0,
    ) {
        val ctx = context ?: return
        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val usbAudioDevice = findUsbAudioDevice(audioManager)

        Log.i(
            "MelodistPlayback",
            "updateUsbExclusiveRouting: isUsbExclusive=$isUsbExclusive, usbAudioDevice=${usbAudioDevice?.productName}",
        )

        if (isUsbExclusive && usbAudioDevice != null) {
            val devName = usbAudioDevice.productName?.toString()?.ifBlank { null } ?: "USB 音频设备"
            _activeUsbDeviceName.value = "$devName (32-bit Float)"
            player?.setPreferredAudioDevice(usbAudioDevice)
            configureBitPerfectMixer(audioManager, usbAudioDevice, targetRate, channelCount, pcmEncoding)
        } else {
            _activeUsbDeviceName.value = null
            player?.setPreferredAudioDevice(null)
            configuredMixerSampleRate = 0
            configuredMixerEncoding = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && usbAudioDevice != null) {
                try {
                    val mediaAttributes =
                        android.media.AudioAttributes
                            .Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .build()
                    audioManager.clearPreferredMixerAttributes(mediaAttributes, usbAudioDevice)
                } catch (e: Exception) {
                    Log.w("MelodistPlayback", "Failed to clear preferred mixer attributes", e)
                }
            }
        }
    }
}
