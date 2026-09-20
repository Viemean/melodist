package org.melodist.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import org.melodist.core.connect.model.GestureSwipeState
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.connect.TakeoverGestureState
import org.melodist.tv.ui.theme.MelodistColors
import kotlin.math.abs

/**
 * TV 端支持手机全面接管（TAKEOVER）手势实时跟手联动的封面走马灯组件
 */
@Composable
fun TakeoverGestureCoverCarousel(
    modifier: Modifier = Modifier,
    currentSong: Song? = null,
    coverUrl: String = "",
    elevation: Dp = 18.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val gesturePayload by TakeoverGestureState.gestureFlow.collectAsState()

    val playlist by PlaybackManager.playlist.collectAsState()
    val currentIndex by PlaybackManager.currentIndex.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()

    val actualCurrentSong = currentSong ?: PlaybackManager.currentSong.collectAsState().value
    val actualCoverUrl = coverUrl.ifBlank { actualCurrentSong?.coverUrl.orEmpty() }

    // 获取前后歌曲
    val prevSong =
        remember(playlist, currentIndex, loopMode, actualCurrentSong?.songMid) {
            PlaybackManager.getPreviousSong()
        }

    val nextSong =
        remember(playlist, currentIndex, loopMode, actualCurrentSong?.songMid) {
            PlaybackManager.getNextSong()
        }

    // 后台预加载前后歌曲封面并在缺少封面时触发 WebDAV 预提取
    LaunchedEffect(prevSong?.songMid, nextSong?.songMid, prevSong?.coverUrl, nextSong?.coverUrl) {
        val prevNeedPrefetch = prevSong != null && (prevSong.isWebDav || prevSong.songMid.startsWith("webdav_")) && prevSong.coverUrl.isBlank()
        val nextNeedPrefetch = nextSong != null && (nextSong.isWebDav || nextSong.songMid.startsWith("webdav_")) && nextSong.coverUrl.isBlank()
        if (prevNeedPrefetch || nextNeedPrefetch) {
            PlaybackManager.prefetchAdjacentWebDavCovers()
        }

        val imageLoader = SingletonImageLoader.get(context)
        prevSong?.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val req = ImageRequest.Builder(context).data(url).build()
            imageLoader.enqueue(req)
        }
        nextSong?.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
            val req = ImageRequest.Builder(context).data(url).build()
            imageLoader.enqueue(req)
        }
    }

    val animFraction = remember { Animatable(0f) }

    // 歌曲切歌完成后重置动画偏移
    LaunchedEffect(actualCurrentSong?.songMid) {
        animFraction.snapTo(0f)
    }

    // 响应手势流驱动
    LaunchedEffect(gesturePayload) {
        when (gesturePayload.state) {
            GestureSwipeState.DRAGGING -> {
                animFraction.snapTo(gesturePayload.fraction)
                // 超时看门狗：若 400ms 内未收到后续手势帧或结算指令，自动平滑弹回原位
                delay(400L)
                if (animFraction.value != 0f) {
                    animFraction.animateTo(
                        targetValue = 0f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                    )
                    TakeoverGestureState.reset()
                }
            }
            GestureSwipeState.SETTLING -> {
                val target = gesturePayload.targetFraction
                val dur = gesturePayload.durationMs.toInt().coerceIn(120, 350)
                animFraction.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = dur, easing = LinearEasing),
                )
                // 若结算后 600ms 歌曲未发生切换，自动复位弹回
                delay(600L)
                if (animFraction.value != 0f) {
                    animFraction.animateTo(
                        targetValue = 0f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                    )
                    TakeoverGestureState.reset()
                }
            }
            GestureSwipeState.CANCEL -> {
                animFraction.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                )
            }
            GestureSwipeState.IDLE -> {
                if (animFraction.value != 0f) {
                    animFraction.snapTo(0f)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)

        if (actualCoverUrl.isEmpty() && actualCurrentSong == null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(MelodistColors.ContainerDark),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Melodist 4K",
                    color = MelodistColors.TextMuted,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            // 下一首卡片 (fraction < 0 时向左推入)
            if (nextSong != null) {
                MelodistElevatedCover(
                    coverUrl = nextSong.coverUrl,
                    albumMid = nextSong.albumMid,
                    visualMid = nextSong.visualMid,
                    songMid = nextSong.songMid,
                    artistMid =
                        nextSong.singerList
                            .firstOrNull()
                            ?.mid
                            .orEmpty(),
                    shape = shape,
                    elevation = elevation,
                    preferRawCover = true,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val f = animFraction.value
                                if (f < -0.001f) {
                                    val absFrac = -f
                                    translationX = (1f + f) * widthPx
                                    scaleX = (0.88f + 0.12f * absFrac).coerceIn(0.88f, 1f)
                                    scaleY = scaleX
                                    alpha = (0.60f + 0.40f * absFrac).coerceIn(0.60f, 1f)
                                } else {
                                    alpha = 0f
                                    translationX = widthPx * 2
                                }
                            },
                )
            }

            // 上一首卡片 (fraction > 0 时向右推入)
            if (prevSong != null) {
                MelodistElevatedCover(
                    coverUrl = prevSong.coverUrl,
                    albumMid = prevSong.albumMid,
                    visualMid = prevSong.visualMid,
                    songMid = prevSong.songMid,
                    artistMid =
                        prevSong.singerList
                            .firstOrNull()
                            ?.mid
                            .orEmpty(),
                    shape = shape,
                    elevation = elevation,
                    preferRawCover = true,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val f = animFraction.value
                                if (f > 0.001f) {
                                    translationX = (-1f + f) * widthPx
                                    scaleX = (0.88f + 0.12f * f).coerceIn(0.88f, 1f)
                                    scaleY = scaleX
                                    alpha = (0.60f + 0.40f * f).coerceIn(0.60f, 1f)
                                } else {
                                    alpha = 0f
                                    translationX = -widthPx * 2
                                }
                            },
                )
            }

            // 当前歌曲卡片
            MelodistElevatedCover(
                coverUrl = actualCoverUrl,
                albumMid = actualCurrentSong?.albumMid.orEmpty(),
                visualMid = actualCurrentSong?.visualMid.orEmpty(),
                songMid = actualCurrentSong?.songMid.orEmpty(),
                artistMid =
                    actualCurrentSong
                        ?.singerList
                        ?.firstOrNull()
                        ?.mid
                        .orEmpty(),
                shape = shape,
                elevation = elevation,
                preferRawCover = true,
                onClick = onClick,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val f = animFraction.value
                            val absF = abs(f)
                            translationX = f * widthPx
                            scaleX = (1f - 0.12f * absF).coerceIn(0.88f, 1f)
                            scaleY = scaleX
                            alpha = (1f - 0.35f * absF).coerceIn(0.65f, 1f)
                        },
            )
        }
    }
}
