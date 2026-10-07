package org.melodist.mobile.ui.settings.sections

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserProfile
import org.melodist.api.UserSession
import org.melodist.api.refreshCurrentUserProfile
import org.melodist.mobile.ui.components.SettingsGroupCard

@Composable
fun SettingsAccountCard(
    userProfile: UserProfile,
    isLoggedIn: Boolean,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogoutConfirmDialog by remember { mutableStateOf(false) }
    var showVipDetailDialog by remember { mutableStateOf(false) }

    val topVipBadge = remember(userProfile) { getTopVipBadgeText(userProfile) }

    SettingsGroupCard(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(enabled = isLoggedIn) { showVipDetailDialog = true },
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
                            imageVector = Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (isLoggedIn) userProfile.nick.ifBlank { "已登录用户" } else "未登录",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (isLoggedIn && topVipBadge != null) Modifier.weight(1f, fill = false) else Modifier,
                        )
                        if (isLoggedIn && topVipBadge != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Text(
                                    text = topVipBadge,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isLoggedIn) "UIN: ${userProfile.uin}" else "登录以同步歌单、收藏与资产",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

    if (showVipDetailDialog) {
        val levelStr = if (userProfile.vipLevel > 0) " Lv.${userProfile.vipLevel}" else ""
        val activeTiers = mutableListOf<String>()
        if (userProfile.isSvip) activeTiers.add("超级会员 SVIP$levelStr")
        if (userProfile.isHugeVip) activeTiers.add("豪华绿钻$levelStr")
        if (userProfile.isVip && !userProfile.isHugeVip && !userProfile.isSvip) activeTiers.add("普通绿钻$levelStr")
        if (userProfile.isCpLover) activeTiers.add("情侣会员")
        if (userProfile.isGroupVip) activeTiers.add("亲情会员")
        val vipTierDisplay = if (activeTiers.isEmpty()) "未开通" else activeTiers.joinToString("\n")

        AlertDialog(
            onDismissRequest = { showVipDetailDialog = false },
            title = { Text("会员与账号详情", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    VipDetailRow(label = "会员级别", value = vipTierDisplay)

                    if (userProfile.isSvip) {
                        VipDetailRow(
                            label = "SVIP 到期",
                            value = userProfile.svipExpireAt.ifBlank { "无" },
                        )
                    }
                    if (userProfile.isHugeVip) {
                        VipDetailRow(
                            label = if (userProfile.isSvip) "绿钻到期" else "会员到期",
                            value = userProfile.hugeVipExpireAt.ifBlank { "无" },
                        )
                    }
                    if (userProfile.isVip && !userProfile.isHugeVip && !userProfile.isSvip) {
                        VipDetailRow(
                            label = "会员到期",
                            value = userProfile.greenVipExpireAt.ifBlank { userProfile.vipExpireAt.ifBlank { "无" } },
                        )
                    }
                    if (userProfile.isCpLover) {
                        VipDetailRow(
                            label = "情侣到期",
                            value = userProfile.cpLoverExpireAt.ifBlank { "已开通" },
                        )
                    }
                    if (userProfile.isGroupVip) {
                        VipDetailRow(
                            label = "亲情到期",
                            value = userProfile.groupVipExpireAt.ifBlank { "已开通" },
                        )
                    }
                    if (activeTiers.isEmpty()) {
                        VipDetailRow(label = "会员有效期", value = "无")
                    }

                    if (userProfile.musicScore > 0) {
                        val formattedScore = java.text.NumberFormat.getIntegerInstance().format(userProfile.musicScore)
                        VipDetailRow(label = "成长积分", value = "$formattedScore 分")
                    }

                    VipDetailRow(
                        label = "年费状态",
                        value = if (userProfile.isYearVip) "是" else "否",
                    )

                    VipDetailRow(
                        label = "乐力级别",
                        value = if (userProfile.musicLevel > 0) "Lv.${userProfile.musicLevel}" else "无",
                    )

                    if (userProfile.isVip && (userProfile.nextVipLevel > 0 || userProfile.vipUpgradePercent > 0f)) {
                        val pct = String.format(java.util.Locale.ROOT, "%.1f%%", userProfile.vipUpgradePercent * 100)
                        val days = if (userProfile.vipUpgradeDays > 0) " 还需 ${userProfile.vipUpgradeDays} 天" else ""
                        VipDetailRow(
                            label = "升级进度",
                            value = "Lv.${userProfile.nextVipLevel} ($pct)$days",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVipDetailDialog = false }) {
                    Text("关闭")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = MusicApiService().refreshCurrentUserProfile()
                        Toast.makeText(
                            context,
                            if (ok) "已刷新账号会员信息" else "刷新失败，请稍后重试",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }) {
                    Text("刷新")
                }
            },
        )
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

@Composable
private fun VipDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun getTopVipBadgeText(userProfile: UserProfile): String? {
    val levelStr = if (userProfile.vipLevel > 0) "Lv${userProfile.vipLevel}" else ""
    return when {
        userProfile.isSvip -> "超级会员$levelStr"
        userProfile.isHugeVip -> "豪华绿钻$levelStr"
        userProfile.isVip && !userProfile.isCpLover && !userProfile.isGroupVip -> "绿钻$levelStr"
        userProfile.isCpLover -> "情侣会员"
        userProfile.isGroupVip -> "亲情会员"
        userProfile.isVip -> "绿钻$levelStr"
        else -> null
    }
}


