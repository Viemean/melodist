package org.melodist.tv.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getRecommendFeed
import org.melodist.model.RecommendShelf
import org.melodist.model.Song
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.MonetColorExtractor

object FeedRecommendCache {
    var cachedShelf: RecommendShelf? = null
    var lastFetchTimeMs: Long = 0L
    const val TTL_MS = 10 * 60 * 1000L // 10 分钟自动换一批

    fun isValid(): Boolean =
        cachedShelf != null &&
            (System.currentTimeMillis() - lastFetchTimeMs < TTL_MS) &&
            cachedShelf!!.songs.isNotEmpty()
}

@Composable
fun FeedRecommendRow(
    cardWidth: Dp = 260.dp,
    rowFocusRequester: FocusRequester? = null,
    onPlaySong: (List<Song>, Int) -> Unit = { _, _ -> },
) {
    val userProfile by UserSession.profileFlow.collectAsState()
    val apiService = remember { MusicApiService() }

    var shelf by remember { mutableStateOf<RecommendShelf?>(FeedRecommendCache.cachedShelf) }
    var isLoading by remember { mutableStateOf(false) }

    // 拉取推荐货架数据（首次与每10分钟换批）
    suspend fun fetchFeedShelf(force: Boolean = false) {
        if (!UserSession.isLoggedIn) {
            shelf = null
            FeedRecommendCache.cachedShelf = null
            FeedRecommendCache.lastFetchTimeMs = 0L
            return
        }

        if (!force && FeedRecommendCache.isValid()) {
            shelf = FeedRecommendCache.cachedShelf
            return
        }

        isLoading = true
        try {
            val shelves = apiService.getRecommendFeed()
            val targetShelf = shelves.firstOrNull()
            if (targetShelf != null && targetShelf.songs.isNotEmpty()) {
                val validSongs = targetShelf.songs.take(35)
                val trimmedShelf = targetShelf.copy(songs = validSongs)
                shelf = trimmedShelf
                FeedRecommendCache.cachedShelf = trimmedShelf
                FeedRecommendCache.lastFetchTimeMs = System.currentTimeMillis()
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(userProfile) {
        fetchFeedShelf()
    }

    // 10 分钟定时换批循环
    LaunchedEffect(userProfile) {
        if (!UserSession.isLoggedIn) return@LaunchedEffect
        while (isActive) {
            val now = System.currentTimeMillis()
            val elapsed = now - FeedRecommendCache.lastFetchTimeMs
            if (FeedRecommendCache.lastFetchTimeMs > 0L && elapsed >= FeedRecommendCache.TTL_MS) {
                fetchFeedShelf(force = true)
            }
            delay(15_000L)
        }
    }

    val currentShelf = shelf
    val songs = currentShelf?.songs.orEmpty()
    if (!UserSession.isLoggedIn || songs.isEmpty()) {
        return
    }

    // 轮换槽位控制：0..6（每卡片分配 7 首歌）
    var rotationIndex by remember { mutableIntStateOf(0) }

    // 10 秒固定轮换定时器：持续平滑更新
    LaunchedEffect(songs.size) {
        if (songs.size < 5) return@LaunchedEffect
        while (isActive) {
            delay(10_000L)
            rotationIndex = (rotationIndex + 1) % 7
        }
    }

    var focusedCardIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = currentShelf?.title.orEmpty().ifBlank { "专属推荐" },
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MelodistColors.TextPrimary,
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 32.dp, top = 14.dp, bottom = 14.dp),
        ) {
            items(5) { slotIndex ->
                // 计算当前卡片展示的全局歌曲索引
                val songGlobalIndex = ((rotationIndex * 5 + slotIndex) % songs.size).coerceIn(0, songs.size - 1)
                val song = songs[songGlobalIndex]

                FeedTrackCard(
                    slotIndex = slotIndex,
                    song = song,
                    cardWidth = cardWidth,
                    modifier =
                        if (slotIndex == focusedCardIndex && rowFocusRequester != null) {
                            Modifier.focusRequester(rowFocusRequester)
                        } else {
                            Modifier
                        },
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedCardIndex = slotIndex
                        }
                    },
                    onClick = {
                        onPlaySong(songs, songGlobalIndex)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FeedTrackCard(
    slotIndex: Int,
    song: Song,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val cardHeight = cardWidth * 1.22f

    // 单图 Monet 取色
    var dynamicBgColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(song.coverUrl) {
        if (song.coverUrl.isNotBlank()) {
            dynamicBgColor = MonetColorExtractor.extractFromUrl(song.coverUrl)
        }
    }

    val currentBgColor = dynamicBgColor ?: Color(0xFF1A2234)
    val animatedBgColor by animateColorAsState(
        targetValue = currentBgColor,
        animationSpec = tween(600),
        label = "FeedCardBgColor",
    )

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(cardWidth)
                .height(cardHeight)
                .onFocusChanged { state ->
                    onFocusChanged(state.isFocused)
                },
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.CardCorner,
                focusedShape = MelodistShapes.CardCorner,
            ),
        colors =
            CardDefaults.colors(
                containerColor = animatedBgColor.copy(alpha = 0.90f),
                focusedContainerColor = animatedBgColor,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        shape = MelodistShapes.CardCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(3.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.CardCorner,
                    ),
            ),
        scale =
            CardDefaults.scale(
                focusedScale = 1.05f,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(animatedBgColor),
        ) {
            // 上半部：专辑封面（缓缓滑出与缓缓滑进，持续 900ms）
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(cardHeight * 0.65f)
                        .padding(top = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = song,
                    transitionSpec = {
                        (slideInHorizontally(
                            animationSpec = tween(durationMillis = 900, delayMillis = slotIndex * 100, easing = FastOutSlowInEasing),
                            initialOffsetX = { fullWidth -> (fullWidth * 0.45f).toInt() },
                        ) + fadeIn(
                            animationSpec = tween(durationMillis = 800, delayMillis = slotIndex * 100),
                        )) togetherWith (slideOutHorizontally(
                            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                            targetOffsetX = { fullWidth -> -(fullWidth * 0.45f).toInt() },
                        ) + fadeOut(
                            animationSpec = tween(durationMillis = 700),
                        ))
                    },
                    label = "FeedCoverTransition",
                ) { currentSong ->
                    val singleSize = (cardWidth * 0.64f).coerceIn(130.dp, 175.dp)
                    if (currentSong.coverUrl.isNotBlank() || currentSong.albumMid.isNotBlank()) {
                        MelodistElevatedCover(
                            coverUrl = currentSong.coverUrl,
                            albumMid = currentSong.albumMid,
                            songMid = currentSong.songMid,
                            contentDescription = null,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.size(singleSize),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size((cardWidth * 0.50f).coerceIn(100.dp, 130.dp))
                                    .clip(MelodistShapes.CardCorner)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), MelodistShapes.CardCorner),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = currentSong.name.take(2).ifBlank { "推荐" },
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // 下半部：歌曲与歌手信息 + 悬浮播放圆形小徽章
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Color(0xFF1A2234).copy(alpha = 0.50f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 左侧：大字歌曲名 + 小字歌手与专辑名（与封面同步缓缓切入）
                    AnimatedContent(
                        targetState = song,
                        transitionSpec = {
                            (slideInHorizontally(
                                animationSpec = tween(durationMillis = 850, delayMillis = slotIndex * 100, easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> (fullWidth * 0.35f).toInt() },
                            ) + fadeIn(
                                animationSpec = tween(durationMillis = 750, delayMillis = slotIndex * 100),
                            )) togetherWith (slideOutHorizontally(
                                animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
                                targetOffsetX = { fullWidth -> -(fullWidth * 0.35f).toInt() },
                            ) + fadeOut(
                                animationSpec = tween(durationMillis = 650),
                            ))
                        },
                        modifier = Modifier.weight(1f),
                        label = "FeedTextTransition",
                    ) { targetSong ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = targetSong.name.ifBlank { "未知单曲" },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            val subtitleText =
                                when {
                                    targetSong.singer.isNotBlank() && targetSong.album.isNotBlank() ->
                                        "${targetSong.singer} · ${targetSong.album}"
                                    targetSong.singer.isNotBlank() -> targetSong.singer
                                    targetSong.album.isNotBlank() -> targetSong.album
                                    else -> "精选推荐"
                                }

                            Text(
                                text = subtitleText,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // 右侧：播放小图标圆钮
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFB4F8FF))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "播放",
                            tint = Color(0xFF102A2D),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
