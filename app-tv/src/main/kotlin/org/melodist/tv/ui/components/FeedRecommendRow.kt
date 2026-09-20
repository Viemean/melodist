package org.melodist.tv.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.model.Song
import org.melodist.tv.ui.theme.MelodistColors

@Composable
fun FeedRecommendRow(
    cardWidth: Dp = 260.dp,
    rowFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    cardRequesters: List<FocusRequester> = remember { List(6) { FocusRequester() } },
    initialFocusedIndex: Int = 0,
    onCardFocused: ((Int) -> Unit)? = null,
    onPlaySong: (List<Song>, Int) -> Unit = { _, _ -> },
) {
    val userProfile by UserSession.profileFlow.collectAsState()
    val apiService = remember { MusicApiService() }

    val shelves by org.melodist.data.RecommendFeedManager.shelvesFlow
        .collectAsState()
    val currentShelf =
        remember(shelves) {
            val targetShelf = shelves.firstOrNull()
            if (targetShelf != null && targetShelf.songs.isNotEmpty()) {
                targetShelf.copy(songs = targetShelf.songs.take(36))
            } else {
                null
            }
        }

    LaunchedEffect(userProfile) {
        if (UserSession.isLoggedIn) {
            org.melodist.data.RecommendFeedManager
                .refresh(apiService, forceRefresh = false)
        }
    }
    val songs = currentShelf?.songs.orEmpty()
    if (!UserSession.isLoggedIn || songs.isEmpty()) {
        return
    }

    // 轮换槽位控制：0..5（每轮 6 张卡片，6 轮完整轮播 36 首）
    var rotationIndex by remember { mutableIntStateOf(0) }

    // 15 秒固定轮换定时器：持续平滑更新
    LaunchedEffect(songs.size) {
        if (songs.size < 6) return@LaunchedEffect
        while (isActive) {
            delay(15_000L)
            rotationIndex = (rotationIndex + 1) % 6
        }
    }

    var focusedCardIndex by remember { mutableIntStateOf(initialFocusedIndex) }
    val scrollState = rememberScrollState()

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

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(start = 14.dp, end = 32.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            (0 until 6).forEach { slotIndex ->
                // 计算当前卡片展示的全局歌曲索引
                val songGlobalIndex = ((rotationIndex * 6 + slotIndex) % songs.size).coerceIn(0, songs.size - 1)
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

                val itemCardRequester = cardRequesters.getOrElse(slotIndex) { FocusRequester() }

                RotatingTrackCard(
                    item = item,
                    cardWidth = cardWidth,
                    slotIndex = slotIndex,
                    showPlayButton = true,
                    modifier =
                        Modifier
                            .focusRequester(itemCardRequester)
                            .then(
                                if (slotIndex == focusedCardIndex && rowFocusRequester != null) {
                                    Modifier.focusRequester(rowFocusRequester)
                                } else {
                                    Modifier
                                },
                            ).focusProperties {
                                if (upFocusRequester != null) {
                                    up = upFocusRequester
                                }
                                left = if (slotIndex > 0) cardRequesters[slotIndex - 1] else cardRequesters.last()
                                right = if (slotIndex < 5) cardRequesters[slotIndex + 1] else cardRequesters.first()
                            },
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedCardIndex = slotIndex
                            onCardFocused?.invoke(slotIndex)
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
