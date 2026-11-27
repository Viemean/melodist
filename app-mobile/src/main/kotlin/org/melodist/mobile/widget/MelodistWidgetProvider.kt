package org.melodist.mobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.melodist.playback.PlaybackManager

/**
 * 适配桌面与 vivo 智慧桌面（负一屏）的 4x2 音乐控制小组件。
 */
class MelodistWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        MelodistWidgetUpdater.init(context)
        MelodistWidgetUpdater.updateAllWidgets(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        MelodistWidgetUpdater.init(context)
        MelodistWidgetUpdater.updateAllWidgets(context, appWidgetIds)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        MelodistWidgetUpdater.init(context)
        when (action) {
            ACTION_TOGGLE_PLAY_PAUSE -> {
                runOnMain { PlaybackManager.togglePlayPause() }
            }

            ACTION_PREV -> {
                runOnMain { PlaybackManager.playPrevious() }
            }

            ACTION_NEXT -> {
                runOnMain { PlaybackManager.playNext() }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())

        const val ACTION_TOGGLE_PLAY_PAUSE = "org.melodist.mobile.widget.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_PREV = "org.melodist.mobile.widget.ACTION_PREV"
        const val ACTION_NEXT = "org.melodist.mobile.widget.ACTION_NEXT"
    }
}
