package org.melodist.playback

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.probeSongQualities
import org.melodist.model.QualityOption
import org.melodist.data.AppSettingsManager
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

class AudioQualityCoordinator(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
) {
    private val _availableTiers = MutableStateFlow<Set<AudioQualityTier>>(emptySet())
    val availableTiers: StateFlow<Set<AudioQualityTier>> = _availableTiers.asStateFlow()

    private val _probedQualityOptions = MutableStateFlow<List<QualityOption>>(emptyList())
    val probedQualityOptions: StateFlow<List<QualityOption>> = _probedQualityOptions.asStateFlow()

    private val _isProbingQuality = MutableStateFlow(false)
    val isProbingQuality: StateFlow<Boolean> = _isProbingQuality.asStateFlow()

    var probedSongMid: String? = null
        private set

    private var probeJob: Job? = null

    fun resetForSong(song: Song) {
        probeJob?.cancel()
        val isLocalOrWebDav = PlaybackSourceResolver.isLocalOrWebDavSong(song)
        if (isLocalOrWebDav) {
            val actualTier = song.currentTier
            _availableTiers.value = setOf(actualTier)
            val localPath = song.localFilePath
            val localSize =
                if (!localPath.isNullOrBlank()) {
                    try {
                        java.io.File(localPath).length()
                    } catch (_: Exception) {
                        0L
                    }
                } else {
                    0L
                }
            _probedQualityOptions.value =
                listOf(
                    QualityOption(
                        tier = actualTier,
                        format = localPath?.substringAfterLast('.', "")?.uppercase()?.ifBlank { "FLAC" } ?: "FLAC",
                        bitrate = "",
                        sizeBytes = localSize,
                        isAvailable = true,
                    ),
                )
            probedSongMid = song.songMid
        } else {
            _availableTiers.value = emptySet()
            _probedQualityOptions.value = emptyList()
            probedSongMid = null
        }
    }

    fun launchProbeJob(
        song: Song,
        delayMs: Long,
        currentSongMidProvider: () -> String?,
        currentTierProvider: () -> AudioQualityTier,
        preferredTierProvider: () -> AudioQualityTier,
        context: Context?,
        onTrackSpecBitrateCalculated: ((Int) -> Unit)? = null,
        onAutoUpgrade: ((AudioQualityTier) -> Unit)? = null,
    ) {
        probeJob?.cancel()
        probeJob =
            scope.launch(Dispatchers.IO) {
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                _isProbingQuality.value = true
                try {
                    val probed = apiService.probeSongQualities(song.songMid, song.mediaMid)
                    val available = probed.filter { it.isAvailable }.map { it.tier }.toSet()
                    if (available.isNotEmpty()) {
                        _availableTiers.value = available
                        _probedQualityOptions.value = probed
                        probedSongMid = song.songMid

                        if (song.durationSeconds > 0 && onTrackSpecBitrateCalculated != null) {
                            val curSize = probed.find { it.tier == currentTierProvider() }?.sizeBytes ?: 0L
                            if (curSize > 0L) {
                                val calcKbps = ((curSize * 8L) / 1024L / song.durationSeconds).toInt()
                                if (calcKbps > 0) {
                                    onTrackSpecBitrateCalculated(calcKbps)
                                }
                            }
                        }

                        val preferred = preferredTierProvider()
                        val effective = clampCellularTier(preferred, song, context)
                        if (currentTierProvider() != effective && available.contains(effective) && currentSongMidProvider() == song.songMid) {
                            Log.i("AudioQualityCoordinator", "Auto-upgrading to preferred tier ${AudioQualityTier.getBadge(effective)} after probe for ${song.name}")
                            onAutoUpgrade?.invoke(effective)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AudioQualityCoordinator", "Probe qualities failed", e)
                } finally {
                    _isProbingQuality.value = false
                }
            }
    }

    fun ensureQualityProbed(
        song: Song?,
        currentSongMidProvider: () -> String?,
        currentTierProvider: () -> AudioQualityTier,
        preferredTierProvider: () -> AudioQualityTier,
        context: Context?,
        onAutoUpgrade: ((AudioQualityTier) -> Unit)? = null,
    ) {
        if (song == null) return
        val isLocalOrWebDav = song.songMid.startsWith("webdav_") || !song.localFilePath.isNullOrBlank()
        if (isLocalOrWebDav) return
        if (probedSongMid == song.songMid && _probedQualityOptions.value.isNotEmpty()) return
        probeJob?.cancel()
        launchProbeJob(
            song = song,
            delayMs = 0L,
            currentSongMidProvider = currentSongMidProvider,
            currentTierProvider = currentTierProvider,
            preferredTierProvider = preferredTierProvider,
            context = context,
            onAutoUpgrade = onAutoUpgrade,
        )
    }

    fun clampCellularTier(
        requestedTier: AudioQualityTier,
        song: Song?,
        context: Context?,
    ): AudioQualityTier =
        PlaybackSourceResolver.clampCellularTier(
            requestedTier = requestedTier,
            song = song,
            context = context,
            cellularLimit = AppSettingsManager.settings.value.cellularQualityTier,
        )

    fun getFallbackTier(current: AudioQualityTier): AudioQualityTier? =
        PlaybackSourceResolver.getFallbackTier(current)

    fun updateAvailableTiers(tiers: Set<AudioQualityTier>) {
        _availableTiers.value = tiers
    }

    fun cancel() {
        probeJob?.cancel()
    }
}
