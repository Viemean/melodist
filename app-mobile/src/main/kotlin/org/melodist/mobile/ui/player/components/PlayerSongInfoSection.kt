package org.melodist.mobile.ui.player.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

@Composable
fun PlayerSongInfoSection(
    song: Song?,
    isFavorite: Boolean,
    isFavSupported: Boolean,
    currentTier: AudioQualityTier,
    animatedAccentColor: Color,
    controlContainerColor: Color,
    contentPrimary: Color,
    contentSecondary: Color,
    contentTertiary: Color,
    onSongInfoClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQualitySheet: () -> Unit,
    modifier: Modifier = Modifier,
    isFromCache: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSongInfoClick,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song?.name ?: "未在播放",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentPrimary,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song?.singer?.ifBlank { "未知歌手" } ?: "未知歌手",
                style = MaterialTheme.typography.titleMedium,
                color = contentSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (!song?.album.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentTertiary,
                    maxLines = 1,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(start = 12.dp),
        ) {
            if (isFavSupported) {
                FilledTonalIconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(38.dp),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = controlContainerColor,
                            contentColor = if (isFavorite) MaterialTheme.colorScheme.error else contentPrimary,
                        ),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "收藏",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            val isLocalOrWebDav =
                song?.songMid?.startsWith("webdav_") == true ||
                    song?.songMid?.startsWith("local_") == true ||
                    !song?.localFilePath.isNullOrBlank()

            val badgeText = if (!isLocalOrWebDav && isFromCache) {
                "${AudioQualityTier.getBadge(currentTier)} · 缓存"
            } else {
                AudioQualityTier.getBadge(currentTier)
            }

            if (isLocalOrWebDav) {
                // 本地与 WebDAV 音乐：单一固定音源，显示静态规格标签，禁止呼出音质切换
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = animatedAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, animatedAccentColor.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = animatedAccentColor,
                        )
                    }
                }
            } else {
                // 在线音乐：可点击呼出多音质选择弹窗
                Surface(
                    onClick = onOpenQualitySheet,
                    shape = RoundedCornerShape(6.dp),
                    color = animatedAccentColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, animatedAccentColor.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = animatedAccentColor,
                        )
                    }
                }
            }
        }
    }
}
