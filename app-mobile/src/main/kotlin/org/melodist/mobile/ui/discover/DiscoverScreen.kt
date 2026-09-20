package org.melodist.mobile.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.data.DailyRecommendCacheManager
import org.melodist.data.GuessRecommendManager
import org.melodist.data.MillionRecommendManager
import org.melodist.data.RecommendFeedManager
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.playback.PlaybackManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    contentPadding: PaddingValues,
    onRequireLogin: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val apiService = remember { MusicApiService() }
    val userProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn

    val recommendData by DailyRecommendCacheManager.recommendFlow.collectAsState()
    val isDailyLoading by DailyRecommendCacheManager.isLoadingFlow.collectAsState()
    val shelves by RecommendFeedManager.shelvesFlow.collectAsState()
    val songs = recommendData.songs
    val officialDesc = recommendData.description

    var isRefreshing by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    val onRefresh: () -> Unit = {
        refreshJob?.cancel()
        isRefreshing = true
        refreshJob =
            scope.launch {
                try {
                    DailyRecommendCacheManager.loadRecommendSongs(apiService, forceRefresh = true)
                    GuessRecommendManager.refresh(apiService, forceRefresh = true)
                    RecommendFeedManager.refresh(apiService, forceRefresh = true)
                    MillionRecommendManager.refresh(apiService, forceRefresh = true)
                } finally {
                    isRefreshing = false
                }
            }
    }

    LaunchedEffect(userProfile.uin, isLoggedIn) {
        DailyRecommendCacheManager.loadRecommendSongs(apiService, forceRefresh = false)
        RecommendFeedManager.refresh(apiService, forceRefresh = false)
        MillionRecommendManager.refresh(apiService, forceRefresh = false)
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (isDailyLoading && songs.isEmpty() && shelves.isEmpty() && !isRefreshing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            CommonSongList(
                songs = songs,
                contentPadding =
                    PaddingValues(
                        top = 2.dp,
                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    ),
                headerItems = {
                    item(key = "discover_hero_carousel") {
                        val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                        val cardWidth = (screenWidth - 44.dp).coerceIn(280.dp, 360.dp)

                        androidx.compose.foundation.lazy.LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "hero_guess_card") {
                                GuessRecommendCard(
                                    onOpenPlayer = onOpenPlayer,
                                    modifier = Modifier.width(cardWidth),
                                )
                            }

                            item(key = "hero_million_card") {
                                MillionRecommendCard(
                                    onRequireLogin = onRequireLogin,
                                    onOpenPlayer = onOpenPlayer,
                                    modifier = Modifier.width(cardWidth),
                                )
                            }
                        }
                    }

                    if (shelves.isNotEmpty()) {
                        item(key = "discover_recommend_shelves") {
                            RecommendShelfSection(
                                shelves = shelves,
                                onSongClick = { song, shelfSongs ->
                                    val index =
                                        shelfSongs
                                            .indexOfFirst {
                                                (it.songId > 0 && it.songId == song.songId) ||
                                                    (it.songMid.isNotBlank() && it.songMid == song.songMid)
                                            }.coerceAtLeast(0)
                                    PlaybackManager.setPlaylist(
                                        songs = shelfSongs,
                                        startIndex = index,
                                    )
                                    onOpenPlayer()
                                },
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                    }

                    if (officialDesc.isNotBlank() || isLoggedIn) {
                        item(key = "discover_header_desc") {
                            Text(
                                text = officialDesc.ifBlank { "每日 06:00 自动更新专属推荐" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (!isLoggedIn) {
                        item(key = "discover_login_prompt") {
                            ElevatedCard(
                                onClick = onRequireLogin,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "扫码登录解锁专属推荐与收藏",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = "同步微信 / QQ 音乐资产与高品质歌曲",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalButton(onClick = onRequireLogin) {
                                        Text("去登录")
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}
