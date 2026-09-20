package org.melodist.tv.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.data.AppSettingsManager
import org.melodist.tv.ui.theme.LocalMonetSurface
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.toMonetContainer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StoragePanel(menuRequester: FocusRequester) {
    val cacheUsage by AppSettingsManager.cacheUsage.collectAsState()
    val scrollState = rememberScrollState()
    var statusFeedback by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        AppSettingsManager.calculateCacheUsage()
    }

    val surfaceColor = LocalMonetSurface.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SettingPanelHeader(title = "存储与缓存管理")

        // 缓存占用统计
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "应用缓存占用总计",
                        fontSize = 15.sp,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = cacheUsage.totalFormatted,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }

                if (statusFeedback.isNotBlank()) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MelodistColors.AccentGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = statusFeedback,
                            fontSize = 13.sp,
                            color = MelodistColors.AccentGreen,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f)),
            )

            // 细分项目
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CacheDetailItem(label = "封面与图片", value = cacheUsage.imageFormatted)
                CacheDetailItem(label = "音频与媒体", value = cacheUsage.mediaFormatted)
                CacheDetailItem(label = "匹配歌词", value = cacheUsage.matchedLyricsFormatted)
                CacheDetailItem(label = "临时数据", value = cacheUsage.tempFormatted)
            }

            if (cacheUsage.mediaQuotaBytes > 0L) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "音频配额占用",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        val percent = (cacheUsage.mediaUsageFraction * 100).toInt()
                        val trackDesc = if (cacheUsage.cachedTrackCount > 0) "，已缓存 ${cacheUsage.cachedTrackCount} 首歌曲" else ""
                        Text(
                            text = "${cacheUsage.mediaFormatted} / ${cacheUsage.mediaQuotaFormatted} (${percent}%$trackDesc)",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.12f)),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(fraction = cacheUsage.mediaUsageFraction)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MelodistColors.FocusTeal),
                        )
                    }
                }
            }
        }

        // 操作按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = {
                    AppSettingsManager.clearImageAndTempCache()
                    statusFeedback = "媒体与图片缓存已释放"
                },
                modifier =
                    Modifier
                        .weight(1f)
                        .focusProperties { left = menuRequester },
                shape =
                    ButtonDefaults.shape(
                        shape = MelodistShapes.ButtonCorner,
                        focusedShape = MelodistShapes.ButtonCorner,
                    ),
                colors =
                    ButtonDefaults.colors(
                        containerColor = surfaceColor.toMonetContainer(0.08f),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                border =
                    ButtonDefaults.border(
                        border =
                            Border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                        focusedBorder =
                            Border(
                                border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                    ),
                scale = ButtonDefaults.scale(focusedScale = 1.04f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "释放媒体与图片",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    AppSettingsManager.clearMatchedLyricCache()
                    statusFeedback = "官方匹配歌词已清空"
                },
                modifier = Modifier.weight(1f),
                shape =
                    ButtonDefaults.shape(
                        shape = MelodistShapes.ButtonCorner,
                        focusedShape = MelodistShapes.ButtonCorner,
                    ),
                colors =
                    ButtonDefaults.colors(
                        containerColor = surfaceColor.toMonetContainer(0.08f),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                border =
                    ButtonDefaults.border(
                        border =
                            Border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                        focusedBorder =
                            Border(
                                border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                    ),
                scale = ButtonDefaults.scale(focusedScale = 1.04f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "清空匹配歌词",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    AppSettingsManager.clearAllCaches()
                    statusFeedback = "全部应用缓存已清空"
                },
                modifier = Modifier.weight(1f),
                shape =
                    ButtonDefaults.shape(
                        shape = MelodistShapes.ButtonCorner,
                        focusedShape = MelodistShapes.ButtonCorner,
                    ),
                colors =
                    ButtonDefaults.colors(
                        containerColor = surfaceColor.toMonetContainer(0.08f),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                border =
                    ButtonDefaults.border(
                        border =
                            Border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                        focusedBorder =
                            Border(
                                border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                    ),
                scale = ButtonDefaults.scale(focusedScale = 1.04f),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "清空全部缓存",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
