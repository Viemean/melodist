package org.melodist.mobile.origin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteCallbackList
import android.util.Log
import com.tencent.qqmusic.third.api.contract.IQQMusicApiEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.melodist.data.AppSettingsManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.playback.PlaybackManager

/**
 * 跨进程事件监听与系统状态广播分发器。
 */
class QQMusicEventNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val callbackList = RemoteCallbackList<IQQMusicApiEventListener>()

    /**
     * 启动状态流监听与自动广播机制。
     */
    fun startObserving() {
        scope.launch {
            PlaybackManager.isPlaying.collectLatest { playing ->
                notifyPlayStateChanged()
                if (playing) {
                    val currSong = PlaybackManager.currentSong.value
                    if (currSong != null) {
                        val songJson = QQMusicDataConverter.buildSongJson(context, currSong)
                        val songBundle =
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", songJson)
                                putString("playSong", songJson)
                                putString("song", songJson)
                            }
                        dispatchSystemEvent(QQMusicDataConverter.EVENT_PLAY_SONG_CHANGED, songBundle)
                    }
                }
            }
        }

        scope.launch {
            PlaybackManager.currentSong.collectLatest { song ->
                val songJson = QQMusicDataConverter.buildSongJson(context, song)
                val bundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putString("data", songJson)
                        putString("playSong", songJson)
                        putString("song", songJson)
                    }
                dispatchSystemEvent(QQMusicDataConverter.EVENT_PLAY_SONG_CHANGED, bundle)
            }
        }

        scope.launch {
            PlaybackManager.loopMode.collectLatest { loopMode ->
                val mode = QQMusicDataConverter.convertLoopModeToQQMode(loopMode)
                val bundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putInt("data", mode)
                        putInt("playMode", mode)
                    }
                dispatchSystemEvent(QQMusicDataConverter.EVENT_PLAY_MODE_CHANGED, bundle)
            }
        }

        scope.launch {
            PlaybackManager.lyrics.collectLatest { lyrics ->
                if (lyrics.isNotEmpty()) {
                    val songJson = QQMusicDataConverter.buildSongJson(context, PlaybackManager.currentSong.value)
                    val bundle =
                        Bundle().apply {
                            putInt("code", 0)
                            putString("data", songJson)
                            putString("playSong", songJson)
                            putString("song", songJson)
                        }
                    dispatchSystemEvent(QQMusicDataConverter.EVENT_PLAY_SONG_CHANGED, bundle)
                    notifyPlayStateChanged()
                }
            }
        }

        scope.launch {
            AppSettingsManager.settings
                .map { it.showBilingualLyrics }
                .distinctUntilChanged()
                .collectLatest {
                    if (PlaybackManager.lyrics.value.isNotEmpty()) {
                        val songJson = QQMusicDataConverter.buildSongJson(context, PlaybackManager.currentSong.value)
                        val bundle =
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", songJson)
                                putString("playSong", songJson)
                                putString("song", songJson)
                            }
                        dispatchSystemEvent(QQMusicDataConverter.EVENT_PLAY_SONG_CHANGED, bundle)
                    }
                }
        }

        scope.launch {
            UserLibraryCacheManager.favoriteSongsFlow.collectLatest { favList ->
                val currSong = PlaybackManager.currentSong.value ?: return@collectLatest
                val isFav = favList.any { it.songMid == currSong.songMid || (currSong.songId > 0 && it.songId == currSong.songId) }
                val favBundle =
                    Bundle().apply {
                        putInt("code", 0)
                        putString("song", QQMusicDataConverter.buildSongJson(context, currSong))
                        putBoolean("isFavorite", isFav)
                    }
                dispatchSystemEvent(QQMusicDataConverter.EVENT_FAVORITE_STATE_CHANGED, favBundle)
            }
        }

        notifyVivoWidgetInit()
    }

    /**
     * 发送系统通知唤醒 Vivo 原子组件。
     */
    fun notifyVivoWidgetInit() {
        try {
            val packageIntent =
                Intent("vivo.intent.musicwidgetmix.notify.init").apply {
                    setPackage("com.vivo.musicwidgetmix")
                }
            context.sendBroadcast(packageIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast notify.init to package", e)
        }
        try {
            val permIntent = Intent("vivo.intent.musicwidgetmix.notify.init")
            context.sendBroadcast(permIntent, "vivo.intent.musicwidgetmix.notify.init.PERMISSION")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast notify.init with permission", e)
        }
    }

    /**
     * 广播播放状态与播放进度。
     */
    fun notifyPlayStateChanged(forcePos: Long? = null) {
        val playing = PlaybackManager.isPlaying.value
        val state =
            if (playing) {
                QQMusicDataConverter.PLAY_STATUS_STARTED
            } else {
                QQMusicDataConverter.PLAY_STATUS_PAUSED
            }
        val pos = forcePos ?: PlaybackManager.currentPositionMs.value
        val bundle = QQMusicDataConverter.buildPlayStateBundle(state, pos)
        dispatchSystemEvent(QQMusicDataConverter.EVENT_PLAY_STATE_CHANGED, bundle)
    }

    /**
     * 立即主动广播当前歌曲的红心收藏状态变更。
     */
    fun notifyFavoriteStateChanged(
        song: org.melodist.model.Song,
        isFav: Boolean,
    ) {
        val favBundle =
            Bundle().apply {
                putInt("code", 0)
                putString("song", QQMusicDataConverter.buildSongJson(context, song))
                putBoolean("isFavorite", isFav)
            }
        dispatchSystemEvent(QQMusicDataConverter.EVENT_FAVORITE_STATE_CHANGED, favBundle)
    }

    /**
     * 注册客户端跨进程事件监听器。
     */
    fun registerEventListener(
        events: MutableList<String>?,
        listener: IQQMusicApiEventListener?,
    ): Bundle {
        if (listener != null) {
            val registered = callbackList.register(listener)
            Log.i(TAG, "registerEventListener: events=$events, registered=$registered")
            scope.launch {
                try {
                    val currSong = PlaybackManager.currentSong.value
                    if (currSong != null) {
                        val songJson = QQMusicDataConverter.buildSongJson(context, currSong)
                        listener.onEvent(
                            QQMusicDataConverter.EVENT_PLAY_SONG_CHANGED,
                            Bundle().apply {
                                putInt("code", 0)
                                putString("data", songJson)
                                putString("playSong", songJson)
                                putString("song", songJson)
                            },
                        )
                    }
                    val state =
                        if (PlaybackManager.isPlaying.value) {
                            QQMusicDataConverter.PLAY_STATUS_STARTED
                        } else {
                            QQMusicDataConverter.PLAY_STATUS_PAUSED
                        }
                    listener.onEvent(
                        QQMusicDataConverter.EVENT_PLAY_STATE_CHANGED,
                        Bundle().apply {
                            putInt("code", 0)
                            putInt("data", state)
                            putInt("playState", state)
                        },
                    )
                    val loopMode = PlaybackManager.loopMode.value
                    val mode = QQMusicDataConverter.convertLoopModeToQQMode(loopMode)
                    listener.onEvent(
                        QQMusicDataConverter.EVENT_PLAY_MODE_CHANGED,
                        Bundle().apply {
                            putInt("code", 0)
                            putInt("data", mode)
                            putInt("playMode", mode)
                        },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed initial push to listener", e)
                }
            }
        }
        return Bundle().apply {
            putInt("code", 0)
            putInt("data", 0)
        }
    }

    /**
     * 注销客户端跨进程事件监听器。
     */
    fun unregisterEventListener(
        events: MutableList<String>?,
        listener: IQQMusicApiEventListener?,
    ): Bundle {
        if (listener != null) {
            val unregistered = callbackList.unregister(listener)
            Log.i(TAG, "unregisterEventListener: unregistered=$unregistered")
        }
        return Bundle().apply {
            putInt("code", 0)
            putInt("data", 0)
        }
    }

    /**
     * 广播事件至所有已注册回调的客户端。
     */
    fun dispatchSystemEvent(
        event: String,
        bundle: Bundle,
    ) {
        val count = callbackList.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbackList.getBroadcastItem(i).onEvent(event, bundle)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send event to broadcast item $i", e)
                }
            }
        } finally {
            callbackList.finishBroadcast()
        }
    }

    /**
     * 释放所有跨进程回调。
     */
    fun release() {
        callbackList.kill()
    }

    companion object {
        private const val TAG = "MelodistQQMusicNotifier"
    }
}
