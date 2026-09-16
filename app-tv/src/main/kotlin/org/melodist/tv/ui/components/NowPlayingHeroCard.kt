package org.melodist.tv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.toMonetContainer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NowPlayingHeroCard(
    song: Song?,
    cardHeight: Dp = 250.dp,
    surfaceColor: Color = MelodistColors.SurfaceDark,
    isPlaying: Boolean = true,
    isFavorite: Boolean = false,
    currentTier: AudioQualityTier = AudioQualityTier.Standard,
    progressMs: Long = 155000L,
    progressMsProvider: (() -> Long)? = null,
    durationMs: Long = 236000L,
    cardFocusRequester: FocusRequester? = null,
    buttonsFocusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onCardClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    canFavorite: Boolean = true,
    onFavoriteClick: () -> Unit = {},
    connectedPhoneName: String? = null,
) {
    val songTitle = song?.name ?: "未在播放曲目"
    val songArtist = song?.singer?.takeIf { it.isNotBlank() } ?: if (song != null) "未知歌手" else "请从歌单中选择歌曲播放"
    val songAlbum = song?.album?.takeIf { it.isNotBlank() } ?: if (song != null) "未知专辑" else ""
    val qualityTag = AudioQualityTier.getBadge(currentTier)
    val coverUrl = song?.coverUrl ?: ""
    val actualProgressProvider = progressMsProvider ?: { progressMs }

    val actualCardRequester = cardFocusRequester ?: remember { FocusRequester() }
    val playPauseRequester = buttonsFocusRequester ?: remember { FocusRequester() }
    val favoriteRequester = remember { FocusRequester() }
    var isCardFocused by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .focusRequester(actualCardRequester)
                .onFocusChanged { isCardFocused = it.isFocused }
                .focusProperties {
                    if (upFocusRequester != null) up = upFocusRequester
                    if (downFocusRequester != null) down = downFocusRequester
                    right = playPauseRequester
                }.onPreviewKeyEvent { event ->
                    if (isCardFocused) {
                        val keyCode = event.nativeKeyEvent.keyCode
                        val isEnterKey =
                            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                keyCode == KeyEvent.KEYCODE_ENTER ||
                                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        if (isEnterKey) {
                            if (event.type == KeyEventType.KeyUp) {
                                onCardClick()
                            }
                            true
                        } else if (event.type == KeyEventType.KeyDown) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                playPauseRequester.requestFocus()
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }.focusable()
                .clip(MelodistShapes.CardCorner)
                .background(
                    if (isCardFocused) surfaceColor.toMonetContainer(0.12f) else surfaceColor.toMonetContainer(0.05f),
                ).border(
                    BorderStroke(
                        width = if (isCardFocused) 3.dp else 1.dp,
                        color = if (isCardFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.10f),
                    ),
                    MelodistShapes.CardCorner,
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧信息与主控按键区
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 10.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.85f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onCardClick() },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = songTitle,
                        color = MelodistColors.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = songArtist,
                        color = Color.White.copy(alpha = 0.90f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (songAlbum.isNotBlank()) {
                        Text(
                            text = songAlbum,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Column {
                    // 进度条
                    HeroProgressBar(
                        progressMsProvider = actualProgressProvider,
                        durationMs = durationMs,
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val btnBg = surfaceColor.toMonetContainer(0.08f)
                            HeroActionButton(
                                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                label = if (isPlaying) "暂停" else "播放",
                                isAccent = false,
                                buttonContainerColor = btnBg,
                                textColor = Color.White,
                                modifier =
                                    Modifier
                                        .focusRequester(playPauseRequester)
                                        .focusProperties {
                                            up = actualCardRequester
                                            if (downFocusRequester != null) down = downFocusRequester
                                            left = actualCardRequester
                                            right = if (canFavorite) favoriteRequester else actualCardRequester
                                        },
                                onClick = onPlayPauseClick,
                            )

                            if (canFavorite) {
                                HeroActionButton(
                                    icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    label = if (isFavorite) "已收藏" else "收藏",
                                    buttonContainerColor = btnBg,
                                    textColor = if (isFavorite) MelodistColors.FavoriteRed else MelodistColors.TextPrimary,
                                    modifier =
                                        Modifier
                                            .focusRequester(favoriteRequester)
                                            .focusProperties {
                                                up = actualCardRequester
                                                if (downFocusRequester != null) down = downFocusRequester
                                                left = playPauseRequester
                                                right = actualCardRequester
                                            },
                                    onClick = onFavoriteClick,
                                )
                            }

                            if (qualityTag.isNotBlank()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .height(40.dp)
                                            .defaultMinSize(minWidth = 56.dp)
                                            .clip(MelodistShapes.ButtonCorner)
                                            .background(btnBg)
                                            .border(
                                                BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                                                MelodistShapes.ButtonCorner,
                                            ).padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = qualityTag,
                                        color = MelodistColors.QualityGoldText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            if (!connectedPhoneName.isNullOrBlank()) {
                                Box(
                                    modifier =
                                        Modifier
                                            .height(40.dp)
                                            .clip(MelodistShapes.ButtonCorner)
                                            .background(Color(0xFF2E7D32).copy(alpha = 0.25f))
                                            .border(
                                                BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.6f)),
                                                MelodistShapes.ButtonCorner,
                                            ).padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.PhoneAndroid,
                                            contentDescription = "手机已连接",
                                            tint = Color(0xFF81C784),
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "手机互联",
                                            color = Color(0xFF81C784),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }

                        // 用户框选位置：当前播放时间 / 总时长（提升为高对比纯白 85% 透明度）
                        HeroProgressText(
                            progressMsProvider = actualProgressProvider,
                            durationMs = durationMs,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(28.dp))

            // 封面展示
            if (coverUrl.isNotEmpty() || song != null) {
                MelodistElevatedCover(
                    coverUrl = coverUrl,
                    albumMid = song?.albumMid.orEmpty(),
                    visualMid = song?.visualMid.orEmpty(),
                    songMid = song?.songMid.orEmpty(),
                    contentDescription = "Cover",
                    shape = RoundedCornerShape(6.dp),
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(vertical = 10.dp, horizontal = 10.dp)
                            .aspectRatio(1f),
                    onClick = onCardClick,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(vertical = 10.dp, horizontal = 10.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(surfaceColor.toMonetContainer(0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Melodist 4K",
                        color = MelodistColors.TextMuted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroActionButton(
    icon: ImageVector,
    label: String,
    isAccent: Boolean = false,
    buttonContainerColor: Color = Color.Transparent,
    textColor: Color = Color.White,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = if (isAccent) MelodistColors.AccentGreen else buttonContainerColor,
                focusedContainerColor = Color.White,
                contentColor = if (isAccent) Color.Black else textColor,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            ButtonDefaults.scale(
                focusedScale = 1.06f,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HeroProgressBar(
    progressMsProvider: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val progressMs by org.melodist.playback.PlaybackManager.currentPositionMs
        .collectAsState()
    val progressFraction =
        if (durationMs > 0) {
            (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(MelodistShapes.PillCorner)
                .background(MelodistColors.ProgressTrack),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progressFraction)
                    .fillMaxHeight()
                    .background(MelodistColors.AccentGreen),
        )
    }
}

@Composable
private fun HeroProgressText(
    progressMsProvider: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val progressMs by org.melodist.playback.PlaybackManager.currentPositionMs
        .collectAsState()
    val currentFormatted =
        remember(progressMs / 1000) {
            val totalSec = (progressMs / 1000).coerceAtLeast(0)
            "%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
    val totalFormatted =
        remember(durationMs / 1000) {
            val totalSec = (durationMs / 1000).coerceAtLeast(0)
            "%02d:%02d".format(totalSec / 60, totalSec % 60)
        }
    Text(
        text = "$currentFormatted / $totalFormatted",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}
