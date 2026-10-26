package org.melodist.playback

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import org.melodist.api.QualityTierFilter
import org.melodist.model.AudioQualityTier

object DeviceAudioCapability {
    @Volatile
    private var isInitialized = false

    @Volatile
    private var supportsDolby = false

    @Volatile
    private var supportsDts = false

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

            // 探测系统 MediaCodec 是否支持 AC-4 / E-AC-3 / DTS 硬件音频解码器
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecInfos = codecList.codecInfos
            for (info in codecInfos) {
                if (info.isEncoder) continue
                for (mime in info.supportedTypes) {
                    val m = mime.lowercase()
                    if (m == "audio/eac3" || m == "audio/eac3-joc" || m == "audio/ac4" || m == "audio/ac3") {
                        supportsDolby = true
                    }
                    if (m == "audio/vnd.dts" || m == "audio/vnd.dts.hd" || m == "audio/vnd.dts.uhd") {
                        supportsDts = true
                    }
                }
            }
        } catch (_: Exception) {
            supportsDolby = false
            supportsDts = false
            supportsSurround51 = false
            supportsSurround71 = false
        }

        // 同步通知网关层过滤不支持的音质探测请求
        QualityTierFilter.isTierSupported = { isTierSupported(it) }
    }

    fun isDolbySupported(): Boolean = supportsDolby

    fun isDtsSupported(): Boolean = supportsDts

    fun isTierSupported(tier: AudioQualityTier): Boolean =
        when (tier) {
            AudioQualityTier.Dolby -> supportsDolby
            AudioQualityTier.DtsX -> supportsDts
            AudioQualityTier.Master, AudioQualityTier.HiRes, AudioQualityTier.SQ -> AudioCodecDetector.isFlacSupported
            else -> true
        }

    /**
     * 判定当前设备硬件与声卡是否支持该音质/声道规格
     * 返回: Pair(是否支持, 不支持原因/说明)
     */
    fun checkDeviceSupport(tier: AudioQualityTier): Pair<Boolean, String?> =
        when (tier) {
            AudioQualityTier.Dolby -> {
                if (supportsDolby) Pair(true, null) else Pair(false, "设备缺少杜比音频硬件解码支持")
            }

            AudioQualityTier.DtsX -> {
                if (supportsDts) Pair(true, null) else Pair(false, "设备缺少 DTS 音频硬件解码支持")
            }

            AudioQualityTier.Master, AudioQualityTier.HiRes, AudioQualityTier.SQ -> {
                if (AudioCodecDetector.isFlacSupported) Pair(true, null) else Pair(false, "设备缺少 FLAC 解码器")
            }

            else -> {
                Pair(true, null)
            }
        }
}
