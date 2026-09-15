package org.melodist.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * 统一风格的专辑封面展示组件。
 * 具备精致圆角、微光立体描边与柔和落地阴影；
 * 支持多清晰度候选列表 (candidates) 与自动降级重试；
 * 显式配置 FilterQuality.Medium 抗锯齿滤波，避免图像下采样时产生摩尔纹与模糊失真；
 * 默认关闭 crossfade 避免列表滑动时并发动画引起 Choreographer 丢帧，仅当 elevation > 0 时添加动态阴影。
 */
@Composable
fun AlbumArtImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    candidates: List<String>? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    elevation: Dp = 8.dp,
    border: BorderStroke? = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    placeholderIconSize: Dp = 28.dp,
    crossfade: Boolean = false,
    spotColor: Color = Color.Black.copy(alpha = 0.45f),
    ambientColor: Color = Color.Black.copy(alpha = 0.25f),
    placeholderContent: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current

    val candidateList =
        remember(coverUrl, candidates) {
            if (!candidates.isNullOrEmpty()) {
                candidates
            } else if (!coverUrl.isNullOrBlank()) {
                listOf(coverUrl)
            } else {
                emptyList()
            }
        }

    var candidateIndex by remember(candidateList) { mutableIntStateOf(0) }
    val effectiveUrl = candidateList.getOrNull(candidateIndex)

    val surfaceModifier =
        if (elevation > 0.dp) {
            modifier.shadow(
                elevation = elevation,
                shape = shape,
                spotColor = spotColor,
                ambientColor = ambientColor,
            )
        } else {
            modifier
        }

    Surface(
        modifier = surfaceModifier,
        shape = shape,
        border = border,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (effectiveUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (placeholderContent != null) {
                    placeholderContent()
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(placeholderIconSize),
                    )
                }
            }
        } else {
            val imageRequest =
                remember(effectiveUrl, crossfade) {
                    ImageRequest
                        .Builder(context)
                        .data(effectiveUrl)
                        .crossfade(crossfade)
                        .precision(coil3.size.Precision.INEXACT)
                        .build()
                }

            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                onError = {
                    if (candidateIndex + 1 < candidateList.size) {
                        candidateIndex++
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
