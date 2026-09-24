package org.melodist.mobile.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.model.AudioQualityTier

@Composable
fun QualityTierBadge(
    tier: AudioQualityTier,
    modifier: Modifier = Modifier,
) {
    val (label, bg, fg) =
        when (tier) {
            AudioQualityTier.HiRes -> Triple("Hi-Res", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            AudioQualityTier.Master -> Triple("Master", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
            AudioQualityTier.SQ -> Triple("SQ", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            AudioQualityTier.HQ -> Triple("HQ", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            AudioQualityTier.Atmos -> Triple("全景声", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            AudioQualityTier.Dolby -> Triple("杜比", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            AudioQualityTier.Premium -> Triple("臻品", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
            AudioQualityTier.Standard -> Triple("标准", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }

    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
