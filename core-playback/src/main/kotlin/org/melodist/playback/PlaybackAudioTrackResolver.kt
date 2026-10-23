package org.melodist.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Tracks
import org.melodist.data.AppSettingsManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.QualityOption
import org.melodist.model.Song

/**
 * 音频音轨解析结果承载体。
 *
 * @property trackSpec 音频流物理规格信息
 * @property detectedTier 推导出的音质级别
 * @property shouldApplyTierToSong 是否应将推导音质同步回曲目实体
 */
data class ResolvedAudioTrack(
    val trackSpec: AudioTrackSpec,
    val detectedTier: AudioQualityTier,
    val shouldApplyTierToSong: Boolean,
)

/**
 * 音轨参数解析与 USB Bit-Perfect 混音器配置协调器。
 */
class PlaybackAudioTrackResolver(
    private val usbRouter: UsbAudioRouter,
    private val getContext: () -> Context?,
    private val onReloadAudioPipelineRequest: () -> Unit,
) {
    /**
     * 解析 ExoPlayer 当前选中的音频流轨道并更新物理硬件混音状态。
     *
     * @param tracks 当前媒体流轨道信息
     * @param currentSong 当前播放曲目
     * @param currentTier 当前设定的音质档位
     * @param probedOptions 当前已探测的音质选项列表
     * @return 解析完成的音轨规格结果；若无选中的有效音频轨道则返回 null
     */
    fun resolveSelectedAudioTrack(
        tracks: Tracks,
        currentSong: Song?,
        currentTier: AudioQualityTier,
        probedOptions: List<QualityOption>,
    ): ResolvedAudioTrack? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                val sRate = format.sampleRate
                val channels = format.channelCount
                val mime = format.sampleMimeType
                val bitrate = format.bitrate

                val bitDepth =
                    when (format.pcmEncoding) {
                        C.ENCODING_PCM_24BIT -> 24
                        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                        else -> {
                            val probedDepth = probedOptions.find { it.tier == currentTier }?.bitDepth ?: 0
                            when {
                                probedDepth > 0 -> probedDepth
                                currentTier == AudioQualityTier.Master || currentTier == AudioQualityTier.HiRes -> 24
                                sRate >= 88200 -> 24
                                else -> 16
                            }
                        }
                    }

                if (sRate <= 0) continue

                val isUsbExclusive = AppSettingsManager.settings.value.enableUsbExclusive
                if (isUsbExclusive) {
                    val context = getContext()
                    val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    val usbDevice = usbRouter.findUsbAudioDevice(audioManager)
                    if (audioManager != null && usbDevice != null) {
                        val needReload =
                            usbRouter.configureBitPerfectMixer(
                                audioManager = audioManager,
                                usbDevice = usbDevice,
                                targetRate = sRate,
                                channelCount = channels,
                                pcmEncoding = format.pcmEncoding,
                            )
                        if (needReload) {
                            onReloadAudioPipelineRequest()
                        }
                    }
                }

                val detectedTier =
                    AudioQualityTier.inferFromAudioFormat(
                        sampleRate = sRate,
                        bitsPerSample = bitDepth,
                        channelCount = if (channels > 0) channels else 2,
                        mimeType = mime,
                        bitrate = if (bitrate > 0) bitrate else 0,
                    )

                val formatStr =
                    when {
                        mime?.contains("flac") == true -> "Flac"
                        mime?.contains("mp4a") == true || mime?.contains("aac") == true -> "AAC"
                        mime?.contains("mpeg") == true || mime?.contains("mp3") == true -> "Mp3"
                        mime?.contains("eac3-joc") == true || mime?.contains("atmos") == true -> "E-AC3 JOC"
                        mime?.contains("eac3") == true -> "E-AC3"
                        mime?.contains("ac3") == true -> "AC3"
                        mime?.contains("opus") == true -> "Opus"
                        mime?.contains("ogg") == true -> "OGG"
                        mime?.contains("wav") == true -> "Wav"
                        else -> mime?.substringAfterLast('/')?.uppercase() ?: "Flac"
                    }

                val probedSize = probedOptions.find { it.tier == currentTier }?.sizeBytes ?: 0L
                val currentDur = currentSong?.durationSeconds ?: 0
                val calcKbps =
                    if (currentDur > 0 && probedSize > 0L) {
                        ((probedSize * 8L) / 1024L / currentDur).toInt()
                    } else {
                        0
                    }
                val kbps = if (bitrate > 0) bitrate / 1000 else calcKbps

                val trackSpec =
                    AudioTrackSpec(
                        format = formatStr,
                        bitDepth = bitDepth,
                        sampleRateHz = sRate,
                        bitrateKbps = kbps,
                    )

                val currentMid = currentSong?.songMid.orEmpty()
                val isLocalOrWebDav =
                    currentMid.startsWith("webdav_") ||
                        currentMid.startsWith("local_") ||
                        !currentSong?.localFilePath.isNullOrBlank()

                return ResolvedAudioTrack(
                    trackSpec = trackSpec,
                    detectedTier = detectedTier,
                    shouldApplyTierToSong = isLocalOrWebDav,
                )
            }
        }
        return null
    }
}
