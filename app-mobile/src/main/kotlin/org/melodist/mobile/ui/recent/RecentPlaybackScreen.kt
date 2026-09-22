package org.melodist.mobile.ui.recent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.melodist.api.RecentAlbumItem
import org.melodist.api.RecentPlaylistItem
import org.melodist.api.UserSession
import org.melodist.data.AppLifecycleManager
import org.melodist.data.RecentPlaybackManager
import org.melodist.model.RotatingCandidatePool
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.mobile.ui.discover.HeroRecommendCard
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.model.Playlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentPlaybackScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val navigation = LocalAppNavigation.current
    val recentSongs by RecentPlaybackManager.recentSongsFlow.collectAsState()
    val recentAlbums by RecentPlaybackManager.recentAlbumsFlow.collectAsState()
    val recentPlaylists by RecentPlaybackManager.recentPlaylistsFlow.collectAsState()
    val isSyncing by RecentPlaybackManager.isSyncingFlow.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (UserSession.isLoggedIn) {
            RecentPlaybackManager.syncFromCloud()
        }
    }

    PullToRefreshBox(
        isRefreshing = isSyncing,
        onRefresh = { RecentPlaybackManager.syncFromCloud(force = true) },
        modifier = modifier.fillMaxSize(),
    ) {
        CommonSongList(
            songs = recentSongs,
            deleteType = SongListDeleteType.RecentHistory,
            showLocalBadge = true,
            showWebDavBadge = true,
            onDeleteSelected = { songs -> RecentPlaybackManager.removeSongs(songs) },
            onDeleteLocalFile = { song -> RecentPlaybackManager.removeSong(song.songMid) },
            contentPadding = PaddingValues(
                top = 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            headerItems = {
                // 特色卡片横向并排区域（“最近专辑”与“最近歌单”）
                item(key = "recent_hero_cards_row") {
                    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                    val cardWidth = (screenWidth - 44.dp).coerceIn(280.dp, 360.dp)

                    val filteredPlaylists = remember(recentPlaylists) {
                        recentPlaylists.filterNot { item ->
                            val t = item.title
                            t.contains("30首") || t.contains("每日30") || t.contains("红心雷达") || t.contains("猜你喜欢")
                        }
                    }

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 卡片 1: 最近播放的专辑特色卡片（5 最新 + 25 随机，15 秒无序轮播）
                        item(key = "recent_hero_album_card") {
                            RecentAlbumsHeroCard(
                                recentAlbums = recentAlbums,
                                cardWidth = cardWidth,
                                onCardClick = { navigation.navigateToRecentAlbums() },
                            )
                        }

                        // 卡片 2: 最近播放的歌单特色卡片（5 最新 + 25 随机，15 秒无序轮播，错峰 4 秒）
                        item(key = "recent_hero_playlist_card") {
                            RecentPlaylistsHeroCard(
                                playlists = filteredPlaylists,
                                cardWidth = cardWidth,
                                onCardClick = { navigation.navigateToRecentPlaylists() },
                            )
                        }
                    }
                }

                // 标题与控制栏
                item(key = "recent_section_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "最近播放的单曲",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "共 ${recentSongs.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (recentSongs.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                TextButton(
                                    onClick = { showClearDialog = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "清空历史",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = "清空",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            emptyContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无最近播放单曲",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "播放曲目累计满 5 秒后将自动同步沉淀在此处",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空最近播放") },
            text = { Text("确认要清空最近播放单曲记录吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        RecentPlaybackManager.clear()
                        showClearDialog = false
                    },
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

private const val ROTATION_INTERVAL_MS = 20_000L
private const val PLAYLIST_PHASE_OFFSET_MS = 10_000L

@Composable
private fun RecentAlbumsHeroCard(
    recentAlbums: List<RecentAlbumItem>,
    cardWidth: Dp,
    onCardClick: () -> Unit,
) {
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    // 5 最新 + 25 随机构建候选池
    val candidatePool = remember(recentAlbums) {
        RotatingCandidatePool.buildCandidatePool(recentAlbums, fixedCount = 5, randomCount = 25)
    }

    var shuffledList by remember { mutableStateOf<List<RecentAlbumItem>>(emptyList()) }
    var currentDisplayIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(candidatePool) {
        if (candidatePool.isNotEmpty()) {
            shuffledList = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = shuffledList.lastOrNull())
            currentDisplayIndex = 0
        } else {
            shuffledList = emptyList()
            currentDisplayIndex = 0
        }
    }

    // 15 秒无序轮播定时器（前台时执行，相位 0s）
    LaunchedEffect(shuffledList, isForeground) {
        if (!isForeground || shuffledList.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(ROTATION_INTERVAL_MS)
            val nextIndex = currentDisplayIndex + 1
            if (nextIndex < shuffledList.size) {
                currentDisplayIndex = nextIndex
            } else {
                shuffledList = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = shuffledList.lastOrNull())
                currentDisplayIndex = 0
            }
        }
    }

    val activeAlbum = shuffledList.getOrNull(currentDisplayIndex) ?: recentAlbums.firstOrNull()

    HeroRecommendCard(
        badgeText = "最近专辑",
        subtitleText = if (recentAlbums.isNotEmpty()) "共 ${recentAlbums.size} 张 · 20 秒无序轮播" else "暂无专辑",
        title = activeAlbum?.albumName ?: "最近播放的专辑",
        caption =
            if (activeAlbum != null) {
                buildString {
                    append(activeAlbum.singerName.ifBlank { "专辑" })
                    if (activeAlbum.listenCnt > 1) {
                        append(" · 听过 ${activeAlbum.listenCnt} 次")
                    }
                }
            } else {
                "收听专辑曲目后将自动展示在此处"
            },
        coverUrl = activeAlbum?.coverUrl.orEmpty(),
        badgeIcon = Icons.Rounded.Album,
        accentColor = MaterialTheme.colorScheme.primary,
        accentContainerColor = MaterialTheme.colorScheme.primaryContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onPlayClick = null,
        onCardClick = onCardClick,
        modifier = Modifier.width(cardWidth),
    )
}

@Composable
private fun RecentPlaylistsHeroCard(
    playlists: List<RecentPlaylistItem>,
    cardWidth: Dp,
    onCardClick: () -> Unit,
) {
    val isForeground by AppLifecycleManager.isForeground.collectAsState()

    // 5 最新 + 25 随机构建候选池
    val candidatePool = remember(playlists) {
        RotatingCandidatePool.buildCandidatePool(playlists, fixedCount = 5, randomCount = 25)
    }

    var shuffledList by remember { mutableStateOf<List<RecentPlaylistItem>>(emptyList()) }
    var currentDisplayIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(candidatePool) {
        if (candidatePool.isNotEmpty()) {
            shuffledList = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = shuffledList.lastOrNull())
            currentDisplayIndex = 0
        } else {
            shuffledList = emptyList()
            currentDisplayIndex = 0
        }
    }

    // 15 秒无序轮播定时器（前台时执行，错峰 4s）
    LaunchedEffect(shuffledList, isForeground) {
        if (!isForeground || shuffledList.size <= 1) return@LaunchedEffect
        delay(PLAYLIST_PHASE_OFFSET_MS)
        while (isActive) {
            delay(ROTATION_INTERVAL_MS)
            val nextIndex = currentDisplayIndex + 1
            if (nextIndex < shuffledList.size) {
                currentDisplayIndex = nextIndex
            } else {
                shuffledList = RotatingCandidatePool.createShuffledQueue(candidatePool, lastItem = shuffledList.lastOrNull())
                currentDisplayIndex = 0
            }
        }
    }

    val activePlaylist = shuffledList.getOrNull(currentDisplayIndex) ?: playlists.firstOrNull()

    HeroRecommendCard(
        badgeText = "最近歌单",
        subtitleText = if (playlists.isNotEmpty()) "共 ${playlists.size} 个 · 20 秒无序轮播" else "暂无歌单",
        title = activePlaylist?.title ?: "最近播放的歌单",
        caption =
            if (activePlaylist != null) {
                buildString {
                    if (activePlaylist.creatorNick.isNotBlank()) {
                        append("by ${activePlaylist.creatorNick} · ")
                    }
                    append("${activePlaylist.songCount} 首")
                    if (activePlaylist.listenCnt > 1) {
                        append(" · 听过 ${activePlaylist.listenCnt} 次")
                    }
                }
            } else {
                "收听歌单曲目后将自动展示在此处"
            },
        coverUrl = activePlaylist?.coverUrl.orEmpty(),
        badgeIcon = Icons.AutoMirrored.Rounded.QueueMusic,
        accentColor = MaterialTheme.colorScheme.secondary,
        accentContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        onAccentContainerColor = MaterialTheme.colorScheme.onSecondaryContainer,
        onPlayClick = null,
        onCardClick = onCardClick,
        modifier = Modifier.width(cardWidth),
    )
}
