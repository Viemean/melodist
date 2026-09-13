package org.melodist.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import org.melodist.model.AudioQualityTier
import org.melodist.playback.DeviceAudioCapability
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

private val StereoTiers =
    listOf(
        AudioQualityTier.Master,
        AudioQualityTier.HiRes,
        AudioQualityTier.SQ,
        AudioQualityTier.HQ,
        AudioQualityTier.Standard,
    )

private val SpatialTiers =
    listOf(
        AudioQualityTier.Atmos71,
        AudioQualityTier.Atmos51,
        AudioQualityTier.Dolby,
        AudioQualityTier.Premium,
    )

@Composable
fun AudioQualityDialog(
    selectedTier: AudioQualityTier = AudioQualityTier.SQ,
    onSelectTier: (AudioQualityTier) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val availableTiers by PlaybackManager.availableTiers.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(680.dp)
                    .clip(MelodistShapes.CardCorner)
                    .background(MelodistColors.ContainerDark)
                    .border(BorderStroke(2.dp, MelodistColors.FocusTeal.copy(alpha = 0.6f)), MelodistShapes.CardCorner)
                    .padding(32.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "音质与声道规格切换",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )

                    Text(
                        text = "当前: ${AudioQualityTier.getBadge(selectedTier)}",
                        fontSize = 14.sp,
                        color = MelodistColors.AccentGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // 全景声双轨
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "全景声轨道",
                        fontSize = 14.sp,
                        color = MelodistColors.TextSecondary,
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(SpatialTiers) { tier ->
                            val (isDevSupport, reason) = DeviceAudioCapability.checkDeviceSupport(tier)
                            val isStreamAvail =
                                if (availableTiers.isNotEmpty()) {
                                    tier in availableTiers
                                } else {
                                    tier == selectedTier || tier == AudioQualityTier.Standard
                                }
                            TierOptionItem(
                                tier = tier,
                                isSelected = tier == selectedTier,
                                isDeviceSupported = isDevSupport,
                                isStreamAvailable = isStreamAvail,
                                unsupportedReason = reason,
                                onSelect = {
                                    onSelectTier(tier)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }

                // 立体声音轨
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "立体声",
                        fontSize = 14.sp,
                        color = MelodistColors.TextSecondary,
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(StereoTiers) { tier ->
                            val (isDevSupport, reason) = DeviceAudioCapability.checkDeviceSupport(tier)
                            val isStreamAvail =
                                if (availableTiers.isNotEmpty()) {
                                    tier in availableTiers
                                } else {
                                    tier == selectedTier || tier == AudioQualityTier.Standard
                                }
                            TierOptionItem(
                                tier = tier,
                                isSelected = tier == selectedTier,
                                isDeviceSupported = isDevSupport,
                                isStreamAvailable = isStreamAvail,
                                unsupportedReason = reason,
                                onSelect = {
                                    onSelectTier(tier)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierOptionItem(
    tier: AudioQualityTier,
    isSelected: Boolean,
    isDeviceSupported: Boolean,
    isStreamAvailable: Boolean,
    unsupportedReason: String?,
    onSelect: () -> Unit,
) {
    val isUsable = (isDeviceSupported && isStreamAvailable) || isSelected
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val badge = AudioQualityTier.getBadge(tier)
    val statusText =
        when {
            !isDeviceSupported -> unsupportedReason ?: "设备不支持"
            !isStreamAvailable ->
                if (tier in
                    listOf(
                        AudioQualityTier.Master,
                        AudioQualityTier.Atmos71,
                        AudioQualityTier.Atmos51,
                        AudioQualityTier.Dolby,
                        AudioQualityTier.Premium,
                    )
                ) {
                    "暂无或需VIP"
                } else {
                    "暂无音源"
                }
            unsupportedReason != null -> unsupportedReason
            else ->
                when (tier) {
                    AudioQualityTier.Master -> "母带级"
                    AudioQualityTier.HiRes -> "超清无损"
                    AudioQualityTier.SQ -> "标准无损"
                    AudioQualityTier.HQ -> "高品质"
                    AudioQualityTier.Standard -> "标准音质"
                    AudioQualityTier.Atmos71 -> "7.1 全景"
                    AudioQualityTier.Atmos51 -> "5.1 环绕"
                    AudioQualityTier.Dolby -> "杜比全景声"
                    AudioQualityTier.Premium -> "臻品母带"
                }
        }

    val bg =
        when {
            !isUsable -> Color.White.copy(alpha = 0.03f)
            isFocused -> Color.White
            isSelected -> MelodistColors.AccentGreen.copy(alpha = 0.2f)
            else -> MelodistColors.ContainerDarkSecondary
        }

    val textColor =
        when {
            !isUsable -> MelodistColors.TextMuted.copy(alpha = 0.35f)
            isFocused -> Color.Black
            isSelected -> MelodistColors.AccentGreen
            else -> MelodistColors.TextPrimary
        }

    val subTextColor =
        when {
            !isUsable -> MelodistColors.TextMuted.copy(alpha = 0.3f)
            isFocused -> Color.DarkGray
            else -> MelodistColors.TextMuted
        }

    val border =
        when {
            !isUsable -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.04f)), MelodistShapes.ButtonCorner)
            isFocused -> Modifier.border(BorderStroke(2.5.dp, MelodistColors.FocusTeal), MelodistShapes.ButtonCorner)
            isSelected -> Modifier.border(BorderStroke(1.dp, MelodistColors.AccentGreen), MelodistShapes.ButtonCorner)
            else -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), MelodistShapes.ButtonCorner)
        }

    val clickableModifier =
        if (isUsable) {
            Modifier
                .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
                .focusable(interactionSource = interactionSource)
        } else {
            Modifier
        }

    Box(
        modifier =
            Modifier
                .then(border)
                .clip(MelodistShapes.ButtonCorner)
                .background(bg)
                .then(clickableModifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = badge,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = statusText,
                color = subTextColor,
                fontSize = 10.sp,
            )
        }
    }
}
