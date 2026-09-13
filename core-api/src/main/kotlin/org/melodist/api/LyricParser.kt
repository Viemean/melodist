package org.melodist.api

import org.melodist.model.LyricLine
import org.melodist.model.WordSpan
import java.util.Base64
import kotlin.math.abs

object LyricParser {
    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[\.:](\d{1,3}))?\]""")
    private val QRC_WORD_REGEX = Regex("""\((\d+),(\d+)\)([^(]*)""")

    fun decodeBase64(b64: String?): String {
        if (b64.isNullOrBlank()) return ""
        return try {
            val bytes = Base64.getDecoder().decode(b64.trim())
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 解析基础 LRC 时间戳与文本对
     */
    fun parseLrc(lrcText: String?): List<Pair<Long, String>> {
        if (lrcText.isNullOrBlank()) return emptyList()

        val lines = lrcText.lines()
        val rawList = mutableListOf<Triple<Long, String, Int>>()
        var lineOrder = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val content =
                TIMESTAMP_REGEX
                    .replace(trimmed, "")
                    .trim()
                    .replace("&apos;", "’")
            if (content.isEmpty() || content == "//") continue

            for (m in matches) {
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                val msStr =
                    m.groupValues
                        .getOrNull(3)
                        ?.padEnd(3, '0')
                        ?.take(3) ?: "000"
                val ms = msStr.toInt()

                val timestampMs = min * 60_000L + sec * 1_000L + ms
                rawList.add(Triple(timestampMs, content, lineOrder++))
            }
        }

        // 稳定排序：时间戳相同保留出现顺序
        rawList.sortWith(compareBy<Triple<Long, String, Int>> { it.first }.thenBy { it.third })

        return rawList.map { Pair(it.first, it.second) }
    }

    /**
     * 合并原文与译文歌词列表为 LyricLine 列表
     */
    fun parseMergedLyrics(
        origLrc: String?,
        transLrc: String?,
    ): List<LyricLine> {
        val origItems = parseLrc(origLrc)
        val transItems = parseLrc(transLrc)

        if (origItems.isEmpty()) return emptyList()
        if (transItems.isEmpty()) {
            return origItems.map { LyricLine(timestampMs = it.first, text = it.second) }
        }

        val cleanTrans =
            transItems.filter {
                it.second != "//" && !it.second.contains("享有") && !it.second.contains("大模型") && !it.second.contains("翻译贡献")
            }

        val usedSet = mutableSetOf<Pair<Long, String>>()
        val result = mutableListOf<LyricLine>()

        for (i in origItems.indices) {
            val orig = origItems[i]
            var transText = ""
            val isMeta = isMetaInfoLine(orig.first, orig.second)

            if (!isMeta && transItems.isNotEmpty()) {
                // 1. 精确匹配 (< 80ms)
                val exact = transItems.find { abs(it.first - orig.first) < 80 }
                if (exact != null &&
                    exact !in usedSet &&
                    exact.second != "//" &&
                    !exact.second.contains("享有") &&
                    !exact.second.contains("大模型")
                ) {
                    transText = exact.second
                    usedSet.add(exact)
                } else if (cleanTrans.isNotEmpty()) {
                    // 2. 300ms 容差候选
                    val candidates = cleanTrans.filter { it !in usedSet && abs(it.first - orig.first) <= 300 }
                    if (candidates.isNotEmpty()) {
                        val best = candidates.minByOrNull { abs(it.first - orig.first) }!!
                        transText = best.second
                        usedSet.add(best)
                    } else if (cleanTrans.size == origItems.size && i < cleanTrans.size && cleanTrans[i] !in usedSet) {
                        // 3. 索引回退
                        transText = cleanTrans[i].second
                        usedSet.add(cleanTrans[i])
                    }
                }
            }

            result.add(LyricLine(timestampMs = orig.first, text = orig.second, transText = transText))
        }

        return result
    }

    /**
     * 解析 QRC / 逐字时间轴（格式如 `[01:23.45](0,200)你(200,300)好`）
     */
    fun parseQrcLine(rawLine: String): LyricLine? {
        val match = TIMESTAMP_REGEX.find(rawLine) ?: return null
        val min = match.groupValues[1].toInt()
        val sec = match.groupValues[2].toInt()
        val msStr =
            match.groupValues
                .getOrNull(3)
                ?.padEnd(3, '0')
                ?.take(3) ?: "000"
        val lineTimestampMs = min * 60_000L + sec * 1_000L + msStr.toInt()

        val afterTimestamp = rawLine.substring(match.range.last + 1)
        val wordMatches = QRC_WORD_REGEX.findAll(afterTimestamp).toList()

        if (wordMatches.isEmpty()) {
            val text = afterTimestamp.trim()
            return if (text.isNotEmpty()) LyricLine(lineTimestampMs, text) else null
        }

        val words = mutableListOf<WordSpan>()
        val fullTextBuilder = StringBuilder()

        for (wm in wordMatches) {
            val offset = wm.groupValues[1].toLong()
            val dur = wm.groupValues[2].toLong()
            val word = wm.groupValues[3]
            words.add(WordSpan(word = word, offsetMs = offset, durationMs = dur))
            fullTextBuilder.append(word)
        }

        return LyricLine(
            timestampMs = lineTimestampMs,
            text = fullTextBuilder.toString(),
            words = words,
        )
    }

    fun isMetaInfoLine(
        timestampMs: Long,
        text: String,
    ): Boolean {
        if (text.isBlank()) return true
        val t = text.trim()
        if (timestampMs <= 50 && t.contains(" - ")) return true

        val metaPrefixes =
            listOf(
                "词：",
                "词:",
                "作词：",
                "作词:",
                "曲：",
                "曲:",
                "作曲：",
                "作曲:",
                "编曲：",
                "编曲:",
                "制作：",
                "制作:",
                "制作人：",
                "制作人:",
                "监制：",
                "混音：",
                "录音：",
                "母带：",
                "出品：",
                "企划：",
                "OP：",
                "SP：",
            )
        return metaPrefixes.any { t.startsWith(it) }
    }
}
