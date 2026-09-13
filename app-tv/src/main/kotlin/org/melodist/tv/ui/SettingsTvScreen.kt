package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import org.melodist.tv.ui.settings.*
import org.melodist.tv.ui.theme.LocalMonetSurface
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberMonetSurfaceColor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics
import org.melodist.tv.ui.theme.toMonetContainer

enum class SettingsCategory(
    val title: String,
    val subtitle: String,
) {
    Account("账号与授权", "QQ / 微信 / QQ音乐扫码登录"),
    AudioQuality("音频与音质", "首选音质 / Master / Hi-Res / SQ"),
    Lyrics("播放与歌词", "双语逐行翻译 / 歌词字号大小"),
    ScreenSaver("OLED 屏保与显示", "闲置等待时间 / 像素微移 / 播放屏保"),
    Storage("存储与缓存清理", "缓存占用统计 / 图片与歌词缓存释放"),
    About("关于 Melodist TV", "系统环境 / 音频直通 / 解码能力"),
}

@Composable
fun SettingsTvScreen(
    surfaceColor: Color = rememberMonetSurfaceColor(),
    onBack: () -> Unit = {},
) {
    BackHandler {
        onBack()
    }

    val metrics = rememberTvWindowMetrics()

    var selectedCategory by remember { mutableStateOf(SettingsCategory.Account) }
    var selectedChannel by remember { mutableStateOf(LoginChannel.QQ) }

    // 焦点流转拓扑：为每个左侧分类创建专属 FocusRequester
    val menuFocusRequesters =
        remember {
            SettingsCategory.entries.associateWith { FocusRequester() }
        }

    LaunchedEffect(Unit) {
        menuFocusRequesters[selectedCategory]?.requestFocus()
    }

    val currentMenuRequester = menuFocusRequesters[selectedCategory] ?: FocusRequester.Default

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(
                    start = metrics.horizontalSafePadding,
                    end = metrics.horizontalSafePadding,
                    top = metrics.verticalSafePadding,
                    bottom = metrics.verticalSafePadding,
                ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部导航与层级标题
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "设置",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 主视窗：左右分栏
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                // 左侧设置分类列表 (使用 LazyColumn 支持 D-Pad 顺畅导航与平滑带入视口)
                LazyColumn(
                    modifier =
                        Modifier
                            .width(320.dp)
                            .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(SettingsCategory.entries) { category ->
                        val isSelected = category == selectedCategory
                        val requester = menuFocusRequesters[category] ?: FocusRequester.Default
                        val itemModifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .focusRequester(requester)
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        selectedCategory = category
                                    }
                                }

                        CategoryMenuItem(
                            title = category.title,
                            subtitle = category.subtitle,
                            isSelected = isSelected,
                            surfaceColor = surfaceColor,
                            modifier = itemModifier,
                            onClick = { selectedCategory = category },
                        )
                    }
                }

                // 右侧主面板 (显式支持按 ← 返回左侧当前分类)
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(MelodistShapes.CardCorner)
                            .background(surfaceColor.toMonetContainer(0.05f))
                            .border(
                                BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                                MelodistShapes.CardCorner,
                            ).padding(32.dp),
                ) {
                    CompositionLocalProvider(LocalMonetSurface provides surfaceColor) {
                        when (selectedCategory) {
                            SettingsCategory.Account -> {
                                AccountLoginPanel(
                                    selectedChannel = selectedChannel,
                                    onChannelSelected = { selectedChannel = it },
                                    menuRequester = currentMenuRequester,
                                )
                            }
                            SettingsCategory.AudioQuality -> {
                                AudioQualityPanel(menuRequester = currentMenuRequester)
                            }
                            SettingsCategory.Lyrics -> {
                                LyricsPanel(menuRequester = currentMenuRequester)
                            }
                            SettingsCategory.ScreenSaver -> {
                                ScreenSaverPanel(menuRequester = currentMenuRequester)
                            }
                            SettingsCategory.Storage -> {
                                StoragePanel(menuRequester = currentMenuRequester)
                            }
                            SettingsCategory.About -> {
                                AboutPanel(menuRequester = currentMenuRequester)
                            }
                        }
                    }
                }
            }
        }
    }
}
