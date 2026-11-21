package org.melodist.mobile.origin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 适配 OriginOS 原子随身听与系统控制中心 AIDL 协议的播放接口服务。
 *
 * 通过对齐 QQ 音乐开放 API 契约（com.tencent.qqmusic.third.api.contract.IQQMusicApi），
 * 向系统端提供播控执行、状态查询、双语逐行歌词、红心收藏与跨进程事件监听广播。
 */
open class QQMusicApiService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var eventNotifier: QQMusicEventNotifier
    private lateinit var commandDispatcher: QQMusicCommandDispatcher

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "QQMusicApiService created")

        eventNotifier = QQMusicEventNotifier(this, serviceScope)
        commandDispatcher = QQMusicCommandDispatcher(this, serviceScope, eventNotifier)

        eventNotifier.startObserving()
    }

    override fun onDestroy() {
        super.onDestroy()
        eventNotifier.release()
        serviceScope.cancel()
        Log.i(TAG, "QQMusicApiService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind action=${intent?.action}")
        return commandDispatcher
    }

    companion object {
        private const val TAG = "MelodistQQMusicApi"
    }
}
