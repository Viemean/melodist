package org.melodist.tv.ui.webdav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.model.Song
import org.melodist.tv.ui.theme.MelodistColors

/**
 * WebDAV 音乐库视图
 */
@Composable
fun WebDavLibraryView(
    cachedSongs: List<Song>,
    isScanningMetadata: Boolean = false,
    onScanMissingMetadata: () -> Unit = {},
    onClearLibrary: () -> Unit = {},
    onSongClick: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onSwitchToDirectory: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 曲库概览与批量操作栏
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = MelodistColors.AccentGreen,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "WebDAV 云端曲库（共 ${cachedSongs.size} 首歌曲）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MelodistColors.TextPrimary,
                )
            }

            if (cachedSongs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WebDavNavButton(
                        icon = Icons.Default.PlayArrow,
                        text = "播放全部",
                        isAccent = true,
                        onClick = onPlayAll,
                    )

                    WebDavNavButton(
                        icon = Icons.Default.Sync,
                        text = if (isScanningMetadata) "正在提取信息..." else "扫描补充信息",
                        onClick = onScanMissingMetadata,
                    )

                    WebDavNavButton(
                        icon = Icons.Default.DeleteSweep,
                        text = "清空曲库",
                        onClick = onClearLibrary,
                    )
                }
            }
        }

        if (cachedSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = MelodistColors.TextMuted,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = "WebDAV 音乐库暂无歌曲",
                        fontSize = 16.sp,
                        color = MelodistColors.TextSecondary,
                    )
                    Text(
                        text = "请切换至【目录浏览】并在音频目录点击【扫描本目录】开始收录",
                        fontSize = 13.sp,
                        color = MelodistColors.TextMuted,
                    )
                    WebDavNavButton(
                        icon = Icons.Default.Folder,
                        text = "前往目录浏览",
                        isAccent = true,
                        onClick = onSwitchToDirectory,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(cachedSongs, key = { index, s -> "${s.songMid}_$index" }) { index, song ->
                    WebDavLibrarySongRow(
                        index = index + 1,
                        song = song,
                        onClick = { onSongClick(index) },
                    )
                }
            }
        }
    }
}

/**
 * 音乐库单曲行组件
 */
@Composable
fun WebDavLibrarySongRow(
    index: Int,
    song: Song,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                        ),
                    shape = RoundedCornerShape(10.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号
        Text(
            text = String.format("%02d", index),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.width(36.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 歌名
        Text(
            text = song.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.44f),
        )

        // 艺术家
        Text(
            text = song.singer,
            fontSize = 13.sp,
            color = if (isFocused) Color.Black else Color.White,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(0.28f)
                    .padding(horizontal = 8.dp),
        )

        // 专辑
        Text(
            text = song.album,
            fontSize = 13.sp,
            color = if (isFocused) Color.Black else Color.White,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(0.28f)
                    .padding(horizontal = 8.dp),
        )
    }
}
