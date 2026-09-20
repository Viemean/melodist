package org.melodist.core.connect.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.melodist.core.connect.model.ConnectDevice
import org.melodist.core.connect.model.DeviceType

class ConnectNsdHelper(
    private val context: Context,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ConnectDevice>> = _discoveredDevices.asStateFlow()

    fun registerTvService(
        port: Int,
        device: ConnectDevice,
        pinCode: String,
        onRegistered: (String) -> Unit = {},
    ) {
        unregisterService()
        val serviceInfo =
            NsdServiceInfo().apply {
                serviceName = "Melodist-TV-${device.id.takeLast(4)}"
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("id", device.id)
                setAttribute("name", device.name)
                setAttribute("token", device.token)
                setAttribute("pin", pinCode)
                if (device.host.isNotBlank() && device.host != "127.0.0.1") {
                    setAttribute("host", device.host)
                }
            }

        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    android.util.Log.i("MelodistNsd", "TV Service registered: ${info.serviceName}, host: ${device.host}:$port")
                    onRegistered(info.serviceName)
                }

                override fun onRegistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    android.util.Log.e("MelodistNsd", "TV Service register failed, errorCode: $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    android.util.Log.i("MelodistNsd", "TV Service unregistered: ${info.serviceName}")
                }

                override fun onUnregistrationFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {}
            }
        registrationListener = listener
        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            android.util.Log.e("MelodistNsd", "registerService exception: ${e.message}", e)
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (_: Exception) {
            }
            registrationListener = null
        }
    }

    fun startDiscovery() {
        stopDiscovery()
        _discoveredDevices.value = emptyList()

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    android.util.Log.e("MelodistNsd", "Discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {}

                override fun onDiscoveryStarted(serviceType: String) {
                    android.util.Log.i("MelodistNsd", "Discovery started for $serviceType")
                }

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    android.util.Log.i("MelodistNsd", "Service found: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}")
                    if (serviceInfo.serviceType.contains("melodist-connect")) {
                        resolveService(serviceInfo)
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val serviceName = serviceInfo.serviceName
                    android.util.Log.i("MelodistNsd", "Service lost: $serviceName")
                    val current = _discoveredDevices.value.toMutableList()
                    current.removeAll { it.name == serviceName || it.id.endsWith(serviceName.takeLast(4)) }
                    _discoveredDevices.value = current
                }
            }
        discoveryListener = listener
        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            android.util.Log.e("MelodistNsd", "discoverServices exception: ${e.message}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        android.util.Log.e("MelodistNsd", "Resolve failed for ${serviceInfo.serviceName}, code: $errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val rawHost = resolved.host?.hostAddress ?: return
                        val port = resolved.port
                        val attributes = resolved.attributes

                        val declaredHost = attributes["host"]?.let { String(it, Charsets.UTF_8) }?.trim().orEmpty()
                        val host =
                            if (declaredHost.isNotBlank() && declaredHost != "10.0.2.15" && declaredHost != "127.0.0.1") {
                                declaredHost
                            } else {
                                rawHost
                            }

                        val id =
                            attributes["id"]?.let { String(it, Charsets.UTF_8) }
                                ?: resolved.serviceName
                        val name =
                            attributes["name"]?.let { String(it, Charsets.UTF_8) }
                                ?: resolved.serviceName
                        val token = attributes["token"]?.let { String(it, Charsets.UTF_8) } ?: ""

                        android.util.Log.i("MelodistNsd", "Service resolved: $name -> $host:$port (rawHost: $rawHost, declared: $declaredHost)")

                        val device =
                            ConnectDevice(
                                id = id,
                                name = name,
                                type = DeviceType.TV,
                                host = host,
                                port = port,
                                token = token,
                            )

                        val current = _discoveredDevices.value.toMutableList()
                        current.removeAll { it.id == device.id }
                        current.add(device)
                        _discoveredDevices.value = current
                    }
                },
            )
        } catch (e: Exception) {
            android.util.Log.w("MelodistNsd", "Failed to start service discovery", e)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager?.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
            discoveryListener = null
        }
    }

    companion object {
        const val SERVICE_TYPE = "_melodist-connect._tcp."
    }
}
