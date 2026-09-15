package org.melodist.mobile.ui.settings.sections

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.melodist.api.UserProfile
import org.melodist.api.UserSession
import org.melodist.mobile.ui.components.SettingsGroupCard

@Composable
fun SettingsAccountCard(
    userProfile: UserProfile,
    isLoggedIn: Boolean,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }

    SettingsGroupCard(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatarUrl = userProfile.effectiveAvatarUrl
            if (isLoggedIn && avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = userProfile.nick,
                    modifier =
                        Modifier
                            .size(54.dp)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isLoggedIn) userProfile.nick.ifBlank { "已登录用户" } else "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isLoggedIn) "UIN: ${userProfile.uin}" else "登录以同步歌单、收藏与资产",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isLoggedIn) {
                TextButton(
                    onClick = { showLogoutConfirmDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("退出", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            } else {
                FilledTonalButton(
                    onClick = onOpenLogin,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text("登录")
                }
            }
        }
    }

    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = { Text("确认退出登录") },
            text = { Text("退出登录后将无法继续同步云端歌单与专属推荐歌曲。") },
            confirmButton = {
                TextButton(onClick = {
                    UserSession.clear()
                    showLogoutConfirmDialog = false
                    Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
                }) {
                    Text("确认退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}
