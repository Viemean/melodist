package org.melodist.model

object AudioQualityFormatter {
    fun formatTrackQuality(
        tier: AudioQualityTier?,
        sampleRateHz: Int = 0,
        bitDepth: Int = 0,
        bitrateKbps: Int = 0,
    ): String {
        val tierBadge = tier?.let { AudioQualityTier.getBadge(it) } ?: "SQ"
        val specParts = mutableListOf<String>()
        if (bitDepth > 0) {
            specParts.add("$bitDepth-bit")
        }
        if (sampleRateHz > 0) {
            val khz =
                if (sampleRateHz % 1000 == 0) {
                    "${sampleRateHz / 1000}kHz"
                } else {
                    String.format(java.util.Locale.US, "%.1fkHz", sampleRateHz / 1000f)
                }
            specParts.add(khz)
        }
        if (bitrateKbps > 0) {
            specParts.add("${bitrateKbps}kbps")
        }
        return if (specParts.isEmpty()) {
            tierBadge
        } else {
            "$tierBadge · ${specParts.joinToString(" / ")}"
        }
    }

    fun formatDuration(durationSeconds: Int): String {
        if (durationSeconds <= 0) return "0:00"
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    fun formatDurationMs(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000).toInt()
        return formatDuration(totalSeconds)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
