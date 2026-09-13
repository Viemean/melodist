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

    Dialog(onDismissRequest = onDismissRequest) {
        Box(
            modifier =
                Modifier
                    .width(620.dp)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF161A24).copy(alpha = 0.96f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
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
                    }.padding(28.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // 头部曲目标题
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "查看歌手与专辑",
                        fontSize = 14.sp,
                        color = MelodistColors.FocusTeal,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = song.name.ifBlank { "曲目详情" },
                        fontSize = 20.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

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

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(200),
        label = "ArtistCardBorder",
    )

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(140.dp)
                .height(170.dp)
                .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(MelodistShapes.CardCorner),
        colors =
            CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = Color.White.copy(alpha = 0.15f),
            ),
        border =
            CardDefaults.border(
                border = Border(border = BorderStroke(1.dp, animatedBorderColor), shape = MelodistShapes.CardCorner),
                focusedBorder = Border(border = BorderStroke(2.5.dp, MelodistColors.FocusTeal), shape = MelodistShapes.CardCorner),
            ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 圆形头像（带文字徽章兜底）
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.5.dp, if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.25f), CircleShape),
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
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = artist.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "歌手",
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp,
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

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.12f),
        animationSpec = tween(200),
        label = "AlbumCardBorder",
    )

    Card(
        onClick = onClick,
        modifier =
            modifier
                .width(140.dp)
                .height(170.dp)
                .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(MelodistShapes.CardCorner),
        colors =
            CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.05f),
                focusedContainerColor = Color.White.copy(alpha = 0.15f),
            ),
        border =
            CardDefaults.border(
                border = Border(border = BorderStroke(1.dp, animatedBorderColor), shape = MelodistShapes.CardCorner),
                focusedBorder = Border(border = BorderStroke(2.5.dp, MelodistColors.FocusTeal), shape = MelodistShapes.CardCorner),
            ),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 方形圆角封面
            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .clip(MelodistShapes.CardCorner)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(
                            1.5.dp,
                            if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.25f),
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
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = albumTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "专辑",
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
