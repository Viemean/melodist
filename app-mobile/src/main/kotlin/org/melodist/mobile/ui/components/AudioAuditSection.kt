package org.melodist.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.model.AudioQualityTier
import org.melodist.playback.AudioAuditResult
import org.melodist.playback.AudioQualityVerdict

val AmberWarningText = Color(0xFFE5A93B)
val GreenSuccessText = Color(0xFF66BB6A)
val BlueInfoText = Color(0xFF64B5F6)

@Composable
fun AudioAuditCard(
    auditResult: AudioAuditResult?,
    isAuditing: Boolean,
    currentTier: AudioQualityTier,
    onRefreshAudit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLossyTier = currentTier == AudioQualityTier.Standard || currentTier == AudioQualityTier.HQ

    val (accentColor, badgeIcon, badgeTitle) =
        when {
            isAuditing -> {
                Triple(
                    MaterialTheme.colorScheme.primary,
                    Icons.Rounded.GraphicEq,
                    "正在分析音频频谱...",
                )
            }
            auditResult?.verdict == AudioQualityVerdict.FAKE_LOSSLESS || auditResult?.verdict == AudioQualityVerdict.UPSAMPLED_HIRES -> {
                Triple(
                    AmberWarningText,
                    Icons.Rounded.WarningAmber,
                    if (auditResult.verdict == AudioQualityVerdict.UPSAMPLED_HIRES) "疑似升频" else "疑似假无损",
                )
            }
            auditResult?.verdict == AudioQualityVerdict.AUTHENTIC -> {
                Triple(
                    GreenSuccessText,
                    Icons.Rounded.CheckCircle,
                    auditResult.description.takeIf { it.isNotBlank() }
                        ?: (if (currentTier == AudioQualityTier.HiRes || currentTier == AudioQualityTier.Master) "真高解析度" else "真无损"),
                )
            }
            auditResult?.verdict == AudioQualityVerdict.BANDWIDTH_LIMITED -> {
                Triple(
                    BlueInfoText,
                    Icons.Rounded.Info,
                    "频宽受限",
                )
            }
            isLossyTier || auditResult?.verdict == AudioQualityVerdict.LOSSY -> {
                Triple(
                    BlueInfoText,
                    Icons.Rounded.Info,
                    "标准有损",
                )
            }
            else -> {
                Triple(
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Icons.Rounded.Info,
                    "未检测",
                )
            }
        }

    val cardBg = accentColor.copy(alpha = 0.12f)
    val cardBorder = accentColor.copy(alpha = 0.35f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isAuditing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = accentColor,
                            )
                        } else {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = badgeTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                    )
                }

                if (!isLossyTier) {
                    IconButton(
                        onClick = onRefreshAudit,
                        modifier = Modifier.size(28.dp),
                        enabled = !isAuditing,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "重新检测",
                            tint = accentColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val descText =
                when {
                    isAuditing -> "正在计算音频切片频谱分布与高频滚降特征..."
                    auditResult != null -> auditResult.details.ifBlank { auditResult.description }
                    isLossyTier -> "当前为标准有损编码（MP3 / AAC），高频截断属于正常声学压缩。"
                    else -> "点击右侧刷新按钮以对当前音频流执行频谱检测。"
                }

            Text(
                text = descText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
            )

            if (auditResult?.cutoffFrequencyHz != null && auditResult.cutoffFrequencyHz > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "有效频宽: ${"%.1f".format(auditResult.cutoffFrequencyHz / 1000.0)} kHz",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
