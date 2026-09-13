package org.melodist.tv.screensaver

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.melodist.data.AppSettingsManager
import org.melodist.data.ScreenSaverTimeout
import org.melodist.playback.PlaybackManager

/**
 * 全局屏幕保护控制器：
 * 负责全局用户输入空闲计时、超时判定、在播状态互锁与屏保状态分发。
 */
object ScreenSaverManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitorJob: Job? = null

    private var lastInteractionTimeMs = System.currentTimeMillis()

    private val _isScreenSaverActive = MutableStateFlow(false)
    val isScreenSaverActive: StateFlow<Boolean> = _isScreenSaverActive.asStateFlow()

    fun init() {
        if (monitorJob != null) return
        lastInteractionTimeMs = System.currentTimeMillis()
        monitorJob =
            scope.launch {
                while (isActive) {
                    delay(2000L) // 每 2 秒巡检一次空闲时长
                    checkInactivity()
                }
            }
    }

    /**
     * 用户按键或触控交互回调
     */
    fun onUserInteraction() {
        lastInteractionTimeMs = System.currentTimeMillis()
    }

    /**
     * 退出屏保
     */
    fun dismissScreenSaver() {
        _isScreenSaverActive.value = false
        lastInteractionTimeMs = System.currentTimeMillis()
    }

    /**
     * 手动触发进入屏保（用于预览或即时休眠）
     */
    fun triggerScreenSaver() {
        _isScreenSaverActive.value = true
    }

    private fun checkInactivity() {
        if (_isScreenSaverActive.value) return

        val settings = AppSettingsManager.settings.value
        val timeout = settings.screenSaverTimeout
        if (timeout == ScreenSaverTimeout.Never || timeout.millis <= 0L) return

        val isPlaying = PlaybackManager.isPlaying.value
        if (!settings.enableScreenSaverDuringPlayback && isPlaying) {
            // 播放期间不允许进入屏保时，持续重置空闲时间戳，避免暂停瞬间立即触发屏保
            lastInteractionTimeMs = System.currentTimeMillis()
            return
        }

        val elapsed = System.currentTimeMillis() - lastInteractionTimeMs
        if (elapsed >= timeout.millis) {
            _isScreenSaverActive.value = true
        }
    }
}
