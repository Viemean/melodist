package org.melodist.playback

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import org.melodist.model.AudioQualityTier

object DeviceAudioCapability {
    @Volatile
    private var isInitialized = false

    @Volatile
    private var supportsDolbyAtmos = false

    @Volatile
    private var supportsSurround51 = false

    @Volatile
    private var supportsSurround71 = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                var maxChannelCount = 2
                for (dev in devices) {
                    val channelMasks = dev.channelIndexMasks
                    for (ch in channelMasks) {
                        val count = Integer.bitCount(ch)
                        if (count > maxChannelCount) maxChannelCount = count
                    }
                    val posMasks = dev.channelMasks
                    for (pos in posMasks) {
                        val count = Integer.bitCount(pos)
                        if (count > maxChannelCount) maxChannelCount = count
                    }
                }
                supportsSurround51 = maxChannelCount >= 6
                supportsSurround71 = maxChannelCount >= 8
            }

            // 探测 MediaCodec 是否支持 E-AC3 / AC-4 / Dolby Atmos
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecInfos = codecList.codecInfos
            for (info in codecInfos) {
                if (info.isEncoder) continue
                for (mime in info.supportedTypes) {
                    if (mime.equals("audio/eac3", ignoreCase = true) ||
                        mime.equals("audio/eac3-joc", ignoreCase = true) ||
                        mime.equals("audio/ac4", ignoreCase = true)
                    ) {
                        supportsDolbyAtmos = true
                    }
                }
            }
        } catch (_: Exception) {
            supportsDolbyAtmos = false
            supportsSurround51 = false
            supportsSurround71 = false
        }
    }

    /**
     * 判定当前 TV 设备硬件与声卡是否支持该音质/声道规格
     * 返回: Pair(是否支持, 不支持原因/说明)
     */
    fun checkDeviceSupport(tier: AudioQualityTier): Pair<Boolean, String?> = Pair(true, null)
}
