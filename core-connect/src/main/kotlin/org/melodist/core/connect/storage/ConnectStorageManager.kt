package org.melodist.core.connect.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.DeviceType
import java.util.UUID

class ConnectStorageManager(
    private val context: Context,
    private val defaultDeviceType: DeviceType,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val _pairedDevicesFlow = MutableStateFlow<List<ConnectDevice>>(loadPairedDevices())
    val pairedDevicesFlow: StateFlow<List<ConnectDevice>> = _pairedDevicesFlow.asStateFlow()

    private val _localMuteFlow = MutableStateFlow(prefs.getBoolean(KEY_LOCAL_MUTE, false))
    val localMuteFlow: StateFlow<Boolean> = _localMuteFlow.asStateFlow()

    private val _tvOfflineProxyFlow = MutableStateFlow(prefs.getBoolean(KEY_TV_OFFLINE_PROXY, false))
    val tvOfflineProxyFlow: StateFlow<Boolean> = _tvOfflineProxyFlow.asStateFlow()

    private val _remoteControlModeFlow = MutableStateFlow(loadRemoteControlMode())
    val remoteControlModeFlow: StateFlow<org.melodist.core.connect.model.RemoteControlMode> = _remoteControlModeFlow.asStateFlow()

    var customTvIp: String?
        get() = prefs.getString(KEY_CUSTOM_TV_IP, null)
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove(KEY_CUSTOM_TV_IP).apply()
            } else {
                prefs.edit().putString(KEY_CUSTOM_TV_IP, value).apply()
            }
        }

    fun getOrCreateLocalDevice(fallbackHost: String = ""): ConnectDevice {
        var id = prefs.getString(KEY_LOCAL_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_LOCAL_DEVICE_ID, id).apply()
        }

        var name = prefs.getString(KEY_LOCAL_DEVICE_NAME, null)
        if (name.isNullOrBlank()) {
            name =
                if (defaultDeviceType == DeviceType.TV) {
                    "Melodist TV (${Build.MODEL})"
                } else {
                    "Melodist Mobile (${Build.MODEL})"
                }
            prefs.edit().putString(KEY_LOCAL_DEVICE_NAME, name).apply()
        }

        var token = prefs.getString(KEY_LOCAL_DEVICE_TOKEN, null)
        if (token.isNullOrBlank()) {
            token = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_LOCAL_DEVICE_TOKEN, token).apply()
        }

        return ConnectDevice(
            id = id,
            name = name,
            type = defaultDeviceType,
            host = fallbackHost,
            token = token,
        )
    }

    fun isDevicePaired(deviceId: String): Boolean = _pairedDevicesFlow.value.any { it.id == deviceId }

    fun isTokenTrusted(token: String): Boolean {
        if (token.isBlank()) return false
        return _pairedDevicesFlow.value.any { it.token == token }
    }

    fun savePairedDevice(device: ConnectDevice) {
        val current = _pairedDevicesFlow.value.toMutableList()
        current.removeAll { it.id == device.id }
        current.add(device)
        persistDevices(current)
    }

    fun removePairedDevice(deviceId: String) {
        val current = _pairedDevicesFlow.value.toMutableList()
        current.removeAll { it.id == deviceId }
        persistDevices(current)
    }

    fun clearAllPairedDevices() {
        persistDevices(emptyList())
    }

    fun setLocalMute(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_MUTE, enabled).apply()
        _localMuteFlow.value = enabled
    }

    fun setTvOfflineProxy(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TV_OFFLINE_PROXY, enabled).apply()
        _tvOfflineProxyFlow.value = enabled
    }

    fun setRemoteControlMode(mode: org.melodist.core.connect.model.RemoteControlMode) {
        prefs.edit().putString(KEY_REMOTE_CONTROL_MODE, mode.name).apply()
        _remoteControlModeFlow.value = mode
    }

    private fun loadRemoteControlMode(): org.melodist.core.connect.model.RemoteControlMode {
        val raw = prefs.getString(KEY_REMOTE_CONTROL_MODE, null) ?: return org.melodist.core.connect.model.RemoteControlMode.BROWSE
        return try {
            org.melodist.core.connect.model.RemoteControlMode
                .valueOf(raw)
        } catch (_: Exception) {
            org.melodist.core.connect.model.RemoteControlMode.BROWSE
        }
    }

    private fun loadPairedDevices(): List<ConnectDevice> {
        val raw = prefs.getString(KEY_PAIRED_DEVICES, null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistDevices(devices: List<ConnectDevice>) {
        val raw = json.encodeToString(devices)
        prefs.edit().putString(KEY_PAIRED_DEVICES, raw).apply()
        _pairedDevicesFlow.value = devices
    }

    fun getLastConnectedDevice(): ConnectDevice? {
        val raw = prefs.getString(KEY_LAST_CONNECTED_DEVICE, null)
        if (!raw.isNullOrBlank()) {
            try {
                return json.decodeFromString<ConnectDevice>(raw)
            } catch (_: Exception) {
            }
        }
        return _pairedDevicesFlow.value.lastOrNull()
    }

    fun setLastConnectedDevice(device: ConnectDevice) {
        val raw = json.encodeToString(device)
        prefs.edit().putString(KEY_LAST_CONNECTED_DEVICE, raw).apply()
    }

    companion object {
        private const val PREFS_NAME = "melodist_connect_prefs"
        private const val KEY_LOCAL_DEVICE_ID = "local_device_id"
        private const val KEY_LOCAL_DEVICE_NAME = "local_device_name"
        private const val KEY_LOCAL_DEVICE_TOKEN = "local_device_token"
        private const val KEY_PAIRED_DEVICES = "paired_devices_json"
        private const val KEY_LOCAL_MUTE = "local_mute"
        private const val KEY_TV_OFFLINE_PROXY = "tv_offline_proxy"
        private const val KEY_REMOTE_CONTROL_MODE = "remote_control_mode"
        private const val KEY_CUSTOM_TV_IP = "custom_tv_ip"
        private const val KEY_LAST_CONNECTED_DEVICE = "last_connected_device"
    }
}
