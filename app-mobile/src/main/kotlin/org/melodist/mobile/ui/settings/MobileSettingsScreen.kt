package org.melodist.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.api.PlaybackCredentialsManager
import org.melodist.api.UserSession
import org.melodist.data.AppSettingsManager
import org.melodist.mobile.ui.settings.sections.SettingsAboutSection
import org.melodist.mobile.ui.settings.sections.SettingsAccountCard
import org.melodist.mobile.ui.settings.sections.SettingsAppearanceSection
import org.melodist.mobile.ui.settings.sections.SettingsCredentialsDialogs
import org.melodist.mobile.ui.settings.sections.SettingsPlaybackSection
import org.melodist.mobile.ui.settings.sections.SettingsStorageSection

@Composable
fun MobileSettingsScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by AppSettingsManager.settings.collectAsState()
    val cacheUsage by AppSettingsManager.cacheUsage.collectAsState()
    val userProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn
    val playbackCreds by PlaybackCredentialsManager.credentialsFlow.collectAsState()

    var showPlaybackCredsDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
        ) {
            // 顶部导航栏
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 设置列表流
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. 账号资料卡片
                item {
                    SettingsAccountCard(
                        userProfile = userProfile,
                        isLoggedIn = isLoggedIn,
                        onOpenLogin = onOpenLogin,
                    )
                }

                // 2. 外观与主题
                item {
                    SettingsAppearanceSection(settings = settings)
                }

                // 3. 播放、音质与歌词显示
                item {
                    SettingsPlaybackSection(settings = settings)
                }

                // 3. 下载与存储缓存
                item {
                    SettingsStorageSection(cacheUsage = cacheUsage)
                }

                // 4. 关于应用
                item {
                    SettingsAboutSection(
                        playbackCreds = playbackCreds,
                        onTriggerCredsDialog = { showPlaybackCredsDialog = true },
                    )
                }
            }
        }
    }

    // 凭证管理系列弹窗
    SettingsCredentialsDialogs(
        showPlaybackCredsDialog = showPlaybackCredsDialog,
        onDismissCredsDialog = { showPlaybackCredsDialog = false },
        userProfile = userProfile,
        playbackCreds = playbackCreds,
        isLoggedIn = isLoggedIn,
    )
}
