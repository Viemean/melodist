package org.melodist.mobile.ui.connect.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.core.connect.model.ConnectDevice

/**
 * 局域网发现设备与记忆配对历史列表条目扩展
 */
fun LazyListScope.remoteDeviceListSection(
    discoveredDevices: List<ConnectDevice>,
    pairedDevices: List<ConnectDevice>,
    connectionState: MobileConnectionState,
    onRequestScanQr: () -> Unit,
    onRequestRefresh: () -> Unit,
    onRequestManualInput: () -> Unit,
    onSelectDeviceToConnect: (ConnectDevice) -> Unit,
    onConnectToPairedDevice: (ConnectDevice) -> Unit,
    onRemovePairedDevice: (deviceId: String) -> Unit,
) {
    // 1. 标题与操作栏
    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "局域网设备",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onRequestScanQr,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("扫码", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = onRequestRefresh,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("刷新", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = onRequestManualInput,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("手动", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }

    // 2. 扫描中或无设备占位
    if (discoveredDevices.isEmpty() && pairedDevices.isEmpty()) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "正在扫描局域网 TV 设备...\n请确保电视与手机处于相同 Wi-Fi 网络",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    // 3. 附近发现的设备列表
    if (discoveredDevices.isNotEmpty()) {
        item {
            Text(
                text = "发现附近设备 (${discoveredDevices.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        items(discoveredDevices, key = { it.id }) { device ->
            val isConnected = (connectionState as? MobileConnectionState.Paired)?.targetDevice?.id == device.id
            Surface(
                onClick = {
                    if (!isConnected) {
                        onSelectDeviceToConnect(device)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${device.host}:${device.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isConnected) {
                        Text("已连接", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    } else {
                        FilledTonalButton(
                            onClick = { onSelectDeviceToConnect(device) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("连接", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // 4. 已记忆配对历史设备列表
    if (pairedDevices.isNotEmpty()) {
        item {
            Text(
                text = "已记忆配对历史 (${pairedDevices.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(pairedDevices, key = { "paired_${it.id}" }) { device ->
            val liveDevice = discoveredDevices.find { it.id == device.id }
            val target = liveDevice ?: device
            val isConnected = (connectionState as? MobileConnectionState.Paired)?.targetDevice?.id == device.id

            Surface(
                onClick = {
                    if (!isConnected) {
                        onConnectToPairedDevice(target)
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = if (isConnected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Rounded.Tv else Icons.Rounded.History,
                            contentDescription = null,
                            tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text(
                                text = target.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Medium,
                            )
                            Text(
                                text = if (isConnected) {
                                    "当前已连接"
                                } else {
                                    "地址: ${target.host.ifBlank { "局域网已记忆" }}:${target.port}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!isConnected) {
                            FilledTonalButton(
                                onClick = { onConnectToPairedDevice(target) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text("连接", fontSize = 12.sp)
                            }
                        }
                        IconButton(
                            onClick = { onRemovePairedDevice(device.id) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "删除记录", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
