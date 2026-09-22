package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.api.AudioHeaderSniffer
import org.melodist.data.AppSettingsManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.QualityOption
import org.melodist.playback.AudioTrackSpec
import org.melodist.playback.PlaybackManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioQualityBottomSheet(
    currentTier: AudioQualityTier,
    availableTiers: Set<AudioQualityTier> = emptySet(),
    title: String = "切换播放音质",
    currentTrackSpec: AudioTrackSpec? = null,
    probedQualityOptions: List<QualityOption> = emptyList(),
    songDurationSec: Int = 0,
    isProbing: Boolean = false,
    enforceCellularRestriction: Boolean = true,
    showSubtitle: Boolean = true,
    targetSong: org.melodist.model.Song? = null,
    onSelectTier: (AudioQualityTier) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val settings by AppSettingsManager.settings.collectAsState()
    val isCellular = remember { PlaybackManager.isCellularNetwork() }
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val effectiveSong = targetSong ?: currentSong
    val isLocalOrWebDav = remember(effectiveSong) { PlaybackManager.isLocalOrWebDavSong(effectiveSong) }

    var enrichedOptions by remember(probedQualityOptions) { mutableStateOf<List<QualityOption>>(probedQualityOptions) }
    var isSniffing by remember(probedQualityOptions) { mutableStateOf(false) }

    LaunchedEffect(probedQualityOptions, songDurationSec, isLocalOrWebDav) {
        if (!isLocalOrWebDav && probedQualityOptions.any { it.isAvailable && !it.playUrl.isNullOrBlank() }) {
            isSniffing = true
            try {
                enrichedOptions = AudioHeaderSniffer.enrichQualityOptions(probedQualityOptions, songDurationSec)
            } catch (_: Exception) {
            } finally {
                isSniffing = false
            }
        } else {
            enrichedOptions = probedQualityOptions
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val supportedTiers =
        remember(availableTiers, enrichedOptions, currentTier, isLocalOrWebDav) {
            if (isLocalOrWebDav) {
                listOf(currentTier)
            } else {
                val probedAvailable = enrichedOptions.filter { it.isAvailable }.map { it.tier }.toSet()
                val combined =
                    if (probedAvailable.isNotEmpty()) {
                        probedAvailable + currentTier
                    } else if (availableTiers.isNotEmpty()) {
                        availableTiers + currentTier
                    } else {
                        setOf(currentTier, AudioQualityTier.SQ, AudioQualityTier.HQ, AudioQualityTier.Standard)
                    }

                fun getWeight(tier: AudioQualityTier): Int =
                    when (tier) {
                        AudioQualityTier.Master -> 100
                        AudioQualityTier.Premium -> 95
                        AudioQualityTier.HiRes -> 90
                        AudioQualityTier.Atmos -> 85
                        AudioQualityTier.Dolby -> 80
                        AudioQualityTier.SQ -> 70
                        AudioQualityTier.HQ -> 60
                        AudioQualityTier.Standard -> 50
                    }
                combined.sortedByDescending { getWeight(it) }
            }
        }

    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isProbing || isSniffing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            supportedTiers.forEach { tier ->
                val isSelected = tier == currentTier
                val probedOption =
                    remember(tier, enrichedOptions) {
                        enrichedOptions.find { it.tier == tier }
                    }
                if (enrichedOptions.isNotEmpty() && !isSelected && (probedOption == null || !probedOption.isAvailable)) {
                    return@forEach
                }
                val isRestricted =
                    enforceCellularRestriction &&
                        isCellular &&
                        !isLocalOrWebDav &&
                        PlaybackManager.getAudioQualityRank(tier) > PlaybackManager.getAudioQualityRank(settings.cellularQualityTier)

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                if (isRestricted) {
                                    Toast
                                        .makeText(
                                            context,
                                            "移动网络下音质已限制为最高 ${AudioQualityTier.getBadge(settings.cellularQualityTier)}",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    onSelectTier(settings.cellularQualityTier)
                                } else {
                                    onSelectTier(tier)
                                }
                                onDismissRequest()
                            }.background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = getQualityBadgeLabel(tier),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isRestricted) {
                                Spacer(modifier = Modifier.size(6.dp))
                                Text(
                                    text = "受移动网络限制",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (showSubtitle || isRestricted) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text =
                                    if (isRestricted) {
                                        "当前移动网络限额为 ${AudioQualityTier.getBadge(settings.cellularQualityTier)}"
                                    } else {
                                        getQualityTierSpec(
                                            tier = tier,
                                            isSelected = isSelected,
                                            currentTrackSpec = currentTrackSpec,
                                            probedOption = probedOption,
                                            songDurationSec = songDurationSec,
                                        )
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "已选择",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

fun getQualityBadgeLabel(tier: AudioQualityTier): String =
    when (tier) {
        AudioQualityTier.Master -> "母带"
        AudioQualityTier.HiRes -> "Hi-Res"
        AudioQualityTier.SQ -> "SQ"
        AudioQualityTier.HQ -> "HQ"
        AudioQualityTier.Standard -> "标准"
        AudioQualityTier.Atmos -> "全景声"
        AudioQualityTier.Dolby -> "杜比"
        AudioQualityTier.Premium -> "臻品"
    }

fun getQualityTierSpec(
    tier: AudioQualityTier,
    isSelected: Boolean,
    currentTrackSpec: AudioTrackSpec?,
    probedOption: QualityOption?,
    songDurationSec: Int,
): String {
    val sizeBytes = probedOption?.sizeBytes ?: 0L
    val sizeFormatted = formatAdaptiveFileSize(sizeBytes)

    val format =
        if (isSelected && currentTrackSpec != null && currentTrackSpec.format.isNotBlank()) {
            currentTrackSpec.format
        } else {
            probedOption?.format?.ifBlank { null } ?: ""
        }

    val bitDepthVal =
        if (isSelected && currentTrackSpec != null && currentTrackSpec.bitDepth > 0) {
            currentTrackSpec.bitDepth
        } else {
            probedOption?.bitDepth ?: 0
        }
    val bitDepth = if (bitDepthVal > 0) "${bitDepthVal}bit" else ""

    val sRateHz =
        if (isSelected && currentTrackSpec != null && currentTrackSpec.sampleRateHz > 0) {
            currentTrackSpec.sampleRateHz
        } else {
            probedOption?.sampleRateHz ?: 0
        }
    val sampleRate =
        if (sRateHz > 0) {
            if (sRateHz % 1000 == 0) {
                "${sRateHz / 1000}khz"
            } else {
                String.format(java.util.Locale.US, "%.1fkhz", sRateHz / 1000.0)
            }
        } else {
            ""
        }

    val bitrateKbps: Int =
        when {
            isSelected && currentTrackSpec != null && currentTrackSpec.bitrateKbps > 0 -> {
                currentTrackSpec.bitrateKbps
            }
            probedOption?.bitrate?.isNotBlank() == true && probedOption.bitrate.endsWith("kbps") -> {
                probedOption.bitrate.removeSuffix("kbps").trim().toIntOrNull() ?: 0
            }
            sizeBytes > 0L && songDurationSec > 0 -> {
                ((sizeBytes * 8.0) / songDurationSec / 1000.0).toInt()
            }
            else -> 0
        }
    val bitrate = if (bitrateKbps > 0) "$bitrateKbps kbps" else ""

    val parts = listOfNotNull(format, bitDepth, sampleRate, bitrate, sizeFormatted).filter { it.isNotBlank() }
    // HiRes with no spec data from API: skip the "(FLAC)" placeholder; real specs appear after playback via currentTrackSpec.
    if (tier == AudioQualityTier.HiRes && parts.size <= 1 && bitDepth.isBlank() && sampleRate.isBlank() && bitrate.isBlank() && sizeFormatted.isBlank()) {
        return ""
    }
    return if (parts.isEmpty()) "" else "(${parts.joinToString(" ")})"
}

fun formatAdaptiveFileSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) {
        String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
        val kb = bytes / 1024.0
        String.format(java.util.Locale.US, "%.0f KB", kb)
    }
}
