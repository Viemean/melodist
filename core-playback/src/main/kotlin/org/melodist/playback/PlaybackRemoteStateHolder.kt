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

    fun setRemoteActive(active: Boolean, deviceName: String? = null) {
        _isRemoteActive.value = active
        _remoteDeviceName.value = deviceName
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
    }
}
