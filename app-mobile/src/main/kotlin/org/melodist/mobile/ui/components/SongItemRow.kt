package org.melodist.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.melodist.model.Song

private val CoverShape = RoundedCornerShape(8.dp)
private val VipBadgeShape = RoundedCornerShape(3.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongItemRow(
    song: Song,
    isPlayingThis: Boolean,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    showLocalBadge: Boolean = true,
    showWebDavBadge: Boolean = true,
    isMultiSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onSelectToggle: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onMoreClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (isMultiSelectMode) {
                            onSelectToggle?.invoke()
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CoverShape)
                    .then(
                        if (isMultiSelectMode) {
                            Modifier.clickable { onSelectToggle?.invoke() }
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            AlbumArtImage(
                coverUrl = song.thumbnailCoverUrl,
                contentDescription = song.name,
                shape = CoverShape,
                elevation = 0.dp,
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                placeholderIconSize = 22.dp,
                modifier = Modifier.matchParentSize(),
            )

            if (isMultiSelectMode) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
                                } else {
                                    Color.Black.copy(alpha = 0.25f)
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "已选中",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(20.dp)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                        )
                    }
                }
            } else if (isPlayingThis) {
                Box(
                    modifier =
                        Modifier
                            .matchParentSize()
                            .clip(CoverShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Equalizer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
        ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            val onSurfaceColor = MaterialTheme.colorScheme.onSurface
            val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
            val titleColor = if (isPlayingThis) primaryColor else onSurfaceColor

            val titleText =
                remember(song.name, highlightQuery, titleColor, primaryColor) {
                    buildHighlightedAnnotatedString(
                        text = song.name,
                        query = highlightQuery,
                        defaultColor = titleColor,
                        highlightColor = primaryColor,
                        highlightFontWeight = FontWeight.Bold,
                    )
                }

            Text(
                text = titleText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isPlayingThis) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val rawSubtitle =
                remember(song.singer, song.album) {
                    buildString {
                        append(song.singer.ifBlank { "未知歌手" })
                        if (song.album.isNotBlank()) {
                            append(" · ")
                            append(song.album)
                        }
                    }
                }

            val subtitleText =
                remember(rawSubtitle, highlightQuery, onSurfaceVariantColor, primaryColor) {
                    buildHighlightedAnnotatedString(
                        text = rawSubtitle,
                        query = highlightQuery,
                        defaultColor = onSurfaceVariantColor,
                        highlightColor = primaryColor,
                        highlightFontWeight = FontWeight.Bold,
                    )
                }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (song.isVip) {
                    Text(
                        text = "VIP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                    VipBadgeShape,
                                ).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (showLocalBadge && song.isLocal) {
                    Text(
                        text = "本地",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                                    VipBadgeShape,
                                ).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (showWebDavBadge && song.isWebDav) {
                    Text(
                        text = "WebDAV",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                                    VipBadgeShape,
                                ).padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (!isMultiSelectMode && onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 构建带有子串高亮样式的 AnnotatedString。
 * 当 query 为空或未匹配时直接返回普通 AnnotatedString，避免额外分配。
 */
fun buildHighlightedAnnotatedString(
    text: String,
    query: String,
    defaultColor: Color,
    highlightColor: Color,
    highlightFontWeight: FontWeight = FontWeight.Bold,
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty() || !text.contains(q, ignoreCase = true)) {
        return AnnotatedString(text, SpanStyle(color = defaultColor))
    }
    return buildAnnotatedString {
        var startIndex = 0
        val lowerText = text.lowercase()
        val lowerQuery = q.lowercase()
        while (startIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
            if (matchIndex == -1) {
                withStyle(SpanStyle(color = defaultColor)) {
                    append(text.substring(startIndex))
                }
                break
            }
            if (matchIndex > startIndex) {
                withStyle(SpanStyle(color = defaultColor)) {
                    append(text.substring(startIndex, matchIndex))
                }
            }
            val endIndex = matchIndex + lowerQuery.length
            withStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = highlightFontWeight,
                ),
            ) {
                append(text.substring(matchIndex, endIndex))
            }
            startIndex = endIndex
        }
    }
}
