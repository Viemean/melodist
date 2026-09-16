package org.melodist.tv.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.melodist.core.connect.model.LyricsScrollPayload

object TakeoverLyricsState {
    private val _scrollFlow = MutableStateFlow<LyricsScrollPayload?>(null)
    val scrollFlow: StateFlow<LyricsScrollPayload?> = _scrollFlow.asStateFlow()

    fun update(payload: LyricsScrollPayload) {
        _scrollFlow.value = payload
    }

    fun reset() {
        _scrollFlow.value = null
    }
}
