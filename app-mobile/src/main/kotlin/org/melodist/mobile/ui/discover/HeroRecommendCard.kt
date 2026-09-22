package org.melodist.mobile.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

@Composable
fun HeroRecommendCard(
    badgeText: String,
    subtitleText: String,
    title: String,
    caption: String,
    coverUrl: String,
    badgeIcon: ImageVector,
    accentColor: Color,
    accentContainerColor: Color,
    onAccentContainerColor: Color,
    playIcon: ImageVector = Icons.Rounded.PlayArrow,
    playContentDescription: String = "播放",
    onPlayClick: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cardBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val cardModifier = modifier.defaultMinSize(minHeight = 100.dp)
    val cardShape = RoundedCornerShape(18.dp)
    val cardColors = CardDefaults.elevatedCardColors(containerColor = cardBg)
    val cardElevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)

    val cardContent: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    accentContainerColor.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                        ),
                    ).padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧封面
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = coverUrl,
                        transitionSpec = {
                            (slideInHorizontally { fullWidth -> (fullWidth * 0.45f).toInt() } + fadeIn(animationSpec = tween(400)))
                                .togetherWith(slideOutHorizontally { fullWidth -> -(fullWidth * 0.45f).toInt() } + fadeOut(animationSpec = tween(400)))
                        },
                        label = "HeroCardCoverAnimation",
                    ) { targetCover ->
                        if (targetCover.isNotBlank()) {
                            AsyncImage(
                                model = targetCover,
                                contentDescription = title,
                                modifier =
                                    Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    // 右下角徽章图标
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = CircleShape,
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .size(20.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // 中间信息
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = accentColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    AnimatedContent(
                        targetState = title to caption,
                        transitionSpec = {
                            (slideInHorizontally { fullWidth -> (fullWidth * 0.35f).toInt() } + fadeIn(animationSpec = tween(450)))
                                .togetherWith(slideOutHorizontally { fullWidth -> -(fullWidth * 0.35f).toInt() } + fadeOut(animationSpec = tween(450)))
                        },
                        label = "HeroCardTextAnimation",
                    ) { (targetTitle, targetCaption) ->
                        Column {
                            Text(
                                text = targetTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = targetCaption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 右侧操作区：若提供播放回调则显示播放按钮，否则可点击卡片时显示轻量右箭头
                if (onPlayClick != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    FilledTonalIconButton(
                        onClick = onPlayClick,
                        modifier = Modifier.size(46.dp),
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = accentContainerColor,
                                contentColor = onAccentContainerColor,
                            ),
                    ) {
                        Icon(
                            imageVector = playIcon,
                            contentDescription = playContentDescription,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                } else if (onCardClick != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }

    if (onCardClick != null) {
        ElevatedCard(
            onClick = onCardClick,
            modifier = cardModifier,
            shape = cardShape,
            colors = cardColors,
            elevation = cardElevation,
        ) {
            cardContent()
        }
    } else {
        ElevatedCard(
            modifier = cardModifier,
            shape = cardShape,
            colors = cardColors,
            elevation = cardElevation,
        ) {
            cardContent()
        }
    }
}
