package org.melodist.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.melodist.model.Song

class PlaybackRemoteStateHolder {
    private val _isRemoteActive = MutableStateFlow(false)
    val isRemoteActive: StateFlow<Boolean> = _isRemoteActive.asStateFlow()

    private val _remoteDeviceName = MutableStateFlow<String?>(null)
    val remoteDeviceName: StateFlow<String?> = _remoteDeviceName.asStateFlow()

    private val _remotePrevSong = MutableStateFlow<Song?>(null)
    val remotePrevSong: StateFlow<Song?> = _remotePrevSong.asStateFlow()

    private val _remoteNextSong = MutableStateFlow<Song?>(null)
    val remoteNextSong: StateFlow<Song?> = _remoteNextSong.asStateFlow()

    @Volatile
    private var syncBasePositionMs: Long = 0L

    @Volatile
    private var syncBaseElapsedRealtimeMs: Long = 0L

    @Volatile
    private var syncIsPlaying: Boolean = false

    private fun currentMonotonicMs(): Long = System.nanoTime() / 1_000_000L

    fun setRemoteActive(active: Boolean, deviceName: String? = null) {
        _isRemoteActive.value = active
        _remoteDeviceName.value = deviceName
        if (!active) {
            syncIsPlaying = false
        }
    }

    fun updateSyncTimeline(positionMs: Long, isPlaying: Boolean) {
        syncBasePositionMs = positionMs
        syncBaseElapsedRealtimeMs = currentMonotonicMs()
        syncIsPlaying = isPlaying
    }

    fun getEstimatedPositionMs(durationMs: Long = 0L): Long? {
        if (!_isRemoteActive.value) return null
        if (!syncIsPlaying) return syncBasePositionMs
        val now = currentMonotonicMs()
        val elapsed = (now - syncBaseElapsedRealtimeMs).coerceAtLeast(0L)
        val estimated = syncBasePositionMs + elapsed
        return if (durationMs > 0L) estimated.coerceAtMost(durationMs) else estimated
    }

    fun setRemotePrevSong(song: Song?) {
        _remotePrevSong.value = song
    }

    fun setRemoteNextSong(song: Song?) {
        _remoteNextSong.value = song
    }

    fun clearRemotePlayback() {
        if (!_isRemoteActive.value) return
        _isRemoteActive.value = false
        _remoteDeviceName.value = null
        _remotePrevSong.value = null
        _remoteNextSong.value = null
        syncBasePositionMs = 0L
        syncBaseElapsedRealtimeMs = 0L
        syncIsPlaying = false
    }
}
