package org.melodist.mobile.connect.dlna

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object DlnaDiscoveryManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scanJob: Job? = null

    private val _discoveredDevices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DlnaDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val httpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

    private val locationRegex = Pattern.compile("LOCATION:\\s*(http://[^\\r\\n]+)", Pattern.CASE_INSENSITIVE)
    private val friendlyNameRegex = Pattern.compile("<friendlyName>([^<]+)</friendlyName>", Pattern.CASE_INSENSITIVE)
    private val modelNameRegex = Pattern.compile("<modelName>([^<]+)</modelName>", Pattern.CASE_INSENSITIVE)
    private val manufacturerRegex = Pattern.compile("<manufacturer>([^<]+)</manufacturer>", Pattern.CASE_INSENSITIVE)
    private val udnRegex = Pattern.compile("<UDN>([^<]+)</UDN>", Pattern.CASE_INSENSITIVE)
    private val serviceBlockRegex = Pattern.compile("<service>[\\s\\S]*?</service>", Pattern.CASE_INSENSITIVE)
    private val controlUrlRegex = Pattern.compile("<controlURL>([^<]+)</controlURL>", Pattern.CASE_INSENSITIVE)
    private val urlBaseRegex = Pattern.compile("<URLBase>([^<]+)</URLBase>", Pattern.CASE_INSENSITIVE)

    fun startScan() {
        if (_isScanning.value) return
        scanJob?.cancel()
        scanJob =
            scope.launch {
                _isScanning.value = true
                try {
                    discoverRenderers()
                } finally {
                    _isScanning.value = false
                }
            }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    private suspend fun discoverRenderers() =
        withContext(Dispatchers.IO) {
            val ssdpDmrMsg =
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            val ssdpAvtMsg =
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n"

            val foundLocations = mutableSetOf<String>()
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket(0)
                socket.soTimeout = 2000
                socket.broadcast = true

                val targetGroup = InetAddress.getByName("239.255.255.250")
                val broadcastAddr = InetAddress.getByName("255.255.255.255")

                val dmrBytes = ssdpDmrMsg.toByteArray(Charsets.UTF_8)
                val avtBytes = ssdpAvtMsg.toByteArray(Charsets.UTF_8)

                socket.send(DatagramPacket(dmrBytes, dmrBytes.size, targetGroup, 1900))
                socket.send(DatagramPacket(dmrBytes, dmrBytes.size, broadcastAddr, 1900))
                socket.send(DatagramPacket(avtBytes, avtBytes.size, targetGroup, 1900))
                socket.send(DatagramPacket(avtBytes, avtBytes.size, broadcastAddr, 1900))

                val buffer = ByteArray(4096)
                val recvPacket = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        socket.receive(recvPacket)
                        val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                        val matcher = locationRegex.matcher(text)
                        if (matcher.find()) {
                            val loc = matcher.group(1)?.trim().orEmpty()
                            if (loc.isNotBlank()) {
                                foundLocations.add(loc)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DlnaDiscoveryManager", "SSDP discovery failed", e)
            } finally {
                socket?.close()
            }

            val validDevices = mutableListOf<DlnaDevice>()
            for (loc in foundLocations) {
                if (!isActive) break
                val device = parseDeviceDescription(loc) ?: continue
                if (validDevices.none { it.id == device.id || it.controlUrl == device.controlUrl }) {
                    validDevices.add(device)
                }
            }
            _discoveredDevices.value = validDevices
        }

    private fun parseDeviceDescription(location: String): DlnaDevice? {
        val request =
            Request
                .Builder()
                .url(location)
                .header("User-Agent", "DLNADOC/1.50")
                .build()

        return try {
            val xml =
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.string().orEmpty()
                }

            if (xml.isBlank()) return null
            if (xml.contains("urn:schemas-tencent-com:service:QPlay", ignoreCase = true) ||
                xml.contains("QPlay", ignoreCase = true)
            ) {
                return null
            }
            if (!xml.contains("AVTransport:1", ignoreCase = true)) {
                return null
            }

            val friendlyName = extractFirstMatch(friendlyNameRegex, xml) ?: "DLNA 电视"
            if (friendlyName.contains("QQ音乐", ignoreCase = true) || friendlyName.contains("QQMusic", ignoreCase = true)) {
                return null
            }
            val modelName = extractFirstMatch(modelNameRegex, xml).orEmpty()
            val manufacturer = extractFirstMatch(manufacturerRegex, xml).orEmpty()
            val udn = extractFirstMatch(udnRegex, xml) ?: location

            val serviceMatcher = serviceBlockRegex.matcher(xml)
            var controlUrl: String? = null
            while (serviceMatcher.find()) {
                val serviceBlock = serviceMatcher.group()
                if (serviceBlock.contains("AVTransport:1", ignoreCase = true)) {
                    val cuMatcher = controlUrlRegex.matcher(serviceBlock)
                    if (cuMatcher.find()) {
                        val rawCu = cuMatcher.group(1)?.trim().orEmpty()
                        controlUrl = resolveControlUrl(location, rawCu, xml)
                        break
                    }
                }
            }

            val validControlUrl = controlUrl ?: return null
            DlnaDevice(
                id = udn,
                name = friendlyName,
                location = location,
                controlUrl = validControlUrl,
                modelName = modelName,
                manufacturer = manufacturer,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("DlnaDiscoveryManager", "Failed to fetch device description from $location", e)
            null
        }
    }

    private fun resolveControlUrl(
        location: String,
        rawCu: String,
        xml: String,
    ): String {
        if (rawCu.startsWith("http://", ignoreCase = true) || rawCu.startsWith("https://", ignoreCase = true)) {
            return rawCu
        }
        val urlBase = extractFirstMatch(urlBaseRegex, xml)?.takeIf { it.isNotBlank() }
        val baseLoc = urlBase ?: location
        val uri = URI(baseLoc)
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val hostPart = "${uri.scheme}://${uri.host}$portPart"

        return if (rawCu.startsWith("/")) {
            "$hostPart$rawCu"
        } else {
            val basePath = uri.path?.takeIf { it.isNotBlank() }?.let { if (it.endsWith("/")) it else "$it/" } ?: "/"
            "$hostPart$basePath$rawCu"
        }
    }

    private fun extractFirstMatch(
        pattern: Pattern,
        text: String,
    ): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }
}
