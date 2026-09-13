package org.melodist.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 封面展示组件
 * 还原纯粹的 12 寸实体印刷封套质感，拒绝露底生硬黑块
 */
@Composable
fun MelodistElevatedCover(
    coverUrl: String,
    modifier: Modifier = Modifier,
    albumMid: String = "",
    visualMid: String = "",
    songMid: String = "",
    artistMid: String = "",
    contentDescription: String? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    isCircle: Boolean = false,
    elevation: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
) {
    val actualShape = if (isCircle) CircleShape else shape

    val interactionSource = remember { MutableInteractionSource() }
    val clickableModifier =
        if (onClick != null) {
            Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onClick() }
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .then(clickableModifier)
                .shadow(
                    elevation = elevation,
                    shape = actualShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                ).clip(actualShape)
                .background(Color(0xFF141414))
                .border(
                    BorderStroke(0.75.dp, Color.White.copy(alpha = 0.12f)),
                    actualShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        MelodistAsyncImage(
            coverUrl = coverUrl,
            albumMid = albumMid,
            visualMid = visualMid,
            songMid = songMid,
            artistMid = artistMid,
            contentDescription = contentDescription,
            shape = actualShape,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
