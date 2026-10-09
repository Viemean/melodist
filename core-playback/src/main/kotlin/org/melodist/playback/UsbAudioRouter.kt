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

    /**
     * 注册 USB 音频设备插拔监听回调。
     *
     * @param context 上下文对象
     */
    fun register(context: Context) {
        if (!registeredCallback) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
            registeredCallback = true
        }
    }

    /**
     * 注销 USB 音频设备监听回调。
     *
     * @param context 上下文对象
     */
    fun unregister(context: Context) {
        if (registeredCallback) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            registeredCallback = false
        }
    }

    /**
     * 判定音频设备是否为 USB 音频输出外设。
     *
     * @param device 待检测的音频设备信息
     * @return 为 USB 音频输出设备返回 true
     */
    fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /**
     * 在系统当前连接的音频设备列表中查找 USB 输出设备。
     *
     * @param audioManager 系统音频管理器
     * @return 找到的首个 USB 音频设备，未连接返回 null
     */
    fun findUsbAudioDevice(audioManager: AudioManager?): AudioDeviceInfo? {
        if (audioManager == null) return null
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull { isUsbAudioDevice(it) }
    }

    /**
     * 配置 Android 14+ USB 位完美（Bit-Perfect）混音器属性。
     *
     * @param audioManager 系统音频管理器
     * @param usbDevice 目标 USB 输出设备
     * @param targetRate 目标采样率（Hz）
     * @param channelCount 声道数
     * @param pcmEncoding PCM 编码格式（如 C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT 等）
     * @return 混音器采样率是否发生变更
     */
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

    /**
     * 更新播放器的 USB 独占输出路由与硬件混音器模式。
     *
     * @param context 应用程序上下文
     * @param player ExoPlayer 播放器实例
     * @param isUsbExclusive 是否启用 USB 独占输出
     * @param targetRate 音频目标采样率（Hz），默认 0
     * @param channelCount 音频声道数，默认 0
     * @param pcmEncoding PCM 编码格式，默认 0
     */
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
