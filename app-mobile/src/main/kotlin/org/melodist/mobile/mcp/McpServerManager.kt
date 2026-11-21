package org.melodist.mobile.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.melodist.core.connect.mcp.McpHttpServer
import org.melodist.core.connect.mcp.McpJsonRpcDispatcher
import org.melodist.data.AppSettingsManager
import org.melodist.playback.PlaybackManager

object McpServerManager {
    private const val TAG = "McpServerManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var server: McpHttpServer? = null
    private var appContext: Context? = null

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _isSuspendedNonWifi = MutableStateFlow(false)
    val isSuspendedNonWifi: StateFlow<Boolean> = _isSuspendedNonWifi.asStateFlow()

    private var isWifiConnected: Boolean = false

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        setupNetworkListener(app)

        scope.launch {
            AppSettingsManager.settings.collect { settings ->
                syncServerState(settings.enableMcpServer)
            }
        }
    }

    private fun setupNetworkListener(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        isWifiConnected = checkCurrentWifi(cm)

        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val isWifi = checkCurrentWifi(cm)
                    if (isWifi != isWifiConnected) {
                        isWifiConnected = isWifi
                        scope.launch {
                            syncServerState(AppSettingsManager.settings.value.enableMcpServer)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    val isWifi = checkCurrentWifi(cm)
                    if (isWifi != isWifiConnected) {
                        isWifiConnected = isWifi
                        scope.launch {
                            syncServerState(AppSettingsManager.settings.value.enableMcpServer)
                        }
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    val isWifi = checkCurrentWifi(cm)
                    if (isWifi != isWifiConnected) {
                        isWifiConnected = isWifi
                        scope.launch {
                            syncServerState(AppSettingsManager.settings.value.enableMcpServer)
                        }
                    }
                }
            },
        )
    }

    private fun checkCurrentWifi(cm: ConnectivityManager): Boolean {
        val activeNet = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNet) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun syncServerState(enabled: Boolean) {
        val ctx = appContext ?: return
        if (!enabled) {
            stopServerInternal()
            _isSuspendedNonWifi.value = false
            return
        }

        if (!isWifiConnected) {
            Log.i(TAG, "Non-Wi-Fi network detected, suspending MCP server")
            stopServerInternal()
            _isSuspendedNonWifi.value = true
            return
        }

        _isSuspendedNonWifi.value = false
        startServerInternal(ctx)
    }

    private fun startServerInternal(context: Context) {
        if (server?.isRunning() == true) return

        val playbackHandler = McpPlaybackHandlerImpl(context)
        val dispatcher =
            McpJsonRpcDispatcher(
                playbackHandler = playbackHandler,
                isDeleteAllowedProvider = { AppSettingsManager.settings.value.mcpAllowDelete },
                getCurrentPlayingMid = { PlaybackManager.currentSong.value?.songMid },
            )

        val targetPort = AppSettingsManager.settings.value.mcpServerPort
        val s =
            McpHttpServer(
                dispatcher = dispatcher,
                authTokenProvider = { AppSettingsManager.settings.value.mcpAuthToken },
                preferredPort = targetPort,
            )

        val boundPort = s.start()
        if (boundPort > 0) {
            server = s
            _isServerRunning.value = true
            _serverUrl.value = s.serverUrl
            Log.i(TAG, "McpHttpServer is active at ${s.serverUrl}")
        } else {
            _isServerRunning.value = false
            _serverUrl.value = ""
        }
    }

    private fun stopServerInternal() {
        server?.stop()
        server = null
        _isServerRunning.value = false
        _serverUrl.value = ""
    }

    fun restart() {
        val ctx = appContext ?: return
        stopServerInternal()
        if (AppSettingsManager.settings.value.enableMcpServer && isWifiConnected) {
            startServerInternal(ctx)
        }
    }
}
