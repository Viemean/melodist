package org.melodist.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.model.Artist
import org.melodist.model.Song
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.toMonetContainer

/**
 * 通用曲目关联歌手与专辑操作面板（长按或播放页呼出）
 * 支持多歌手卡片左右平滑横滑，单专辑卡片直接跳转
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SongArtistAlbumDialog(
    song: Song,
    onDismissRequest: () -> Unit,
    onSelectArtist: (artistMid: String, artistName: String) -> Unit,
    onSelectAlbum: (albumMid: String, albumName: String) -> Unit,
) {
    if (!song.canShowArtistAlbumDialog) {
        LaunchedEffect(Unit) {
            onDismissRequest()
        }
        return
    }

    val firstFocusRequester = remember { FocusRequester() }

    // 提取歌手列表
    val artists =
        remember(song) {
            if (song.singerList.isNotEmpty()) {
                song.singerList
            } else {
                song.singer.split("/", "、", "&", ",").map { it.trim() }.filter { it.isNotBlank() }.map { name ->
                    Artist(id = 0L, mid = "", name = name)
                }
            }
        }

    val dialogOpenTime = remember { System.currentTimeMillis() }

    fun canTriggerAction(): Boolean {
        val elapsed = System.currentTimeMillis() - dialogOpenTime
        return elapsed >= 500L
    }

    val monetSurfaceColor = rememberMonetSurfaceColor(song.coverUrl)
    val dialogBackgroundColor =
        remember(monetSurfaceColor) {
            monetSurfaceColor.toMonetContainer(elevation = 0.08f).copy(alpha = 0.95f)
        }

    Dialog(onDismissRequest = onDismissRequest) {
        val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setDimAmount(0.28f)
        }

        Box(
            modifier =
                Modifier
                    .width(680.dp)
                    .wrapContentHeight()
                    .clip(MelodistShapes.DialogCorner)
                    .background(dialogBackgroundColor)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), MelodistShapes.DialogCorner)
                    .onPreviewKeyEvent { event ->
                        val elapsed = System.currentTimeMillis() - dialogOpenTime
                        if (elapsed < 500L) {
                            val isConfirmKey =
                                event.key == Key.DirectionCenter ||
                                    event.key == Key.Enter ||
                                    event.key == Key.NumPadEnter
                            if (isConfirmKey) {
                                // 刚性拦截 0.5 秒内的确认按键（包括长按松开时的 KeyUp 与重复按压），防止误触自动进入
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }.padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 头部曲目标题
                Text(
                    text = song.name.ifBlank { "曲目详情" },
                    fontSize = 22.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 卡片横向滚动流（歌手卡片组 + 专辑卡片）
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    // 1. 歌手卡片组（可左右滚动）
                    itemsIndexed(artists) { index, artist ->
                        ArtistActionCard(
                            artist = artist,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier,
                            onClick = {
                                if (!canTriggerAction()) return@ArtistActionCard
                                onDismissRequest()
                                onSelectArtist(artist.mid, artist.name)
                            },
                        )
                    }

                    // 2. 专辑卡片（单个）
                    if (song.album.isNotBlank() || song.albumMid.isNotBlank()) {
                        item {
                            AlbumActionCard(
                                albumTitle = song.album.ifBlank { "专辑" },
                                albumMid = song.albumMid,
                                coverUrl = song.coverUrl,
                                modifier = if (artists.isEmpty()) Modifier.focusRequester(firstFocusRequester) else Modifier,
                                onClick = {
                                    if (!canTriggerAction()) return@AlbumActionCard
                                    onDismissRequest()
                                    onSelectAlbum(song.albumMid, song.album)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            firstFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistActionCard(
    artist: Artist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var resolvedMid by remember(artist) { mutableStateOf(artist.mid) }

    // 当没有歌手 mid 时，后台轻量拉取
    LaunchedEffect(artist.name) {
        if (resolvedMid.isBlank() && artist.name.isNotBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val searchResult = MusicApiService().search(artist.name, page = 1, pageSize = 5)
                    val matchedSong =
                        searchResult.firstOrNull { s ->
                            s.singerList.any { it.name.equals(artist.name, ignoreCase = true) }
                        }
                    val foundMid =
                        matchedSong
                            ?.singerList
                            ?.firstOrNull {
                                it.name.equals(artist.name, ignoreCase = true)
                            }?.mid
                            .orEmpty()
                    if (foundMid.isNotBlank()) {
                        resolvedMid = foundMid
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    val avatarUrl =
        remember(resolvedMid, artist.avatarUrl) {
            if (resolvedMid.isNotBlank()) {
                MusicApiService.getSingerAvatarUrl(resolvedMid)
            } else {
                artist.avatarUrl
            }
        }

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(160.dp)
                .height(196.dp)
                .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(MelodistShapes.CardCorner),
        colors =
            CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = Color.White,
            ),
        border =
            CardDefaults.border(
                border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape = MelodistShapes.CardCorner),
                focusedBorder = Border(border = BorderStroke(2.5.dp, MelodistColors.FocusTeal), shape = MelodistShapes.CardCorner),
            ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 圆形头像（带文字徽章兜底，内层移除绿色高亮边框）
            Box(
                modifier =
                    Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(
                            1.dp,
                            if (isFocused) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.22f),
                            CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarUrl.isNotBlank() || resolvedMid.isNotBlank()) {
                    MelodistAsyncImage(
                        coverUrl = avatarUrl,
                        artistMid = resolvedMid,
                        contentDescription = artist.name,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = artist.name.take(1).uppercase(),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFocused) Color.Black else Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = artist.name,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "歌手",
                color = if (isFocused) Color.DarkGray else Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AlbumActionCard(
    albumTitle: String,
    albumMid: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }

    val resolvedCover =
        remember(albumMid, coverUrl) {
            if (albumMid.isNotBlank()) MusicApiService.getAlbumCoverUrl(albumMid) else coverUrl
        }

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(160.dp)
                .height(196.dp)
                .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(MelodistShapes.CardCorner),
        colors =
            CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f),
                focusedContainerColor = Color.White,
            ),
        border =
            CardDefaults.border(
                border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape = MelodistShapes.CardCorner),
                focusedBorder = Border(border = BorderStroke(2.5.dp, MelodistColors.FocusTeal), shape = MelodistShapes.CardCorner),
            ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 方形圆角封面（内层移除绿色高亮边框）
            Box(
                modifier =
                    Modifier
                        .size(100.dp)
                        .clip(MelodistShapes.CardCorner)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(
                            1.dp,
                            if (isFocused) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.22f),
                            MelodistShapes.CardCorner,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (resolvedCover.isNotBlank() || albumMid.isNotBlank()) {
                    MelodistAsyncImage(
                        coverUrl = resolvedCover,
                        albumMid = albumMid,
                        contentDescription = albumTitle,
                        shape = MelodistShapes.CardCorner,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = albumTitle.take(1).uppercase(),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFocused) Color.Black else Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = albumTitle,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "专辑",
                color = if (isFocused) Color.DarkGray else Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
