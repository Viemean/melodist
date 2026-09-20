package com.tencent.qqmusicsdk.player.listener

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import org.melodist.playback.PlaybackManager

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        Log.i(TAG, "MediaButtonReceiver onReceive: action=$action")
        if (Intent.ACTION_MEDIA_BUTTON == action) {
            @Suppress("DEPRECATION")
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackManager.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackManager.pause()
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> PlaybackManager.togglePlayPause()
                    KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackManager.playNext()
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackManager.playPrevious()
                }
            }
        }
    }

    companion object {
        private const val TAG = "VivoMediaButtonReceiver"
    }
}
