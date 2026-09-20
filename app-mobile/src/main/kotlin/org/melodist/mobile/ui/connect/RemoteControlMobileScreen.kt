package org.melodist.mobile.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.probeSongQualities
import org.melodist.core.connect.client.MobileConnectionState
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.mobile.connect.MobileConnectManager
import org.melodist.mobile.ui.connect.components.RemoteConnectDialogs
import org.melodist.mobile.ui.connect.components.RemotePlaybackControlCard
import org.melodist.mobile.ui.connect.components.RemotePreferencesCard
import org.melodist.mobile.ui.connect.components.remoteDeviceListSection
import org.melodist.model.QualityOption

/**
 * 移动端投屏互联与电视遥控器界面
 */
@Composable
fun RemoteControlMobileScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val connectionState by MobileConnectManager.connectionState.collectAsState()
    val discoveredDevices by MobileConnectManager.discoveredDevices.collectAsState()
    val pairedDevices by MobileConnectManager.pairedDevices.collectAsState()
    val localMute by MobileConnectManager.localMute.collectAsState()
    val tvOfflineProxy by MobileConnectManager.tvOfflineProxy.collectAsState()
    val remoteControlMode by MobileConnectManager.remoteControlMode.collectAsState()
    val tvPlayerState by MobileConnectManager.tvPlayerState.collectAsState()

    var showQrScanner by remember { mutableStateOf(false) }
    var showManualInputDialog by remember { mutableStateOf(false) }
    var showTvQualitySheet by remember { mutableStateOf(false) }
    var manualHostPrefill by remember { mutableStateOf("") }
    var selectedDeviceForPin by remember { mutableStateOf<ConnectDevice?>(null) }

    var tvProbedQualityOptions by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var isTvProbingQuality by remember { mutableStateOf(false) }

    val curTvSong = tvPlayerState?.currentSong
    LaunchedEffect(showTvQualitySheet, curTvSong?.songMid) {
        if (showTvQualitySheet && curTvSong != null) {
            val isLocalOrWebDav = curTvSong.isLocal || curTvSong.isWebDav
            if (!isLocalOrWebDav) {
                isTvProbingQuality = true
                try {
                    val probed = withContext<List<QualityOption>>(Dispatchers.IO) {
                        org.melodist.api.MusicApiService().probeSongQualities(curTvSong.songMid, curTvSong.mediaMid)
                    }
                    tvProbedQualityOptions = probed
                } catch (_: Exception) {
                } finally {
                    isTvProbingQuality = false
                }
            } else {
                tvProbedQualityOptions = emptyList()
                isTvProbingQuality = false
            }
        }
    }

    LaunchedEffect(Unit) {
        MobileConnectManager.startDiscovery()
    }

    // 弹窗集合（扫码配对、手动 IP 输入、设备 PIN 认证与远端音质切换）
    RemoteConnectDialogs(
        showQrScanner = showQrScanner,
        onDismissQrScanner = { showQrScanner = false },
        showManualInputDialog = showManualInputDialog,
        manualHostPrefill = manualHostPrefill,
        onDismissManualInput = { showManualInputDialog = false },
        onShowManualInputWithHost = { host ->
            manualHostPrefill = host
            showManualInputDialog = true
        },
        selectedDeviceForPin = selectedDeviceForPin,
        onDismissPinDialog = { selectedDeviceForPin = null },
        showTvQualitySheet = showTvQualitySheet,
        onDismissTvQualitySheet = { showTvQualitySheet = false },
        tvPlayerState = tvPlayerState,
        tvProbedQualityOptions = tvProbedQualityOptions,
        isTvProbingQuality = isTvProbingQuality,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. TV 远程控制主卡片
        item {
            RemotePlaybackControlCard(
                connectionState = connectionState,
                tvPlayerState = tvPlayerState,
                onRequestScanQr = { showQrScanner = true },
                onRequestManualInput = { showManualInputDialog = true },
                onRequestTvQualitySheet = { showTvQualitySheet = true },
            )
        }

        // 2. 互联偏好设置卡片
        item {
            RemotePreferencesCard(
                remoteControlMode = remoteControlMode,
                localMute = localMute,
                tvOfflineProxy = tvOfflineProxy,
            )
        }

        // 3. 局域网设备与历史设备列表
        remoteDeviceListSection(
            discoveredDevices = discoveredDevices,
            pairedDevices = pairedDevices,
            connectionState = connectionState,
            onRequestScanQr = { showQrScanner = true },
            onRequestRefresh = { MobileConnectManager.startDiscovery() },
            onRequestManualInput = { showManualInputDialog = true },
            onSelectDeviceToConnect = { device ->
                selectedDeviceForPin = device
            },
            onConnectToPairedDevice = { device ->
                MobileConnectManager.connectTo(device)
            },
            onRemovePairedDevice = { deviceId ->
                MobileConnectManager.removePairedDevice(deviceId)
            },
        )
    }
}
