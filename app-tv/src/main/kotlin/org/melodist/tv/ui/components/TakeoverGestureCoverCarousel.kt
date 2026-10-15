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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    shape: Shape = RoundedCornerShape(8.dp),
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

    var centerSlotIndex by remember { mutableIntStateOf(0) }
    var slotSongs by remember {
        mutableStateOf(listOf(actualCurrentSong, nextSong, prevSong))
    }
    var pendingTargetSongMid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(actualCurrentSong?.songMid, prevSong?.songMid, nextSong?.songMid, centerSlotIndex) {
        val target = pendingTargetSongMid
        if (target != null) {
            if (actualCurrentSong?.songMid == target) {
                pendingTargetSongMid = null
                val prevSlot = (centerSlotIndex - 1).floorMod(3)
                val nextSlot = (centerSlotIndex + 1).floorMod(3)
                val updated = slotSongs.toMutableList()
                updated[centerSlotIndex] = actualCurrentSong
                updated[nextSlot] = nextSong
                updated[prevSlot] = prevSong
                slotSongs = updated
            }
        } else {
            val prevSlot = (centerSlotIndex - 1).floorMod(3)
            val nextSlot = (centerSlotIndex + 1).floorMod(3)
            val updated = slotSongs.toMutableList()
            updated[centerSlotIndex] = actualCurrentSong
            updated[nextSlot] = nextSong
            updated[prevSlot] = prevSong
            slotSongs = updated
        }
    }

    // 歌曲切歌完成后重置动画偏移
    LaunchedEffect(actualCurrentSong?.songMid) {
        if (animFraction.value != 0f) {
            animFraction.snapTo(0f)
        }
    }

    // 响应手势流驱动
    LaunchedEffect(gesturePayload) {
        when (gesturePayload.state) {
            GestureSwipeState.DRAGGING -> {
                animFraction.snapTo(gesturePayload.fraction)
                // 超时看门狗：400ms 内未收到后续手势帧或指令则复位
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
                if (target <= -0.9f) {
                    val targetSong = nextSong
                    pendingTargetSongMid = targetSong?.songMid
                    centerSlotIndex = (centerSlotIndex + 1).floorMod(3)
                    animFraction.snapTo(0f)
                } else if (target >= 0.9f) {
                    val targetSong = prevSong
                    pendingTargetSongMid = targetSong?.songMid
                    centerSlotIndex = (centerSlotIndex - 1).floorMod(3)
                    animFraction.snapTo(0f)
                }
                TakeoverGestureState.reset()
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
            val prevSlot = (centerSlotIndex - 1).floorMod(3)
            val nextSlot = (centerSlotIndex + 1).floorMod(3)
            val renderOrder = listOf(prevSlot, nextSlot, centerSlotIndex)

            for (slotIndex in renderOrder) {
                key(slotIndex) {
                    val relativeStep =
                        when (slotIndex) {
                            centerSlotIndex -> 0
                            nextSlot -> 1
                            else -> -1
                        }

                    val slotSong = slotSongs.getOrNull(slotIndex)

                    if (slotSong != null) {
                        val f = animFraction.value
                        val isCenter = relativeStep == 0
                        val isNext = relativeStep == 1

                        val cardTranslationX =
                            when {
                                isCenter -> f * widthPx
                                isNext -> (1f + f) * widthPx
                                else -> (-1f + f) * widthPx
                            }

                        val cardScale =
                            when {
                                isCenter -> {
                                    val absF = abs(f)
                                    (1f - 0.12f * absF).coerceIn(0.88f, 1f)
                                }

                                isNext -> {
                                    if (f < -0.001f) {
                                        val absFrac = -f
                                        (0.88f + 0.12f * absFrac).coerceIn(0.88f, 1f)
                                    } else {
                                        0.88f
                                    }
                                }

                                else -> {
                                    if (f > 0.001f) {
                                        (0.88f + 0.12f * f).coerceIn(0.88f, 1f)
                                    } else {
                                        0.88f
                                    }
                                }
                            }

                        val cardAlpha =
                            when {
                                isCenter -> {
                                    val absF = abs(f)
                                    (1f - 0.35f * absF).coerceIn(0.65f, 1f)
                                }

                                isNext -> {
                                    if (f < -0.001f) {
                                        val absFrac = -f
                                        (0.60f + 0.40f * absFrac).coerceIn(0.60f, 1f)
                                    } else {
                                        0f
                                    }
                                }

                                else -> {
                                    if (f > 0.001f) {
                                        (0.60f + 0.40f * f).coerceIn(0.60f, 1f)
                                    } else {
                                        0f
                                    }
                                }
                            }

                        MelodistElevatedCover(
                            coverUrl = slotSong.coverUrl,
                            albumMid = slotSong.albumMid,
                            visualMid = slotSong.visualMid,
                            songMid = slotSong.songMid,
                            artistMid =
                                slotSong.singerList
                                    .firstOrNull()
                                    ?.mid
                                    .orEmpty(),
                            shape = shape,
                            elevation = elevation,
                            preferRawCover = true,
                            onClick = if (isCenter) onClick else null,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = cardTranslationX
                                        scaleX = cardScale
                                        scaleY = cardScale
                                        alpha = cardAlpha
                                    },
                        )
                    }
                }
            }
        }
    }
}

private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
