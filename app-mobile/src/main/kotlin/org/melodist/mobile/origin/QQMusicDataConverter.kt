package org.melodist.mobile.origin

import android.content.Context
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import org.melodist.data.AppSettingsManager
import org.melodist.data.LocalMusicManager
import org.melodist.data.WebDavManager
import org.melodist.model.LyricLine
import org.melodist.model.Song
import org.melodist.playback.PlaybackLoopMode
import java.io.File

/**
 * QQ 音乐开放协议与内部数据模型的适配转换器。
 */
object QQMusicDataConverter {
    const val PLAY_STATUS_STOPPED = 3
    const val PLAY_STATUS_STARTED = 4
    const val PLAY_STATUS_PAUSED = 5

    const val EVENT_PLAY_STATE_CHANGED = "API_EVENT_PLAY_STATE_CHANGED"
    const val EVENT_PLAY_SONG_CHANGED = "API_EVENT_PLAY_SONG_CHANGED"
    const val EVENT_PLAY_MODE_CHANGED = "API_EVENT_PLAY_MODE_CHANGED"
    const val EVENT_FAVORITE_STATE_CHANGED = "API_EVENT_SONG_FAVORITE_STATE_CHANGED"

    @Volatile
    private var cachedSong: Song? = null

    @Volatile
    private var cachedSongJson: String = "{}"

    @Volatile
    private var cachedLyricsList: List<LyricLine>? = null

    @Volatile
    private var cachedShowBilingual: Boolean = false

    @Volatile
    private var cachedFormattedLrc: String = ""

    /**
     * 解析歌曲唯一标识字符串。
     */
    fun resolveSongId(song: Song): String =
        when {
            song.songId > 0L -> song.songId.toString()
            song.songMid.isNotBlank() -> song.songMid
            else -> (song.localFilePath ?: song.name).hashCode().toString()
        }

    /**
     * 将播放循环模式转换为协议数值。
     */
    fun convertLoopModeToQQMode(loopMode: PlaybackLoopMode): Int =
        when (loopMode) {
            PlaybackLoopMode.ListRepeat -> 0
            PlaybackLoopMode.SingleRepeat -> 1
            PlaybackLoopMode.Shuffle -> 2
        }

    /**
     * 将协议数值转换为播放循环模式。
     */
    fun convertQQModeToLoopMode(mode: Int): PlaybackLoopMode? =
        when (mode) {
            0 -> PlaybackLoopMode.ListRepeat
            1 -> PlaybackLoopMode.SingleRepeat
            2 -> PlaybackLoopMode.Shuffle
            else -> null
        }

    /**
     * 解析音频封面真实 URI。
     */
    fun resolveCoverUri(
        context: Context,
        song: Song,
    ): String {
        var rawUrl = song.coverUrl

        if (rawUrl.isBlank()) {
            val localPath = song.localFilePath
            if (!localPath.isNullOrBlank()) {
                rawUrl = LocalMusicManager.resolveCoverUrl(localPath, "")
            } else if (song.songMid.startsWith("webdav_")) {
                val server = WebDavManager.getActiveServer()
                val relativeHref = song.mediaMid.ifBlank { song.localFilePath ?: "" }
                if (server != null && relativeHref.isNotBlank()) {
                    rawUrl = WebDavManager.getSongCoverPath(server.id, relativeHref).orEmpty()
                }
            }
        }

        if (rawUrl.isBlank()) return ""

        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            return rawUrl
        }

        val cleanUrl = rawUrl.substringBefore('?')
        val rawPath = cleanUrl.removePrefix("file://")
        val decodedPath = Uri.decode(rawPath)
        val file = File(decodedPath)
        if (file.exists() && file.isFile && file.length() > 0L) {
            return OriginCoverProvider.buildContentUri(context.packageName, file).toString()
        }

        return ""
    }

    /**
     * 将歌曲模型构建为协议 JSON 字符串。
     */
    fun buildSongJson(
        context: Context,
        song: Song?,
    ): String {
        if (song == null) return "{}"
        val curCached = cachedSong
        if (curCached === song && cachedSongJson.isNotBlank() && cachedSongJson != "{}") {
            return cachedSongJson
        }
        val primaryArtist = song.singerList.firstOrNull()
        val singerObj =
            JSONObject().apply {
                put("id", primaryArtist?.id ?: 0L)
                put("mid", primaryArtist?.mid?.ifBlank { "0" } ?: "0")
                put("title", song.singer)
            }
        val resolvedCover = resolveCoverUri(context, song)
        val albumObj =
            JSONObject().apply {
                put("id", 0L)
                put("mid", song.albumMid.ifBlank { "0" })
                put("title", song.album)
                put("coverUri", resolvedCover)
            }
        val json =
            JSONObject()
                .apply {
                    put("id", resolveSongId(song))
                    put("mid", song.songMid)
                    put("title", song.name)
                    put("type", 0)
                    put("coverUri", resolvedCover)
                    put("singer", singerObj)
                    put("album", albumObj)
                }.toString()
        cachedSong = song
        cachedSongJson = json
        return json
    }

    /**
     * 将逐行歌词列表格式化为标准 LRC 文本。
     */
    fun formatLyricsToLrc(lyrics: List<LyricLine>): String {
        if (lyrics.isEmpty()) return ""
        val showBilingual = AppSettingsManager.settings.value.showBilingualLyrics
        val currentCachedLyrics = cachedLyricsList
        if (currentCachedLyrics === lyrics && cachedShowBilingual == showBilingual) {
            return cachedFormattedLrc
        }

        val sb = StringBuilder(lyrics.size * 48)
        for (line in lyrics) {
            val ms = line.timestampMs
            val min = ms / 60000
            val sec = (ms % 60000) / 1000
            val hundredths = (ms % 1000) / 10
            val text =
                if (showBilingual && line.hasTranslation) {
                    "${line.text}^${line.transText}"
                } else {
                    line.text
                }
            sb.append('[')
            if (min < 10) sb.append('0')
            sb.append(min).append(':')
            if (sec < 10) sb.append('0')
            sb.append(sec).append('.')
            if (hundredths < 10) sb.append('0')
            sb.append(hundredths).append(']')
            sb.append(text).append('\n')
        }
        val formatted = sb.toString()
        cachedLyricsList = lyrics
        cachedShowBilingual = showBilingual
        cachedFormattedLrc = formatted
        return formatted
    }

    /**
     * 构建播放状态 Bundle。
     */
    fun buildPlayStateBundle(
        state: Int,
        positionMs: Long,
    ): Bundle {
        val posInt = positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return Bundle().apply {
            putInt("code", 0)
            putInt("data", state)
            putInt("playState", state)
            putLong("time", positionMs)
            putInt("timeInt", posInt)
            putLong("currTime", positionMs)
            putInt("currTimeInt", posInt)
            putLong("position", positionMs)
            putInt("positionInt", posInt)
        }
    }

    /**
     * 构建歌单分类信息 JSON 对象。
     */
    fun buildFolderJson(
        id: String,
        title: String,
        subTitle: String,
        type: Int,
        picUrl: String = "",
        isSongFolder: Boolean = true,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("mainTitle", title)
            put("subTitle", subTitle)
            put("picUrl", picUrl)
            put("type", type)
            put("isSongFolder", isSongFolder)
        }
}
