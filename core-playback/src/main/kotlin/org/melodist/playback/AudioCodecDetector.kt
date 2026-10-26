package org.melodist.playback

import android.media.MediaCodecList
import androidx.annotation.VisibleForTesting
import androidx.media3.common.MimeTypes

object AudioCodecDetector {
    @get:VisibleForTesting
    @set:VisibleForTesting
    var flacSupportedOverride: Boolean? = null

    private val cachedSupportedAudioMimes: Set<String> by lazy {
        val mimes = mutableSetOf<String>()
        mimes.add(MimeTypes.AUDIO_RAW.lowercase())

        var listLoaded = false
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val infos = codecList.codecInfos
            if (infos != null && infos.isNotEmpty()) {
                for (info in infos) {
                    if (info.isEncoder) continue
                    for (type in info.supportedTypes) {
                        if (type.startsWith("audio/", ignoreCase = true)) {
                            mimes.add(type.lowercase())
                        }
                    }
                }
                listLoaded = true
            }
        } catch (_: Throwable) {
            // JVM unit test 或宿主桩环境
        }

        if (!listLoaded) {
            mimes.add(MimeTypes.AUDIO_FLAC.lowercase())
            mimes.add(MimeTypes.AUDIO_MPEG.lowercase())
            mimes.add(MimeTypes.AUDIO_AAC.lowercase())
            mimes.add(MimeTypes.AUDIO_VORBIS.lowercase())
            mimes.add(MimeTypes.AUDIO_OPUS.lowercase())
        }
        mimes
    }

    fun isMimeSupported(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return true
        val normalized = mime.lowercase()
        if (normalized == MimeTypes.AUDIO_RAW.lowercase() ||
            normalized == "audio/wav" ||
            normalized == "audio/x-wav"
        ) {
            return true
        }
        return cachedSupportedAudioMimes.contains(normalized)
    }

    val isFlacSupported: Boolean
        get() = flacSupportedOverride ?: (isMimeSupported(MimeTypes.AUDIO_FLAC) || isFlacExtensionAvailable())

    private fun isFlacExtensionAvailable(): Boolean =
        try {
            androidx.media3.decoder.flac.FlacLibrary
                .isAvailable()
        } catch (_: Throwable) {
            false
        }

    fun getSupportedAudioMimes(): Set<String> = cachedSupportedAudioMimes
}
