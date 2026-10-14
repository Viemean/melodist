package org.melodist.mobile.coloros

import android.os.Bundle
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.melodist.model.LyricLine
import org.melodist.model.Song
import java.util.Locale

/**
 * ColorOS 锁屏与流体云歌词协议转换工具。
 */
object ColorOsLyricHelper {
    const val KEY_OPLUS_MEDIA_CONFIG = "oplus.media.config"
    const val KEY_OPLUS_LYRIC_INFO = "lyricInfo"
    const val VALUE_MEDIA_CONFIG = "1"

    /**
     * 将解析后的歌词序列化为 ColorOS 支持的标准单行 LRC 文本。
     *
     * @param lyrics 歌词行列表
     * @return 符合 LRC 规范的歌词字符串
     */
    fun formatLyricsToLrc(lyrics: List<LyricLine>): String {
        if (lyrics.isEmpty()) return ""
        val sb = StringBuilder(lyrics.size * 32)
        val firstLine = lyrics.firstOrNull()
        if (firstLine != null && firstLine.timestampMs > 1000L) {
            sb.append("[00:00.00]前奏\n")
        }
        for (line in lyrics) {
            val totalSeconds = line.timestampMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val millis = (line.timestampMs % 1000) / 10
            val cleanedText =
                line.text
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim()
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, millis))
            sb.append(cleanedText)
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 按照 ColorOS OplusMediaDataManagerExImpl 规范构建 lyricInfo JSON 字符串。
     *
     * @param song 歌曲对象
     * @param lrcText LRC 文本
     * @return 序列化后的 JSON 字符串
     */
    fun buildLyricInfoJson(
        song: Song,
        lrcText: String,
    ): String =
        buildJsonObject {
            put("songName", song.name)
            put("artist", song.singer)
            put("songId", song.songMid)
            put("lyric", lrcText)
        }.toString()

    /**
     * 构建包含 ColorOS 私有媒体扩展字段的 Bundle。
     *
     * @param song 当前播放歌曲
     * @param lyrics 当前歌曲歌词列表
     * @return 注入了 oplus.media.config 与 lyricInfo 的 Bundle
     */
    fun buildMetadataExtras(
        song: Song,
        lyrics: List<LyricLine>,
    ): Bundle {
        val bundle = Bundle()
        bundle.putString(KEY_OPLUS_MEDIA_CONFIG, VALUE_MEDIA_CONFIG)
        val lrc = formatLyricsToLrc(lyrics)
        if (lrc.isNotBlank()) {
            bundle.putString(KEY_OPLUS_LYRIC_INFO, buildLyricInfoJson(song, lrc))
        }
        return bundle
    }
}
