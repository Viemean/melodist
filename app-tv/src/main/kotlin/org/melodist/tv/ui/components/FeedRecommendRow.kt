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

    // 15 秒固定轮换定时器：持续平滑更新
    LaunchedEffect(songs.size) {
        if (songs.size < 5) return@LaunchedEffect
        while (isActive) {
            delay(15_000L)
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

                val item =
                    RotatingCardItem(
                        id = "feed_${song.songId}_$songGlobalIndex",
                        title = song.name.ifBlank { "未知单曲" },
                        subtitle =
                            when {
                                song.singer.isNotBlank() && song.album.isNotBlank() -> "${song.singer} · ${song.album}"
                                song.singer.isNotBlank() -> song.singer
                                song.album.isNotBlank() -> song.album
                                else -> "精选推荐"
                            },
                        coverUrl = song.coverUrl,
                        albumMid = song.albumMid,
                        songMid = song.songMid,
                    )

                RotatingTrackCard(
                    item = item,
                    cardWidth = cardWidth,
                    slotIndex = slotIndex,
                    showPlayButton = true,
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
