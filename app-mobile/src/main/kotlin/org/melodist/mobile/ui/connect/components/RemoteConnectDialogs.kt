package org.melodist.mobile.ui.connect.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.DeviceType
import org.melodist.core.connect.model.PlayerStateEvent
import org.melodist.mobile.connect.MobileConnectManager
import org.melodist.mobile.ui.components.AudioQualityBottomSheet
import org.melodist.mobile.ui.connect.QrScannerDialog
import org.melodist.model.AudioQualityTier
import org.melodist.model.QualityOption

/**
 * 远程控制连接与配置相关弹窗集合组件（扫码配对、手动 IP 输入、设备 PIN 认证与远端音质切换）
 */
@Composable
fun RemoteConnectDialogs(
    showQrScanner: Boolean,
    onDismissQrScanner: () -> Unit,
    showManualInputDialog: Boolean,
    manualHostPrefill: String = "",
    onDismissManualInput: () -> Unit,
    onShowManualInputWithHost: (host: String) -> Unit,
    selectedDeviceForPin: ConnectDevice?,
    onDismissPinDialog: () -> Unit,
    showTvQualitySheet: Boolean,
    onDismissTvQualitySheet: () -> Unit,
    tvPlayerState: PlayerStateEvent?,
    tvProbedQualityOptions: List<QualityOption>,
    isTvProbingQuality: Boolean,
) {
    val context = LocalContext.current

    // 1. 扫码弹窗
    if (showQrScanner) {
        QrScannerDialog(
            onDismissRequest = onDismissQrScanner,
            onQrDecoded = { qrText ->
                val ok = MobileConnectManager.connectByQrJson(qrText)
                if (ok) {
                    Toast.makeText(context, "已识别电视配置，正在握手连接...", Toast.LENGTH_SHORT).show()
                } else {
                    val trimmed = qrText.trim()
                    if (trimmed.contains(".") && !trimmed.contains("{")) {
                        val host = trimmed.substringBefore(":")
                        onShowManualInputWithHost(host)
                    } else {
                        Toast.makeText(context, "二维码内容不符合配对规范", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    // 2. 手动输入配对 IP/端口弹窗
    if (showManualInputDialog) {
        var manualHost by remember(manualHostPrefill) { mutableStateOf(manualHostPrefill) }
        var manualPin by remember { mutableStateOf("") }
        val trimmedHost = manualHost.trim()
        val isManualPinValid = manualPin.isBlank() || manualPin.length == 6
        val isManualHostValid = trimmedHost.isNotBlank()

        AlertDialog(
            onDismissRequest = onDismissManualInput,
            title = { Text("手动连接电视") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("电视 IP 地址 (例如 192.168.1.100 或 192.168.1.100:8765)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = manualPin,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            if (digits.length <= 6) manualPin = digits
                        },
                        label = { Text("6位配对码 (选填)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = isManualHostValid && isManualPinValid,
                    onClick = {
                        if (isManualHostValid && isManualPinValid) {
                            val hostParts = trimmedHost.split(":")
                            val actualHost = hostParts.getOrNull(0)?.trim() ?: ""
                            val actualPort = hostParts.getOrNull(1)?.trim()?.toIntOrNull() ?: 8765
                            val dev = ConnectDevice(
                                id = "manual_${actualHost.replace(".", "_")}_$actualPort",
                                name = "Melodist TV ($actualHost)",
                                type = DeviceType.TV,
                                host = actualHost,
                                port = actualPort,
                            )
                            MobileConnectManager.connectTo(dev, manualPin.trim())
                            onDismissManualInput()
                        }
                    },
                ) {
                    Text("连接")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissManualInput) {
                    Text("取消")
                }
            },
        )
    }

    // 3. 针对局域网设备输入 PIN 弹窗
    selectedDeviceForPin?.let { dev ->
        var pinInput by remember { mutableStateOf("") }
        val isPinComplete = pinInput.length == 6

        AlertDialog(
            onDismissRequest = onDismissPinDialog,
            title = { Text("输入配对码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入电视屏幕上显示的 6 位数字配对码：", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            if (digits.length <= 6) pinInput = digits
                        },
                        label = { Text("6位数字配对码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = isPinComplete,
                    onClick = {
                        if (isPinComplete) {
                            MobileConnectManager.connectTo(dev, pinInput.trim())
                            onDismissPinDialog()
                        }
                    },
                ) {
                    Text("确定连接")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPinDialog) {
                    Text("取消")
                }
            },
        )
    }

    // 4. TV 端音质切换弹窗
    if (showTvQualitySheet) {
        val curSong = tvPlayerState?.currentSong
        val curTier = curSong?.currentTier ?: AudioQualityTier.SQ
        val availTiers = curSong?.availableTiers?.toSet() ?: emptySet()
        AudioQualityBottomSheet(
            currentTier = curTier,
            availableTiers = availTiers,
            probedQualityOptions = tvProbedQualityOptions,
            songDurationSec = curSong?.durationSeconds ?: 0,
            isProbing = isTvProbingQuality,
            targetSong = curSong,
            enforceCellularRestriction = false,
            onSelectTier = { selectedTier ->
                MobileConnectManager.tvSwitchTier(selectedTier)
                onDismissTvQualitySheet()
            },
            onDismissRequest = onDismissTvQualitySheet,
        )
    }
}
