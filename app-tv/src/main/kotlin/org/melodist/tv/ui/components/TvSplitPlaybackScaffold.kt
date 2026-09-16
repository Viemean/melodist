package org.melodist.tv.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

/**
 * TV 大屏统一左右分栏播放脚手架
 * 规范化布局：左侧 0.40f 封面与主控区，右侧 0.60f 动态视窗区，底部浮动控制栏
 */
@Composable
fun TvSplitPlaybackScaffold(
    modifier: Modifier = Modifier,
    surfaceColor: Color = MonetColorExtractor.DefaultSurfaceColor,
    isControlsHidden: Boolean = false,
    controlsBottomPadding: Dp = 100.dp,
    onUserInteraction: () -> Unit = {},
    leftPanel: @Composable ColumnScope.(coverSize: Dp) -> Unit,
    rightContent: @Composable BoxScope.() -> Unit,
    bottomBar: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val metrics = rememberTvWindowMetrics()

    val contentBottomPadding by animateDpAsState(
        targetValue = if (isControlsHidden) metrics.verticalSafePadding else controlsBottomPadding,
        animationSpec = tween(durationMillis = 300),
        label = "TvScaffoldBottomPadding",
    )

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(surfaceColor)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        onUserInteraction()
                    }
                    false
                },
    ) {
        // 主视窗：严格标准化的左右分栏 (0.40f vs 0.60f)
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = metrics.horizontalSafePadding,
                        end = metrics.horizontalSafePadding,
                        top = metrics.verticalSafePadding,
                        bottom = contentBottomPadding,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧面板 (0.40f)
            Column(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.40f),
                verticalArrangement = Arrangement.Center,
            ) {
                leftPanel(metrics.playerCoverSize)
            }

            Spacer(modifier = Modifier.width(32.dp))

            // 右侧面板 (0.60f)
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .weight(0.60f),
                contentAlignment = Alignment.Center,
            ) {
                rightContent()
            }
        }

        // 最底部控制栏槽位
        bottomBar()

        // 浮层/弹窗槽位
        overlay()
    }
}
