package org.melodist.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 横屏模式下 TopBar 手势折叠与展开状态持有类。
 *
 * @param isVisible 当前 TopBar 是否可见
 * @param nestedScrollConnection 捕获垂直滑动手势的嵌套滚动连接器
 */
class LandscapeCollapsibleTopBarState(
    val isVisible: Boolean,
    val nestedScrollConnection: NestedScrollConnection,
)

/**
 * 记忆横屏模式下手势折叠 TopBar 的状态与 NestedScrollConnection。
 *
 * 竖屏模式下保持常驻可见；横屏模式下：
 * - 列表向下滚动浏览时（手势上滑，available.y < -12f）隐藏顶部标题栏以提供更大的垂直内容展示空间；
 * - 列表向上回滚时（手势下滑，available.y > 12f）重新展示顶部标题栏。
 *
 * @return 构造完成的 [LandscapeCollapsibleTopBarState]
 */
@Composable
fun rememberLandscapeCollapsibleTopBarState(): LandscapeCollapsibleTopBarState {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isTopBarVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(isLandscape) {
        if (!isLandscape) {
            isTopBarVisible = true
        }
    }

    val nestedScrollConnection =
        remember(isLandscape) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (isLandscape) {
                        if (kotlin.math.abs(available.y) > kotlin.math.abs(available.x) * 1.5f) {
                            if (available.y < -12f) {
                                isTopBarVisible = false
                            } else if (available.y > 12f) {
                                isTopBarVisible = true
                            }
                        }
                    }
                    return Offset.Zero
                }
            }
        }

    return LandscapeCollapsibleTopBarState(
        isVisible = !isLandscape || isTopBarVisible,
        nestedScrollConnection = nestedScrollConnection,
    )
}

/**
 * 横屏手势折叠 TopBar 的平滑动画容器。
 *
 * 折叠时沿顶部边缘向上收缩并淡出，展开时沿顶部边缘向下滑入并淡入。
 *
 * @param visible 当前容器是否可见
 * @param modifier 修饰符
 * @param content TopBar 内容 Composable
 */
@Composable
fun LandscapeCollapsibleTopBar(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(180)),
        exit =
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(200, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(150)),
        modifier = modifier,
    ) {
        content()
    }
}

/**
 * 将横屏手势折叠连接器绑定到当前 Modifier 链。
 *
 * @param state 横屏折叠状态
 * @return 附带嵌套滚动拦截能力的 Modifier
 */
fun Modifier.landscapeNestedScroll(state: LandscapeCollapsibleTopBarState): Modifier =
    this.nestedScroll(state.nestedScrollConnection)
