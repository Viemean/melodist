package org.melodist.api

import org.melodist.model.LyricLine
import org.melodist.model.WordSpan
import java.util.Base64
import kotlin.math.abs

object LyricParser {
    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{1,2})(?:[\.:](\d{1,3}))?\]""")
    private val QRC_WORD_REGEX = Regex("""\((\d+),(\d+)\)([^(]*)""")

    // 标准 LRC ID 标签正则 (如 ti:, ar:, al:, by:, offset:)
    private val LRC_TAG_REGEX = Regex("""^(?:ti|ar|al|by|offset)\s*[:：]""", RegexOption.IGNORE_CASE)

    // 结构化制作信息行正则 (中/日/韩/英 制作角色 + 任意冒号/空格/by 连接)
    private val META_ROLE_REGEX =
        Regex(
            """^(?:(?:作?[词詞]|作?[曲]|编[曲]|編[曲]|演?唱|原唱|制作|製作|监制|監制|混音|录音|錄音|母带|母帶|出品|企划|企劃|歌手|专辑|專輯|歌名|歌曲|和声|和聲|吉他|贝斯|貝斯|鼓手|弦乐|弦樂|OP|SP|작사|작곡|편곡)\s*人?\s*[:：]|(?:lyrics?|composed?|arranged?|music|produced?|producer|mixed?|mastered?|vocals?|strings?|guitars?|bass|drums?|production|(?:mixing|mastering|recording|audio|sound)\s+engineers?|music\s+(?:supervisor|producer)s?|voicing\s+arrangements?)\s*(?:by|[:：]))""",
            RegexOption.IGNORE_CASE,
        )

    private val META_STATEMENT_KEYWORDS =
        listOf(
            "享有本翻译",
            "翻译贡献",
            "大模型",
            "未经著作权人",
            "未经许可",
        )

    private val TITLE_SEPARATORS =
        listOf(" - ", " － ", " — ", " – ", " / ")

    /**
     * 将 Base64 编码的歌词原始字符串解码为 UTF-8 明文文本。
     *
     * @param b64 Base64 编码字符串
     * @return 解码后的纯文本字符串
     */
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
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val msStr =
                    m.groupValues
                        .getOrNull(3)
                        ?.padEnd(3, '0')
                        ?.take(3) ?: "000"
                val ms = msStr.toIntOrNull() ?: 0

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
            return mergeSingleLrcBilingual(origItems)
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
                // 1. 精确匹配 (< 120ms)
                val exact = transItems.find { abs(it.first - orig.first) < 120 }
                if (exact != null) {
                    if (exact.second != "//" &&
                        !exact.second.contains("享有") &&
                        !exact.second.contains("大模型") &&
                        !exact.second.contains("翻译贡献")
                    ) {
                        transText = exact.second
                        usedSet.add(exact)
                    }
                    // 精确匹配到占位符 "//" 时明确该行无翻译，不应被后续容差候选覆盖
                } else if (cleanTrans.isNotEmpty()) {
                    // 2. 容差候选匹配 (<= 600ms)：
                    // 优先匹配当前原文行
                    val candidates =
                        cleanTrans.filter { cand ->
                            cand !in usedSet &&
                                abs(cand.first - orig.first) <= 600 &&
                                !origItems.drop(i + 1).any { nextOrig ->
                                    abs(cand.first - nextOrig.first) < abs(cand.first - orig.first)
                                }
                        }
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
        val min = match.groupValues[1].toIntOrNull() ?: 0
        val sec = match.groupValues[2].toIntOrNull() ?: 0
        val msStr =
            match.groupValues
                .getOrNull(3)
                ?.padEnd(3, '0')
                ?.take(3) ?: "000"
        val lineTimestampMs = min * 60_000L + sec * 1_000L + (msStr.toIntOrNull() ?: 0)

        val afterTimestamp = rawLine.substring(match.range.last + 1)
        val wordMatches = QRC_WORD_REGEX.findAll(afterTimestamp).toList()

        if (wordMatches.isEmpty()) {
            val text = afterTimestamp.trim()
            return if (text.isNotEmpty()) LyricLine(lineTimestampMs, text) else null
        }

        val words = mutableListOf<WordSpan>()
        val fullTextBuilder = StringBuilder()

        for (wm in wordMatches) {
            val offset = wm.groupValues[1].toLongOrNull() ?: 0L
            val dur = wm.groupValues[2].toLongOrNull() ?: 0L
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

    /**
     * 判定指定歌词文本行是否属于元数据信息行。
     *
     * @param timestampMs 行时间戳（毫秒）
     * @param text 歌词文本
     * @return 为元数据行返回 true，属于正文歌词返回 false
     */
    fun isMetaInfoLine(
        timestampMs: Long,
        text: String,
    ): Boolean {
        if (text.isBlank()) return true
        val t = text.trim()
        if (t == "//") return true

        // 1. 结构化 Key-Value 制作属性匹配 (中/日/韩/英) 及 LRC 标签
        if (META_ROLE_REGEX.containsMatchIn(t) || LRC_TAG_REGEX.containsMatchIn(t)) {
            return true
        }

        // 2. 版权与声明关键词过滤
        if (META_STATEMENT_KEYWORDS.any { t.contains(it, ignoreCase = true) }) {
            return true
        }

        // 3. 前奏前 6 秒内的歌曲-歌手标题行 (要求两边带空格，且排除句首破折号)
        if (timestampMs <= 6000) {
            if (TITLE_SEPARATORS.any { t.contains(it) } && !t.startsWith("-") && !t.startsWith("—")) {
                return true
            }
        }

        return false
    }

    /**
     * 单文本双语歌词检测与合并。
     * 支持交错式双语歌词（相邻原文行与译文行时间戳相同或在 250ms 容差内）。
     */
    fun mergeSingleLrcBilingual(origItems: List<Pair<Long, String>>): List<LyricLine> {
        if (origItems.isEmpty()) return emptyList()

        val n = origItems.size
        var pairs = 0
        var nonMetaCount = 0
        var i = 0
        while (i < n) {
            val (ts, text) = origItems[i]
            if (!isMetaInfoLine(ts, text)) {
                nonMetaCount++
                if (i + 1 < n) {
                    val (nextTs, nextText) = origItems[i + 1]
                    if (!isMetaInfoLine(nextTs, nextText) && abs(nextTs - ts) <= 250) {
                        pairs++
                        i += 2
                        continue
                    }
                }
            }
            i++
        }

        val isBilingual = pairs >= 3 || (nonMetaCount in 1..6 && pairs * 2 >= nonMetaCount)
        if (!isBilingual) {
            return origItems.map { LyricLine(timestampMs = it.first, text = it.second) }
        }

        val result = mutableListOf<LyricLine>()
        i = 0
        while (i < n) {
            val (ts, text) = origItems[i]
            if (isMetaInfoLine(ts, text)) {
                result.add(LyricLine(timestampMs = ts, text = text))
                i++
                continue
            }

            if (i + 1 < n) {
                val (nextTs, nextText) = origItems[i + 1]
                if (!isMetaInfoLine(nextTs, nextText) && abs(nextTs - ts) <= 250) {
                    result.add(LyricLine(timestampMs = ts, text = text, transText = nextText))
                    i += 2
                    continue
                }
            }

            result.add(LyricLine(timestampMs = ts, text = text))
            i++
        }

        return result
    }

    /**
     * 判断歌词是否为纯音乐、暂无歌词等无效占位内容
     */
    fun isPlaceholderLyrics(lyrics: List<LyricLine>): Boolean {
        if (lyrics.isEmpty()) return true
        val validLines = lyrics.filter { !isMetaInfoLine(it.timestampMs, it.text) && it.text.isNotBlank() }
        if (validLines.isEmpty()) return true
        if (validLines.size <= 2) {
            val combined = validLines.joinToString(" ") { it.text }
            val placeholderKeywords =
                listOf(
                    "纯音乐",
                    "没有填词",
                    "暂无歌词",
                    "请您欣赏",
                    "请欣赏",
                    "instrumental",
                    "no lyrics",
                )
            if (placeholderKeywords.any { combined.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }
}
