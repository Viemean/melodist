package org.melodist.tv.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.melodist.core.connect.model.GestureSwipePayload
import org.melodist.core.connect.model.GestureSwipeState

object TakeoverGestureState {
    private val _gestureFlow =
        MutableStateFlow(
            GestureSwipePayload(state = GestureSwipeState.IDLE, fraction = 0f),
        )
    val gestureFlow: StateFlow<GestureSwipePayload> = _gestureFlow.asStateFlow()

    fun updateGesture(payload: GestureSwipePayload) {
        _gestureFlow.value = payload
    }

    fun reset() {
        _gestureFlow.value = GestureSwipePayload(state = GestureSwipeState.IDLE, fraction = 0f)
    }
}
