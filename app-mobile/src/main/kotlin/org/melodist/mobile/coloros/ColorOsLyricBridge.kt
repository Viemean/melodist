package org.melodist.mobile.coloros

import android.os.Bundle
import android.util.Log
import org.melodist.playback.PlaybackMediaItemFactory

/**
 * ColorOS 锁屏与流体云歌词桥接注册组件。
 */
object ColorOsLyricBridge {
    private const val TAG = "ColorOsLyricBridge"
    private var isRegistered = false

    /**
     * 向 PlaybackMediaItemFactory 注册 ColorOS 扩展元数据提供器。
     */
    fun init() {
        if (isRegistered) return
        isRegistered = true

        val previousProvider = PlaybackMediaItemFactory.extraMetadataProvider
        PlaybackMediaItemFactory.extraMetadataProvider = { song, lyrics ->
            val bundle = previousProvider?.invoke(song, lyrics) ?: Bundle()
            val colorOsExtras = ColorOsLyricHelper.buildMetadataExtras(song, lyrics)
            bundle.putAll(colorOsExtras)
            bundle
        }
        Log.i(TAG, "ColorOsLyricBridge initialized")
    }
}
