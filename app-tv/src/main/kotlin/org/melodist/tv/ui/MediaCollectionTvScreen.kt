package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getFavoriteAlbums
import org.melodist.api.getPlaylists
import org.melodist.model.Album
import org.melodist.model.Playlist
import org.melodist.tv.ui.components.MelodistAsyncImage
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaCollectionTvScreen(
    type: String = "playlists", // "playlists" 或 "collections"
    onSelectPlaylist: (Playlist) -> Unit = {},
    onSelectAlbum: (Album) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    BackHandler {
        onBack()
    }

    val metrics = rememberTvWindowMetrics()
    val monetBg = rememberMonetSurfaceColor()
    val apiService = remember { MusicApiService() }
    val userProfile by UserSession.profileFlow.collectAsState()

    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val isPlaylistMode = type == "playlists"
    val pageTitle = if (isPlaylistMode) "我的歌单" else "我的收藏"
    val pageSubtitle =
        if (isPlaylistMode) {
            if (playlists.isNotEmpty()) "共收录 ${playlists.size} 个自建与收藏歌单" else "自建与外部收藏歌单列表"
        } else {
            if (albums.isNotEmpty()) "共收藏 ${albums.size} 张专辑" else "已收藏专辑与艺术家资产"
        }

    val firstItemRequester = remember { FocusRequester() }

    LaunchedEffect(type, userProfile) {
        if (!UserSession.isLoggedIn) {
            playlists = emptyList()
            albums = emptyList()
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        try {
            if (isPlaylistMode) {
                playlists = apiService.getPlaylists().filterNot { it.isMyFavorite }
            } else {
                albums = apiService.getFavoriteAlbums()
            }
        } catch (_: Exception) {
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(playlists, albums) {
        if (playlists.isNotEmpty() || albums.isNotEmpty()) {
            firstItemRequester.requestFocus()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(monetBg)
                .padding(
                    start = metrics.horizontalSafePadding,
                    end = metrics.horizontalSafePadding,
                    top = metrics.verticalSafePadding,
                    bottom = metrics.verticalSafePadding,
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 顶部导航与状态头
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = pageTitle,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )
                Text(
                    text = pageSubtitle,
                    fontSize = 14.sp,
                    color = MelodistColors.TextSecondary,
                )
            }

            if (!UserSession.isLoggedIn) {
                // 未登录引导卡片
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = "未登录",
                        tint = MelodistColors.TextMuted,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "尚未登录 QQ 音乐账号",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "登录后即可同步查看个人自建歌单与收藏的专辑资产",
                        fontSize = 14.sp,
                        color = MelodistColors.TextMuted,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        shape = ButtonDefaults.shape(MelodistShapes.PillCorner),
                        colors =
                            ButtonDefaults.colors(
                                containerColor = MelodistColors.AccentGreen,
                                focusedContainerColor = Color.White,
                                contentColor = Color.Black,
                                focusedContentColor = Color.Black,
                            ),
                        border =
                            ButtonDefaults.border(
                                focusedBorder =
                                    Border(
                                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                        shape = MelodistShapes.PillCorner,
                                    ),
                            ),
                        scale = ButtonDefaults.scale(focusedScale = 1.06f),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "前往设置扫码登录",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "正在同步资产列表...",
                        color = MelodistColors.TextSecondary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else if (isPlaylistMode && playlists.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无自建或收藏的歌单",
                        color = MelodistColors.TextMuted,
                        fontSize = 16.sp,
                    )
                }
            } else if (!isPlaylistMode && albums.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无收藏的专辑",
                        color = MelodistColors.TextMuted,
                        fontSize = 16.sp,
                    )
                }
            } else {
                // 实体卡片网格
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 190.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    contentPadding = PaddingValues(bottom = 36.dp),
                ) {
                    if (isPlaylistMode) {
                        itemsIndexed(playlists) { index, playlist ->
                            CollectionCardItem(
                                title = playlist.name,
                                subtitle = "共 ${playlist.songCount} 首曲目",
                                coverUrl = playlist.picUrl,
                                badgeText = if (playlist.isFav) "收藏歌单" else "自建歌单",
                                isFirst = index == 0,
                                focusRequester = if (index == 0) firstItemRequester else null,
                                onClick = { onSelectPlaylist(playlist) },
                            )
                        }
                    } else {
                        itemsIndexed(albums) { index, album ->
                            CollectionCardItem(
                                title = album.title,
                                subtitle = "${album.artist} · 共 ${album.songCount} 首",
                                coverUrl = album.coverUrl,
                                albumMid = album.mid,
                                badgeText = "专辑",
                                isFirst = index == 0,
                                focusRequester = if (index == 0) firstItemRequester else null,
                                onClick = { onSelectAlbum(album) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollectionCardItem(
    title: String,
    subtitle: String,
    coverUrl: String,
    albumMid: String = "",
    badgeText: String = "",
    isFirst: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (isFirst && focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { isFocused = it.isFocused },
        shape =
            CardDefaults.shape(
                shape = MelodistShapes.CardCorner,
                focusedShape = MelodistShapes.CardCorner,
            ),
        colors =
            CardDefaults.colors(
                containerColor = MelodistColors.ContainerDarkSecondary,
                focusedContainerColor = Color.White,
            ),
        border =
            CardDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        shape = MelodistShapes.CardCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.CardCorner,
                    ),
            ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 方形封面
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF141822)),
                contentAlignment = Alignment.Center,
            ) {
                if (coverUrl.isNotBlank() || albumMid.isNotBlank()) {
                    MelodistAsyncImage(
                        coverUrl = coverUrl,
                        albumMid = albumMid,
                        contentDescription = title,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MelodistColors.TextMuted,
                        modifier = Modifier.size(48.dp),
                    )
                }

                if (badgeText.isNotBlank()) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            // 名称与副文本 (获焦时白底黑字，未获焦时暗底白字)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isFocused) Color(0xFF12141A) else MelodistColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = if (isFocused) Color(0xFF4A5568) else MelodistColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
