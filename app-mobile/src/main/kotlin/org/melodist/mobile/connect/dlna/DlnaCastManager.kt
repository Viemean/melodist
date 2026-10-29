package org.melodist.mobile.connect.dlna

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getPlayUrl
import org.melodist.data.WebDavManager
import org.melodist.data.pipeline.AudioMetadataPipeline
import org.melodist.mobile.connect.MobileConnectManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.io.File

object DlnaCastManager {
    private val _castingDevice = MutableStateFlow<DlnaDevice?>(null)
    val castingDevice: StateFlow<DlnaDevice?> = _castingDevice.asStateFlow()

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    val discoveredDevices: StateFlow<List<DlnaDevice>> = DlnaDiscoveryManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = DlnaDiscoveryManager.isScanning

    fun startScan() {
        DlnaDiscoveryManager.startScan()
    }

    fun stopScan() {
        DlnaDiscoveryManager.stopScan()
    }

    suspend fun cast(
        context: Context,
        song: Song,
        device: DlnaDevice,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    PlaybackManager.pause()
                }

                val streamUrl = resolveStreamUrl(song)
                if (streamUrl.isNullOrBlank()) {
                    Log.w("DlnaCastManager", "Failed to resolve stream URL for song: ${song.songMid}")
                    return@withContext false
                }

                val coverUrl = resolveCoverUrl(song)
                val mimeType = resolveMimeType(song, streamUrl)

                val uriSetOk = DlnaSoapClient.setAVTransportUri(device.controlUrl, streamUrl, coverUrl, song, mimeType)
                if (!uriSetOk) {
                    Log.w("DlnaCastManager", "SetAVTransportURI failed for device: ${device.name}")
                    return@withContext false
                }

                kotlinx.coroutines.delay(800)

                val playOk = DlnaSoapClient.play(device.controlUrl)
                if (playOk) {
                    _castingDevice.value = device
                    _isCasting.value = true
                    true
                } else {
                    Log.w("DlnaCastManager", "Play failed for device: ${device.name}")
                    false
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DlnaCastManager", "Cast to device ${device.name} failed", e)
                false
            }
        }

    suspend fun disconnect(): Boolean =
        withContext(Dispatchers.IO) {
            val device = _castingDevice.value ?: return@withContext true
            try {
                DlnaSoapClient.stop(device.controlUrl)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w("DlnaCastManager", "Stop failed during disconnect", e)
            } finally {
                _castingDevice.value = null
                _isCasting.value = false
            }
            true
        }

    private suspend fun resolveStreamUrl(song: Song): String? {
        val server = MobileConnectManager.audioStreamServer ?: return null

        val isTrueLocal = song.isLocal || song.songMid.startsWith("local_")
        val localFilePath =
            song.localFilePath?.takeIf { it.isNotBlank() }
                ?: if (isTrueLocal) {
                    org.melodist.data.LocalMusicManager
                        .getScannedSongs()
                        .find { it.songMid == song.songMid }
                        ?.localFilePath
                } else {
                    null
                }

        if (isTrueLocal && !localFilePath.isNullOrBlank() && File(localFilePath).exists()) {
            return server.buildLocalAudioStreamUrl(localFilePath)
        }

        if (song.isWebDav || song.songMid.startsWith("webdav_")) {
            val serverId = song.songMid.removePrefix("webdav_").substringBeforeLast('_')
            val href = song.mediaMid.ifBlank { song.localFilePath.orEmpty() }
            if (serverId.isNotBlank() && href.isNotBlank()) {
                return server.buildWebDavStreamUrl(serverId, href)
            }
        }

        val cachedPath = song.localFilePath?.takeIf { File(it).exists() }
        if (!cachedPath.isNullOrBlank()) {
            return server.buildLocalAudioStreamUrl(cachedPath)
        }

        if (song.mediaMid.startsWith("http://") || song.mediaMid.startsWith("https://")) {
            return server.buildProxyStreamUrl(song.mediaMid)
        }

        try {
            val api = MusicApiService()
            val playUrlInfo = api.getPlayUrl(song.songMid, mediaMid = song.effectiveMediaMid, preferredTier = AudioQualityTier.HQ)
            val remoteUrl = playUrlInfo.url
            if (!remoteUrl.isNullOrBlank()) {
                return server.buildProxyStreamUrl(remoteUrl)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("DlnaCastManager", "Failed to fetch online play URL for ${song.songMid}", e)
        }

        return null
    }

    private suspend fun resolveCoverUrl(song: Song): String? {
        val server = MobileConnectManager.audioStreamServer ?: return null
        val coverUrl = song.coverUrl
        if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
            return coverUrl
        }
        val cleanPath = if (coverUrl.startsWith("file://")) coverUrl.removePrefix("file://") else coverUrl
        if (cleanPath.isNotBlank()) {
            val file = File(cleanPath)
            if (file.exists() && file.length() > 0L) {
                return server.buildLocalCoverUrl(cleanPath)
            }
        }

        if (song.isWebDav || song.songMid.startsWith("webdav_")) {
            val serverId = song.songMid.removePrefix("webdav_").substringBeforeLast('_')
            val href = song.mediaMid.ifBlank { song.localFilePath.orEmpty() }
            val webDavServers = WebDavManager.getServers()
            val webDavServer =
                webDavServers.find { it.id == serverId }
                    ?: WebDavManager.getActiveServer()
                    ?: webDavServers.firstOrNull()
            if (webDavServer != null && href.isNotBlank()) {
                val cachedCover =
                    WebDavManager.getSongCoverPath(webDavServer.id, href)
                        ?: WebDavManager.getSongRawCoverPath(webDavServer.id, href)
                val cachedLocalPath = cachedCover?.removePrefix("file://")
                if (!cachedLocalPath.isNullOrBlank()) {
                    val file = File(cachedLocalPath)
                    if (file.exists() && file.length() > 0L) {
                        return server.buildLocalCoverUrl(cachedLocalPath)
                    }
                }

                try {
                    val meta = WebDavManager.extractPlaybackMetadata(webDavServer, song)
                    val extractedPath = (meta.rawCoverUrl ?: meta.coverUrl)?.removePrefix("file://")
                    if (!extractedPath.isNullOrBlank()) {
                        val file = File(extractedPath)
                        if (file.exists() && file.length() > 0L) {
                            return server.buildLocalCoverUrl(extractedPath)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("DlnaCastManager", "Failed to extract WebDAV cover for ${song.songMid}", e)
                }
            }
        }

        val localFilePath = song.localFilePath
        if (!localFilePath.isNullOrBlank()) {
            val localAudioFile = File(localFilePath)
            if (localAudioFile.exists()) {
                val companion = AudioMetadataPipeline.detectCompanionCover(localAudioFile)
                if (companion != null && companion.exists() && companion.length() > 0L) {
                    return server.buildLocalCoverUrl(companion.absolutePath)
                }
            }
        }

        return null
    }

    private fun resolveMimeType(
        song: Song,
        streamUrl: String,
    ): String {
        val pathOrUrl = song.localFilePath.orEmpty().ifBlank { streamUrl }.lowercase()
        return when {
            pathOrUrl.contains(".flac") -> "audio/flac"
            pathOrUrl.contains(".wav") -> "audio/wav"
            pathOrUrl.contains(".ogg") -> "audio/ogg"
            pathOrUrl.contains(".m4a") || pathOrUrl.contains(".aac") || pathOrUrl.contains(".mp4") -> "audio/mp4"
            pathOrUrl.contains(".opus") -> "audio/opus"
            else -> "audio/mpeg"
        }
    }
}
