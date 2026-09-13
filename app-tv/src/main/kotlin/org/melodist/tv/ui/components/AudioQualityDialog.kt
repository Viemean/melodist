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
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.toMonetContainer

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
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val monetSurfaceColor = rememberMonetSurfaceColor(currentSong?.coverUrl)
    val dialogBackgroundColor =
        remember(monetSurfaceColor) {
            monetSurfaceColor.toMonetContainer(elevation = 0.08f).copy(alpha = 0.95f)
        }

    Dialog(onDismissRequest = onDismiss) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.28f)
        }

        Box(
            modifier =
                Modifier
                    .width(680.dp)
                    .wrapContentHeight()
                    .clip(MelodistShapes.DialogCorner)
                    .background(dialogBackgroundColor)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), MelodistShapes.DialogCorner)
                    .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {

                // 全景声
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "全景声",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.90f),
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

                // 立体声
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "立体声",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.90f),
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

private fun getOfficialTierColor(tier: AudioQualityTier): Color =
    when (tier) {
        AudioQualityTier.Master -> Color(0xFFFFD580)
        AudioQualityTier.Premium -> Color(0xFFFFB84D)
        AudioQualityTier.HiRes -> Color(0xFFFFCF40)
        AudioQualityTier.SQ -> Color(0xFF22E59E)
        AudioQualityTier.HQ -> Color(0xFF68B5FF)
        AudioQualityTier.Standard -> Color(0xFFF1F5F9)
        AudioQualityTier.Dolby -> Color(0xFFFFCF40)
        AudioQualityTier.Atmos71 -> Color(0xFF5CE1E6)
        AudioQualityTier.Atmos51 -> Color(0xFF6BE5FF)
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
    val officialColor = remember(tier) { getOfficialTierColor(tier) }

    val bg =
        when {
            !isUsable -> Color.White.copy(alpha = 0.05f)
            isFocused -> Color.White
            isSelected -> officialColor.copy(alpha = 0.22f)
            else -> Color.White.copy(alpha = 0.12f)
        }

    val textColor =
        when {
            !isUsable -> officialColor.copy(alpha = 0.65f)
            isFocused -> Color.Black
            else -> officialColor
        }

    val border =
        when {
            !isUsable -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), MelodistShapes.ButtonCorner)
            isFocused -> Modifier.border(BorderStroke(2.5.dp, MelodistColors.FocusTeal), MelodistShapes.ButtonCorner)
            isSelected -> Modifier.border(BorderStroke(1.dp, officialColor.copy(alpha = 0.80f)), MelodistShapes.ButtonCorner)
            else -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)), MelodistShapes.ButtonCorner)
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
                .width(110.dp)
                .height(52.dp)
                .then(border)
                .clip(MelodistShapes.ButtonCorner)
                .background(bg)
                .then(clickableModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge,
            color = textColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
