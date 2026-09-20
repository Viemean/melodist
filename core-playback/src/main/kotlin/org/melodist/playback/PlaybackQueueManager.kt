package org.melodist.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getGuessRecommendSongs
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

class PlaybackQueueManager(
    private val scope: CoroutineScope,
    private val apiService: MusicApiService,
    private val onStateChanged: () -> Unit,
    private val onPlaySongRequest: (song: Song, forceTier: AudioQualityTier?, seekToMs: Long) -> Unit,
    private val onStopPlaybackRequest: () -> Unit,
) {
    private val _playlist = MutableStateFlow<List<Song>>(emptyList())
    val playlist: StateFlow<List<Song>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _loopMode = MutableStateFlow(PlaybackLoopMode.ListRepeat)
    val loopMode: StateFlow<PlaybackLoopMode> = _loopMode.asStateFlow()

    private val _isRadioMode = MutableStateFlow(false)
    val isRadioMode: StateFlow<Boolean> = _isRadioMode.asStateFlow()

    private val _paginationSource = MutableStateFlow<QueuePaginationSource?>(null)
    val paginationSource: StateFlow<QueuePaginationSource?> = _paginationSource.asStateFlow()

    private val _isLoadingMoreForQueue = MutableStateFlow(false)
    val isLoadingMoreForQueue: StateFlow<Boolean> = _isLoadingMoreForQueue.asStateFlow()

    private val _queueTag = MutableStateFlow<String?>(null)
    val queueTag: StateFlow<String?> = _queueTag.asStateFlow()

    val shuffleQueue = ShuffleQueueManager()

    private var pendingNextSongMid: String? = null
    val hasPendingNextPlay: Boolean get() = pendingNextSongMid != null

    @Volatile
    private var isFetchingMoreRadio = false

    fun restoreState(
        playlist: List<Song>,
        currentIndex: Int,
        loopMode: PlaybackLoopMode,
        isRadioMode: Boolean,
        shuffledIndices: String?,
        shuffledPointer: Int,
    ) {
        _playlist.value = playlist
        _currentIndex.value = currentIndex
        _loopMode.value = loopMode
        _isRadioMode.value = isRadioMode
        shuffleQueue.restore(shuffledIndices, shuffledPointer, playlist.size)
    }

    fun setLoopMode(mode: PlaybackLoopMode) {
        val oldMode = _loopMode.value
        _loopMode.value = mode
        if (mode == PlaybackLoopMode.Shuffle && (oldMode != PlaybackLoopMode.Shuffle || shuffleQueue.shuffledIndices.isEmpty())) {
            val list = _playlist.value
            if (list.isNotEmpty()) {
                shuffleQueue.reset(list.size, _currentIndex.value.coerceAtLeast(0), list)
            }
        }
        onStateChanged()
    }

    fun setRadioMode(isRadio: Boolean) {
        _isRadioMode.value = isRadio
    }

    fun setCurrentIndex(index: Int) {
        pendingNextSongMid = null
        _currentIndex.value = index
        if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
            val list = _playlist.value
            if (list.isNotEmpty() && index in list.indices) {
                shuffleQueue.syncTo(index, list.size, list)
            }
        }
        checkPrefetchQueueNextPage()
    }

    fun setPlaylist(
        songs: List<Song>,
        startIndex: Int = 0,
        isRadio: Boolean = false,
        initialSeekToMs: Long = 0L,
        forceTier: AudioQualityTier? = null,
        paginationSource: QueuePaginationSource? = null,
        queueTag: String? = null,
    ) {
        _isRadioMode.value = isRadio
        _paginationSource.value = paginationSource
        _queueTag.value = queueTag
        _playlist.value = songs
        pendingNextSongMid = null
        if (songs.isNotEmpty() && startIndex in songs.indices) {
            _currentIndex.value = startIndex
            if (!isRadio && _loopMode.value == PlaybackLoopMode.Shuffle) {
                shuffleQueue.reset(songs.size, startIndex, songs)
            }
            onPlaySongRequest(songs[startIndex], forceTier, initialSeekToMs)
        }
        checkPrefetchQueueNextPage()
        onStateChanged()
    }

    fun setPaginationSource(source: QueuePaginationSource?) {
        _paginationSource.value = source
        checkPrefetchQueueNextPage()
    }

    fun checkPrefetchQueueNextPage() {
        if (_isRadioMode.value) return
        val source = _paginationSource.value ?: return
        if (!source.hasMore || source.isLoadingMore || _isLoadingMoreForQueue.value) return
        val currentList = _playlist.value
        if (currentList.isNotEmpty() && _currentIndex.value >= currentList.size - 3) {
            scope.launch(Dispatchers.IO) {
                loadMoreForQueue()
            }
        }
    }

    suspend fun loadMoreForQueue(): Boolean {
        val source = _paginationSource.value ?: return false
        if (!source.hasMore || source.isLoadingMore || _isLoadingMoreForQueue.value) return false
        _isLoadingMoreForQueue.value = true
        return try {
            val newSongs = source.loadMore()
            if (newSongs.isNotEmpty()) {
                appendPlaylist(newSongs)
                true
            } else {
                false
            }
        } finally {
            _isLoadingMoreForQueue.value = false
        }
    }

    fun appendPlaylist(newSongs: List<Song>, targetTag: String? = null) {
        if (newSongs.isEmpty()) return
        if (targetTag != null && _queueTag.value != targetTag) return
        val current = _playlist.value
        val existingMids = current.map { it.songMid }.toSet()
        val toAdd = newSongs.filter { it.songMid.isNotBlank() && !existingMids.contains(it.songMid) }
        if (toAdd.isNotEmpty()) {
            val updated = current + toAdd
            _playlist.value = updated
            if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
                shuffleQueue.syncTo(_currentIndex.value, updated.size, updated)
            }
            onStateChanged()
        }
    }

    fun insertNextPlay(song: Song) {
        if (song.songMid.isBlank()) return
        val current = _playlist.value.toMutableList()
        if (current.isEmpty()) {
            setPlaylist(listOf(song), startIndex = 0)
            return
        }
        val curIdx = _currentIndex.value
        val existingIndex = current.indexOfFirst { it.songMid == song.songMid }
        if (existingIndex == curIdx && curIdx != -1) {
            pendingNextSongMid = null
            return
        }
        val targetInsertPos = (curIdx + 1).coerceIn(0, current.size)
        if (existingIndex != -1) {
            current.removeAt(existingIndex)
            val adjustedPos = if (existingIndex < targetInsertPos) (targetInsertPos - 1).coerceAtLeast(0) else targetInsertPos
            current.add(adjustedPos, song)
            if (existingIndex < curIdx) {
                _currentIndex.value = curIdx - 1
            }
        } else {
            current.add(targetInsertPos, song)
        }
        _playlist.value = current
        pendingNextSongMid = song.songMid

        if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
            val currentPlayingIdx = _currentIndex.value
            shuffleQueue.syncTo(currentPlayingIdx, current.size, current)
            val targetIdx = current.indexOfFirst { it.songMid == song.songMid }
            if (targetIdx != -1) {
                shuffleQueue.promoteToNext(targetIdx)
            }
        }
        onStateChanged()
    }

    fun insertAndPlay(
        song: Song,
        seekToMs: Long = 0L,
    ) {
        pendingNextSongMid = null
        if (song.songMid.isBlank()) return
        val current = _playlist.value.toMutableList()
        if (current.isEmpty()) {
            setPlaylist(listOf(song), startIndex = 0, initialSeekToMs = seekToMs)
            return
        }
        val existingIndex = current.indexOfFirst { it.songMid == song.songMid }
        if (existingIndex != -1) {
            _currentIndex.value = existingIndex
            onPlaySongRequest(current[existingIndex], null, seekToMs)
        } else {
            val curIdx = _currentIndex.value
            val insertPos = (curIdx + 1).coerceIn(0, current.size)
            current.add(insertPos, song)
            _playlist.value = current
            _currentIndex.value = insertPos
            onPlaySongRequest(song, null, seekToMs)
        }
    }

    fun removeFromPlaylist(index: Int) {
        val list = _playlist.value.toMutableList()
        if (index !in list.indices) return
        if (list[index].songMid == pendingNextSongMid) {
            pendingNextSongMid = null
        }
        val isCurrent = index == _currentIndex.value
        list.removeAt(index)
        _playlist.value = list
        if (list.isEmpty()) {
            _currentIndex.value = -1
            onStopPlaybackRequest()
        } else if (isCurrent) {
            val nextIndex = index.coerceAtMost(list.lastIndex)
            _currentIndex.value = nextIndex
            onPlaySongRequest(list[nextIndex], null, 0L)
        } else if (index < _currentIndex.value) {
            _currentIndex.value = _currentIndex.value - 1
        }
        if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
            shuffleQueue.syncTo(_currentIndex.value, list.size, list)
        }
        onStateChanged()
    }

    fun removeFromPlaylist(songs: List<Song>, currentPlayingMid: String?) {
        if (songs.isEmpty()) return
        val current = _playlist.value.toMutableList()
        val removeMids = songs.map { it.songMid }.toSet()
        if (pendingNextSongMid != null && removeMids.contains(pendingNextSongMid)) {
            pendingNextSongMid = null
        }
        val isCurrentRemoved = currentPlayingMid != null && removeMids.contains(currentPlayingMid)

        val remaining = current.filterNot { removeMids.contains(it.songMid) }
        _playlist.value = remaining
        if (remaining.isEmpty()) {
            _currentIndex.value = -1
            onStopPlaybackRequest()
        } else if (isCurrentRemoved) {
            val newIndex = 0
            _currentIndex.value = newIndex
            onPlaySongRequest(remaining[newIndex], null, 0L)
        } else {
            val newIdx = remaining.indexOfFirst { it.songMid == currentPlayingMid }
            _currentIndex.value = if (newIdx != -1) newIdx else 0
        }
        if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
            shuffleQueue.syncTo(_currentIndex.value, remaining.size, remaining)
        }
        onStateChanged()
    }

    fun clearPlaylist() {
        pendingNextSongMid = null
        _paginationSource.value = null
        _queueTag.value = null
        _playlist.value = emptyList()
        _currentIndex.value = -1
        onStopPlaybackRequest()
        onStateChanged()
    }

    fun cycleLoopMode() {
        if (_isRadioMode.value) return
        val newMode =
            when (_loopMode.value) {
                PlaybackLoopMode.ListRepeat -> PlaybackLoopMode.SingleRepeat
                PlaybackLoopMode.SingleRepeat -> PlaybackLoopMode.Shuffle
                PlaybackLoopMode.Shuffle -> PlaybackLoopMode.ListRepeat
            }
        _loopMode.value = newMode
        if (newMode == PlaybackLoopMode.Shuffle) {
            val list = _playlist.value
            shuffleQueue.reset(list.size, _currentIndex.value, list)
        }
        onStateChanged()
    }

    fun checkPrefetchRadioSongs() {
        if (!_isRadioMode.value || isFetchingMoreRadio) return
        val currentList = _playlist.value
        if (_currentIndex.value >= currentList.size - 3) {
            isFetchingMoreRadio = true
            scope.launch(Dispatchers.IO) {
                try {
                    val moreSongs = apiService.getGuessRecommendSongs(count = 15)
                    if (moreSongs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            appendPlaylist(moreSongs)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    isFetchingMoreRadio = false
                }
            }
        }
    }

    fun getPreviousSong(isRemoteActive: Boolean, remotePrevSong: Song?): Song? {
        if (isRemoteActive) {
            return remotePrevSong
        }
        val list = _playlist.value
        if (list.isEmpty()) return null
        if (_isRadioMode.value) {
            val prevIndex = _currentIndex.value - 1
            return if (prevIndex in list.indices) list[prevIndex] else null
        }
        val prevIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.Shuffle -> shuffleQueue.peekPrevious() ?: if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
                PlaybackLoopMode.SingleRepeat,
                PlaybackLoopMode.ListRepeat -> if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            }
        return if (prevIndex in list.indices) list[prevIndex] else null
    }

    fun getNextSong(isRemoteActive: Boolean, remoteNextSong: Song?): Song? {
        if (isRemoteActive) {
            return remoteNextSong
        }
        val list = _playlist.value
        if (list.isEmpty()) return null

        val pendingMid = pendingNextSongMid
        if (pendingMid != null) {
            val pendingSong = list.firstOrNull { it.songMid == pendingMid }
            if (pendingSong != null) return pendingSong
        }

        if (_isRadioMode.value) {
            val nextIndex = _currentIndex.value + 1
            return if (nextIndex in list.indices) list[nextIndex] else null
        }
        val nextIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.Shuffle -> shuffleQueue.peekNext() ?: ((_currentIndex.value + 1) % list.size)
                PlaybackLoopMode.SingleRepeat,
                PlaybackLoopMode.ListRepeat -> (_currentIndex.value + 1) % list.size
            }
        return if (nextIndex in list.indices) list[nextIndex] else null
    }

    fun updateSongInPlaylist(song: Song) {
        val current = _playlist.value
        val idx = current.indexOfFirst { it.songMid == song.songMid }
        if (idx != -1 && current[idx] != song) {
            val mutable = current.toMutableList()
            mutable[idx] = song
            _playlist.value = mutable
        }
    }

    fun playNext() {
        val list = _playlist.value
        if (list.isEmpty()) return

        val pendingMid = pendingNextSongMid
        if (pendingMid != null) {
            pendingNextSongMid = null
            val pendingIndex = list.indexOfFirst { it.songMid == pendingMid }
            if (pendingIndex != -1) {
                _currentIndex.value = pendingIndex
                if (!_isRadioMode.value && _loopMode.value == PlaybackLoopMode.Shuffle) {
                    shuffleQueue.syncTo(pendingIndex, list.size, list)
                }
                onPlaySongRequest(list[pendingIndex], null, 0L)
                checkPrefetchQueueNextPage()
                return
            }
        }

        if (_isRadioMode.value) {
            checkPrefetchRadioSongs()
            val nextIndex = _currentIndex.value + 1
            if (nextIndex in list.indices) {
                _currentIndex.value = nextIndex
                onPlaySongRequest(list[nextIndex], null, 0L)
            } else {
                scope.launch(Dispatchers.IO) {
                    try {
                        val moreSongs = apiService.getGuessRecommendSongs(count = 15)
                        withContext(Dispatchers.Main) {
                            if (moreSongs.isNotEmpty()) {
                                appendPlaylist(moreSongs)
                                val updated = _playlist.value
                                if (nextIndex in updated.indices) {
                                    _currentIndex.value = nextIndex
                                    onPlaySongRequest(updated[nextIndex], null, 0L)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            return
        }

        val nextIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.Shuffle -> {
                    val idx = shuffleQueue.next(list)
                    if (idx in list.indices) idx else ((_currentIndex.value + 1) % list.size)
                }
                PlaybackLoopMode.SingleRepeat,
                PlaybackLoopMode.ListRepeat -> (_currentIndex.value + 1) % list.size
            }
        if (nextIndex in list.indices) {
            _currentIndex.value = nextIndex
            onPlaySongRequest(list[nextIndex], null, 0L)
            checkPrefetchQueueNextPage()
        }
    }

    fun playPrevious() {
        val list = _playlist.value
        if (list.isEmpty()) return

        if (_isRadioMode.value) {
            val prevIndex = (_currentIndex.value - 1).coerceAtLeast(0)
            if (prevIndex in list.indices) {
                _currentIndex.value = prevIndex
                onPlaySongRequest(list[prevIndex], null, 0L)
            }
            return
        }

        val prevIndex =
            when (_loopMode.value) {
                PlaybackLoopMode.Shuffle -> {
                    val idx = shuffleQueue.previous()
                    if (idx in list.indices) idx else if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
                }
                PlaybackLoopMode.SingleRepeat,
                PlaybackLoopMode.ListRepeat -> if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            }
        if (prevIndex in list.indices) {
            _currentIndex.value = prevIndex
            onPlaySongRequest(list[prevIndex], null, 0L)
        }
    }

    fun syncRemoteQueue(queue: List<Song>, currentIndex: Int) {
        _playlist.value = queue
        _currentIndex.value = currentIndex
        _isRadioMode.value = false
        if (_loopMode.value == PlaybackLoopMode.Shuffle && queue.isNotEmpty()) {
            shuffleQueue.syncTo(currentIndex.coerceAtLeast(0), queue.size, queue)
        }
    }
}
