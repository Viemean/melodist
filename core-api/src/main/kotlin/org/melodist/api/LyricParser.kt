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
     * 合并原文（LRC 或 QRC）与译文歌词列表为 LyricLine 列表
     */
    fun parseMergedLyrics(
        origLrc: String?,
        transLrc: String?,
    ): List<LyricLine> {
        if (origLrc.isNullOrBlank()) return emptyList()

        val isQrc = isQrcContent(origLrc)
        val transItems = parseLrc(transLrc)

        if (isQrc) {
            val qrcLines = parseQrcLyrics(origLrc)
            if (qrcLines.isEmpty()) return emptyList()
            if (transItems.isEmpty()) return qrcLines

            val cleanTrans =
                transItems.filter {
                    it.second != "//" && !it.second.contains("享有") && !it.second.contains("大模型") && !it.second.contains("翻译贡献")
                }

            val usedSet = mutableSetOf<Pair<Long, String>>()
            val result = mutableListOf<LyricLine>()

            for (i in qrcLines.indices) {
                val qrcLine = qrcLines[i]
                var transText = ""
                val isMeta = isMetaInfoLine(qrcLine.timestampMs, qrcLine.text)

                if (!isMeta && transItems.isNotEmpty()) {
                    val exact = transItems.find { abs(it.first - qrcLine.timestampMs) < 150 }
                    if (exact != null) {
                        if (exact.second != "//" &&
                            !exact.second.contains("享有") &&
                            !exact.second.contains("大模型") &&
                            !exact.second.contains("翻译贡献")
                        ) {
                            transText = exact.second
                            usedSet.add(exact)
                        }
                    } else if (cleanTrans.isNotEmpty()) {
                        val candidates =
                            cleanTrans.filter { cand ->
                                cand !in usedSet &&
                                    abs(cand.first - qrcLine.timestampMs) <= 600 &&
                                    !qrcLines.drop(i + 1).any { nextOrig ->
                                        abs(cand.first - nextOrig.timestampMs) < abs(cand.first - qrcLine.timestampMs)
                                    }
                            }
                        if (candidates.isNotEmpty()) {
                            val best = candidates.minByOrNull { abs(it.first - qrcLine.timestampMs) }!!
                            transText = best.second
                            usedSet.add(best)
                        } else if (cleanTrans.size == qrcLines.size && i < cleanTrans.size && cleanTrans[i] !in usedSet) {
                            transText = cleanTrans[i].second
                            usedSet.add(cleanTrans[i])
                        }
                    }
                }

                result.add(
                    LyricLine(
                        timestampMs = qrcLine.timestampMs,
                        text = qrcLine.text,
                        transText = transText,
                        words = qrcLine.words,
                    ),
                )
            }
            return result
        }

        val origItems = parseLrc(origLrc)
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
                // 精确匹配 (< 120ms)
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
                } else if (cleanTrans.isNotEmpty()) {
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
                        transText = cleanTrans[i].second
                        usedSet.add(cleanTrans[i])
                    }
                }
            }

            result.add(LyricLine(timestampMs = orig.first, text = orig.second, transText = transText))
        }

        return result
    }

    private val QRC_MS_REGEX = Regex("""\[(\d+),(\d+)\]""")

    /**
     * 判断一段歌词文本是否为 QRC（包含 XML 结构或逐字时间标签）
     */
    fun isQrcContent(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val trimmed = text.trim()
        return trimmed.startsWith("<?xml") || trimmed.contains("<QrcInfos") || QRC_MS_REGEX.containsMatchIn(trimmed)
    }

    /**
     * 从 QRC XML 报文中提取 LyricContent 文本，若非 XML 则原样返回
     */
    fun extractQrcLyricContent(rawText: String?): String {
        if (rawText.isNullOrBlank()) return ""
        val trimmed = rawText.trim()
        if (trimmed.startsWith("<?xml") || trimmed.contains("<QrcInfos")) {
            // QQ音乐 QRC 的 LyricContent 属性值内常包含未经转义的双引号（如合作曲名），需匹配到末尾标签前闭合的双引号
            val contentMatch = Regex("""LyricContent="(.*)"(?:\s*/>|\s*>)""", RegexOption.DOT_MATCHES_ALL).find(trimmed)
            if (contentMatch != null) {
                return contentMatch.groupValues[1]
                    .replace("&apos;", "'")
                    .replace("&quot;", "\"")
                    .replace("&gt;", ">")
                    .replace("&lt;", "<")
                    .replace("&amp;", "&")
            }
        }
        return rawText
    }

    /**
     * 解析基础 QRC（逐字时间戳）行列表
     */
    fun parseQrcLyrics(qrcText: String?): List<LyricLine> {
        val content = extractQrcLyricContent(qrcText)
        if (content.isBlank()) return emptyList()

        val lines = content.lines()
        val result = mutableListOf<LyricLine>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parsed = parseQrcLine(trimmed)
            if (parsed != null) {
                result.add(parsed)
            }
        }
        return result
    }

    /**
     * 解析 QRC / 逐字时间轴（支持标准分秒 `[01:23.45]` 与纯毫秒 `[12345,6789]` 格式）
     */
    fun parseQrcLine(rawLine: String): LyricLine? {
        // 先检查纯毫秒格式 [startMs, durationMs]
        val msMatch = QRC_MS_REGEX.find(rawLine)
        val lineTimestampMs: Long
        val afterTimestamp: String

        if (msMatch != null && msMatch.range.first == 0) {
            lineTimestampMs = msMatch.groupValues[1].toLongOrNull() ?: 0L
            afterTimestamp = rawLine.substring(msMatch.range.last + 1)
        } else {
            val match = TIMESTAMP_REGEX.find(rawLine) ?: return null
            val min = match.groupValues[1].toIntOrNull() ?: 0
            val sec = match.groupValues[2].toIntOrNull() ?: 0
            val msStr =
                match.groupValues
                    .getOrNull(3)
                    ?.padEnd(3, '0')
                    ?.take(3) ?: "000"
            lineTimestampMs = min * 60_000L + sec * 1_000L + (msStr.toIntOrNull() ?: 0)
            afterTimestamp = rawLine.substring(match.range.last + 1)
        }

        // 增强 LRC 格式（括号起始）：(start,dur)文本 如 "(0,300)旋(300,200)律(500,400)家"
        // QRC 格式（文本起始）：文本(start,dur) 如 "蝉(25961,245)の(26206,403)"
        val isLeadingStyle = afterTimestamp.startsWith("(")
        val timeTagRegex = Regex("""\((\d+),(\d+)\)""")

        if (isLeadingStyle) {
            val leadingMatches = QRC_WORD_REGEX.findAll(afterTimestamp).toList()
            if (leadingMatches.isEmpty()) {
                val text = afterTimestamp.trim()
                return if (text.isNotEmpty()) LyricLine(lineTimestampMs, text) else null
            }
            val words = mutableListOf<WordSpan>()
            val fullTextBuilder = StringBuilder()
            for (wm in leadingMatches) {
                val offsetOrAbsStart = wm.groupValues[1].toLongOrNull() ?: 0L
                val dur = wm.groupValues[2].toLongOrNull() ?: 0L
                val word = wm.groupValues[3]
                val relOffset = if (offsetOrAbsStart >= lineTimestampMs) offsetOrAbsStart - lineTimestampMs else offsetOrAbsStart
                words.add(WordSpan(word = word, offsetMs = relOffset, durationMs = dur))
                fullTextBuilder.append(word)
            }
            return LyricLine(
                timestampMs = lineTimestampMs,
                text = fullTextBuilder.toString(),
                words = words,
            )
        } else {
            val tagMatches = timeTagRegex.findAll(afterTimestamp).toList()
            if (tagMatches.isEmpty()) {
                val text = afterTimestamp.trim()
                return if (text.isNotEmpty()) LyricLine(lineTimestampMs, text) else null
            }
            val words = mutableListOf<WordSpan>()
            val fullTextBuilder = StringBuilder()
            var lastEnd = 0
            for (tm in tagMatches) {
                val word = afterTimestamp.substring(lastEnd, tm.range.first)
                val offsetOrAbsStart = tm.groupValues[1].toLongOrNull() ?: 0L
                val dur = tm.groupValues[2].toLongOrNull() ?: 0L
                val relOffset = if (offsetOrAbsStart >= lineTimestampMs) offsetOrAbsStart - lineTimestampMs else offsetOrAbsStart
                if (word.isNotEmpty()) {
                    words.add(WordSpan(word = word, offsetMs = relOffset, durationMs = dur))
                    fullTextBuilder.append(word)
                }
                lastEnd = tm.range.last + 1
            }
            return LyricLine(
                timestampMs = lineTimestampMs,
                text = fullTextBuilder.toString(),
                words = words,
            )
        }
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

        // 结构化 Key-Value 制作属性匹配 (中/日/韩/英) 及 LRC 标签
        if (META_ROLE_REGEX.containsMatchIn(t) || LRC_TAG_REGEX.containsMatchIn(t)) {
            return true
        }

        // 版权与声明关键词过滤
        if (META_STATEMENT_KEYWORDS.any { t.contains(it, ignoreCase = true) }) {
            return true
        }

        // 前奏前 6 秒内的歌曲-歌手标题行 (要求两边带空格，且排除句首破折号)
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
