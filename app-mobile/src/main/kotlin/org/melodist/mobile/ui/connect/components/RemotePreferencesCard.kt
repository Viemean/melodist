package org.melodist.mobile.ui.connect.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.core.connect.model.RemoteControlMode
import org.melodist.mobile.connect.MobileConnectManager

/**
 * 远程互联偏好设置卡片组件（控制模式切换、本地静音开关与 TV 离线中转开关）
 */
@Composable
fun RemotePreferencesCard(
    remoteControlMode: RemoteControlMode,
    localMute: Boolean,
    tvOfflineProxy: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "互联偏好设置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // 1. 控制模式选择
            Text(
                text = "联动控制模式",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val isTakeover = remoteControlMode == RemoteControlMode.TAKEOVER
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable {
                                MobileConnectManager.setRemoteControlMode(RemoteControlMode.TAKEOVER)
                            },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isTakeover) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (isTakeover) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected = isTakeover,
                                onClick = { MobileConnectManager.setRemoteControlMode(RemoteControlMode.TAKEOVER) },
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "全面接管",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isTakeover) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "选歌、歌单、切音质直接调度在 TV 播放",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color =
                                if (isTakeover) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                        alpha = 0.8f,
                                    )
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }

                val isBrowse = remoteControlMode == RemoteControlMode.BROWSE
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable {
                                MobileConnectManager.setRemoteControlMode(RemoteControlMode.BROWSE)
                            },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isBrowse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (isBrowse) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected = isBrowse,
                                onClick = { MobileConnectManager.setRemoteControlMode(RemoteControlMode.BROWSE) },
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "浏览模式",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isBrowse) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "手机本地播放，点击接力或菜单时在 TV 播放",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color =
                                if (isBrowse) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                        alpha = 0.8f,
                                    )
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // 2. 本地静音开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地静音功能", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "开启后手机仅充当遥控板不发声，避免双端回声重叠",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = localMute,
                    onCheckedChange = { MobileConnectManager.setLocalMute(it) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // 3. TV 离线模式开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TV 离线中转模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        "开启后 TV 端无需直连公网，全部音频流与元数据经由手机代理中转",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = tvOfflineProxy,
                    onCheckedChange = { MobileConnectManager.setTvOfflineProxy(it) },
                )
            }
        }
    }
}
