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
import androidx.compose.ui.window.DialogProperties
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.28f)
        }

        Box(
            modifier =
                Modifier
                    .width(660.dp)
                    .wrapContentHeight()
                    .clip(MelodistShapes.DialogCorner)
                    .background(dialogBackgroundColor)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), MelodistShapes.DialogCorner)
                    .padding(horizontal = 24.dp, vertical = 22.dp),
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(SpatialTiers) { tier ->
                            val (isDevSupport, reason) = DeviceAudioCapability.checkDeviceSupport(tier)
                            val isStreamAvail =
                                if (availableTiers.isNotEmpty()) {
                                    tier in availableTiers || tier == selectedTier
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(StereoTiers) { tier ->
                            val (isDevSupport, reason) = DeviceAudioCapability.checkDeviceSupport(tier)
                            val isStreamAvail =
                                if (availableTiers.isNotEmpty()) {
                                    tier in availableTiers || tier == selectedTier
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val isUsable = (isDeviceSupported && isStreamAvailable) || isSelected
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val badge = AudioQualityTier.getBadge(tier)
    val officialColor = remember(tier) { getOfficialTierColor(tier) }

    val statusText =
        when {
            isSelected -> "当前播放"
            !isDeviceSupported -> "不支持"
            !isStreamAvailable -> "无音源"
            else -> "可用"
        }

    val bg =
        when {
            isFocused && isUsable -> Color.White
            isFocused && !isUsable -> Color.White.copy(alpha = 0.12f)
            isSelected -> officialColor.copy(alpha = 0.22f)
            isUsable -> Color.White.copy(alpha = 0.10f)
            else -> Color.White.copy(alpha = 0.03f)
        }

    val titleColor =
        when {
            isFocused && isUsable -> Color.Black
            isFocused && !isUsable -> Color.White.copy(alpha = 0.50f)
            isUsable -> officialColor
            else -> Color.White.copy(alpha = 0.22f)
        }

    val subtitleColor =
        when {
            isFocused && isUsable -> if (isSelected) MelodistColors.FocusTeal else Color(0xFF475569)
            isFocused && !isUsable -> Color.White.copy(alpha = 0.40f)
            isSelected -> officialColor.copy(alpha = 0.90f)
            isUsable -> Color.White.copy(alpha = 0.70f)
            else -> Color.White.copy(alpha = 0.20f)
        }

    val border =
        when {
            isFocused && isUsable -> Modifier.border(BorderStroke(2.5.dp, MelodistColors.FocusTeal), MelodistShapes.ButtonCorner)
            isFocused && !isUsable -> Modifier.border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f)), MelodistShapes.ButtonCorner)
            isSelected -> Modifier.border(BorderStroke(1.5.dp, officialColor.copy(alpha = 0.85f)), MelodistShapes.ButtonCorner)
            isUsable -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)), MelodistShapes.ButtonCorner)
            else -> Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), MelodistShapes.ButtonCorner)
        }

    Box(
        modifier =
            Modifier
                .requiredWidth(110.dp)
                .height(54.dp)
                .then(border)
                .clip(MelodistShapes.ButtonCorner)
                .background(bg)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        if (isUsable) {
                            onSelect()
                        } else {
                            val tip = if (!isDeviceSupported) (unsupportedReason ?: "当前设备不支持该音质") else "当前歌曲暂无该音质音源"
                            android.widget.Toast.makeText(context, tip, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = badge,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                color = subtitleColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
