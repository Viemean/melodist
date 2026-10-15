package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.ListeningChartPoint
import org.melodist.model.ListeningHighlightItem
import org.melodist.model.ListeningMonthlySongItem
import org.melodist.model.ListeningRankItem
import org.melodist.model.ListeningReportData
import org.melodist.model.ListeningReportPeriod
import org.melodist.model.ListeningSingerStat
import org.melodist.model.ListeningTextBlock

/**
 * 拉取用户指定周期（日/周/月/年）的详细听歌数据报告与单曲排行。
 *
 * @param period 统计周期（日/周/月/年）
 * @param date 目标日期字符串（如日统计 "20261012"，月统计 "202610"；为空表示当前最新）
 * @return 听歌统计综合数据对象
 */
suspend fun MusicApiService.getListeningRecordReport(
    period: ListeningReportPeriod = ListeningReportPeriod.Day,
    date: String? = null,
): ListeningReportData =
    withContext(Dispatchers.IO) {
        val uin = getEffectiveUin()
        val numericUin = uin.toLongOrNull() ?: 0L

        val barParam =
            buildJsonObject {
                put("type", period.typeCode)
                date?.takeIf { it.isNotBlank() }?.let { put("date", it) }
                put("optionType", 1)
                put("version", "v2")
            }

        val rankParam =
            buildJsonObject {
                put("type", period.typeCode)
                date?.takeIf { it.isNotBlank() }?.let { put("date", it) }
                put("version", "v2")
            }

        val payload =
            buildJsonObject {
                put(
                    "comm",
                    buildJsonObject {
                        put("cv", 4747474)
                        put("ct", 24)
                        put("format", "json")
                        put("inCharset", "utf-8")
                        put("outCharset", "utf-8")
                        put("notice", 0)
                        put("platform", "yqq.json")
                        put("needNewCode", 1)
                        put("uin", numericUin)
                    },
                )
                put(
                    "req_bar",
                    buildJsonObject {
                        put("module", "music.individuation.ListenReportSvr")
                        put("method", "GetBar")
                        put("param", barParam)
                    },
                )
                put(
                    "req_rank",
                    buildJsonObject {
                        put("module", "music.individuation.ListenReportSvr")
                        put("method", "GetRank")
                        put("param", rankParam)
                    },
                )
            }.toString()

        try {
            val responseStr = postGateway(payload)
            parseListeningRecordReport(responseStr, period, date ?: "")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiListeningStats", "getListeningRecordReport failed: period=$period, date=$date", e)
            ListeningReportData(period = period, dateKey = date ?: "")
        }
    }

internal val JsonElement?.asJsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

internal val JsonElement?.asJsonArrayOrNull: JsonArray?
    get() = this as? JsonArray

internal val JsonElement?.asJsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive

internal fun parseListeningRecordReport(
    jsonStr: String,
    period: ListeningReportPeriod,
    requestDate: String,
): ListeningReportData {
    val root = Json.parseToJsonElement(jsonStr).asJsonObjectOrNull ?: return ListeningReportData(period = period, dateKey = requestDate)

    val barData = root["req_bar"].asJsonObjectOrNull?.get("data").asJsonObjectOrNull
    val rankData = root["req_rank"].asJsonObjectOrNull?.get("data").asJsonObjectOrNull

    val availableDates =
        barData?.get("availableDates").asJsonArrayOrNull?.mapNotNull {
            it.asJsonPrimitiveOrNull?.contentOrNull
        } ?: emptyList()

    val dateKey = requestDate.ifBlank { availableDates.firstOrNull() ?: "" }

    val todayObj = barData?.get("today").asJsonObjectOrNull
    val weekObj = barData?.get("week").asJsonObjectOrNull
    val barObj = barData?.get("bar").asJsonObjectOrNull
    val rootListenTime = barData?.get("listenTime").asJsonObjectOrNull

    val listenTimeSec: Long
    val listenCnt: Int
    val activeHour: Int
    val percent: Float
    val upOrDown: Int
    val title: String

    when (period) {
        ListeningReportPeriod.Day -> {
            val ltObj = todayObj?.get("listenTime").asJsonObjectOrNull ?: rootListenTime
            listenTimeSec = ltObj?.get("listenTimeSec").asJsonPrimitiveOrNull?.longOrNull ?: 0L
            listenCnt = ltObj?.get("listenCnt").asJsonPrimitiveOrNull?.intOrNull ?: 0
            activeHour = ltObj?.get("activeHour").asJsonPrimitiveOrNull?.intOrNull ?: 0
            percent = ltObj?.get("percent").asJsonPrimitiveOrNull?.floatOrNull ?: 0f
            upOrDown = ltObj?.get("upOrDown").asJsonPrimitiveOrNull?.intOrNull ?: 0
            title = todayObj
                ?.get("title")
                .asJsonPrimitiveOrNull
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "今日听歌时长"
        }

        ListeningReportPeriod.Week -> {
            val ltObj = weekObj?.get("listenTime").asJsonObjectOrNull ?: rootListenTime
            listenTimeSec = ltObj?.get("listenTimeSec").asJsonPrimitiveOrNull?.longOrNull ?: 0L
            listenCnt = ltObj?.get("listenCnt").asJsonPrimitiveOrNull?.intOrNull ?: 0
            activeHour = ltObj?.get("activeHour").asJsonPrimitiveOrNull?.intOrNull ?: 0
            percent = ltObj?.get("percent").asJsonPrimitiveOrNull?.floatOrNull ?: 0f
            upOrDown = ltObj?.get("upOrDown").asJsonPrimitiveOrNull?.intOrNull ?: 0
            title = weekObj
                ?.get("title")
                .asJsonPrimitiveOrNull
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "本周日均"
        }

        ListeningReportPeriod.Month -> {
            val ltObj = rootListenTime
            listenTimeSec = ltObj?.get("listenTimeSec").asJsonPrimitiveOrNull?.longOrNull ?: 0L
            listenCnt = ltObj?.get("listenCnt").asJsonPrimitiveOrNull?.intOrNull ?: 0
            activeHour = 0
            percent = ltObj?.get("percent").asJsonPrimitiveOrNull?.floatOrNull ?: 0f
            upOrDown = ltObj?.get("upOrDown").asJsonPrimitiveOrNull?.intOrNull ?: 0
            val monthNum = dateKey.takeLast(2).toIntOrNull()
            title = if (monthNum != null) "${monthNum}月听歌首数" else "本月听歌首数"
        }

        ListeningReportPeriod.Year -> {
            val ltObj = rootListenTime
            listenTimeSec = ltObj?.get("listenTimeSec").asJsonPrimitiveOrNull?.longOrNull ?: 0L
            listenCnt = ltObj?.get("listenCnt").asJsonPrimitiveOrNull?.intOrNull ?: 0
            activeHour = 0
            percent = ltObj?.get("percent").asJsonPrimitiveOrNull?.floatOrNull ?: 0f
            upOrDown = ltObj?.get("upOrDown").asJsonPrimitiveOrNull?.intOrNull ?: 0
            title = "年度听歌时长"
        }
    }

    val chartPoints =
        when (period) {
            ListeningReportPeriod.Day -> {
                val chartInfoObj =
                    todayObj
                        ?.get("chart")
                        .asJsonObjectOrNull
                        ?.get("chartInfo")
                        .asJsonObjectOrNull
                chartInfoObj
                    ?.entries
                    ?.mapNotNull { (slot, element) ->
                        val ptObj = element.asJsonObjectOrNull ?: return@mapNotNull null
                        val value = ptObj["value"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                        val avg = ptObj["avgValue"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                        val text = ptObj["text"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                        ListeningChartPoint(slot = slot, value = value, avgValue = avg, text = text)
                    }?.sortedBy { it.slot.toIntOrNull() ?: 0 } ?: emptyList()
            }

            ListeningReportPeriod.Week -> {
                val chartInfoObj =
                    weekObj
                        ?.get("chart")
                        .asJsonObjectOrNull
                        ?.get("chartInfo")
                        .asJsonObjectOrNull
                val weekDaysOrder = listOf("1" to "一", "2" to "二", "3" to "三", "4" to "四", "5" to "五", "6" to "六", "0" to "日")
                if (chartInfoObj != null) {
                    weekDaysOrder.map { (slotKey, label) ->
                        val ptObj = chartInfoObj[slotKey].asJsonObjectOrNull
                        val value = ptObj?.get("value").asJsonPrimitiveOrNull?.longOrNull ?: 0L
                        val avg = ptObj?.get("avgValue").asJsonPrimitiveOrNull?.longOrNull ?: 0L
                        ListeningChartPoint(slot = slotKey, value = value, avgValue = avg, text = label)
                    }
                } else {
                    emptyList()
                }
            }

            ListeningReportPeriod.Month -> {
                val optResultsObj = barObj?.get("optionResults").asJsonObjectOrNull
                optResultsObj?.entries?.sortedBy { it.key }?.mapNotNull { (monthKey, arrElem) ->
                    val firstItem = arrElem.asJsonArrayOrNull?.firstOrNull().asJsonObjectOrNull ?: return@mapNotNull null
                    val value = firstItem["value"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                    val avatar = firstItem["singerAvatar"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                    val mNum = monthKey.takeLast(2).toIntOrNull()
                    val label = if (mNum != null) "${mNum}月" else monthKey
                    ListeningChartPoint(slot = monthKey, value = value, text = label, avatarUrl = avatar)
                } ?: emptyList()
            }

            ListeningReportPeriod.Year -> {
                val optResultsObj = barObj?.get("optionResults").asJsonObjectOrNull
                optResultsObj?.entries?.sortedBy { it.key }?.mapNotNull { (yearKey, arrElem) ->
                    val firstItem = arrElem.asJsonArrayOrNull?.firstOrNull().asJsonObjectOrNull ?: return@mapNotNull null
                    val value = firstItem["value"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                    val avatar = firstItem["singerAvatar"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                    ListeningChartPoint(slot = yearKey, value = value, text = yearKey, avatarUrl = avatar)
                } ?: emptyList()
            }
        }

    val rawSingers =
        todayObj?.get("singers").asJsonArrayOrNull
            ?: weekObj?.get("singers").asJsonArrayOrNull
            ?: barData?.get("singers").asJsonArrayOrNull

    val topSingers =
        if (rawSingers != null && rawSingers.isNotEmpty()) {
            rawSingers.mapIndexedNotNull { index, elem ->
                val sObj = elem.asJsonObjectOrNull ?: return@mapIndexedNotNull null
                val id = sObj["id"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                val name = sObj["name"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                val cover = sObj["cover"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                val dur = sObj["listenTimeSec"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                if (name.isNotBlank()) {
                    ListeningSingerStat(
                        singerId = id,
                        singerName = name,
                        singerCoverUrl = cover,
                        listenTimeSec = dur,
                        rank = index + 1,
                    )
                } else {
                    null
                }
            }
        } else {
            barData?.get("optionResults").asJsonArrayOrNull?.mapIndexedNotNull { index, elem ->
                val sObj = elem.asJsonObjectOrNull ?: return@mapIndexedNotNull null
                val id = sObj["id"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                val name = sObj["name"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                val cover = sObj["singerAvatar"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
                val dur = sObj["value"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
                if (name.isNotBlank()) {
                    ListeningSingerStat(
                        singerId = id,
                        singerName = name,
                        singerCoverUrl = cover,
                        listenTimeSec = dur,
                        rank = index + 1,
                    )
                } else {
                    null
                }
            } ?: emptyList()
        }

    val highlights =
        barData?.get("highLights").asJsonArrayOrNull?.mapNotNull { elem ->
            val hObj = elem.asJsonObjectOrNull ?: return@mapNotNull null
            val type = hObj["type"].asJsonPrimitiveOrNull?.intOrNull ?: 0
            val hTitle = hObj["title"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
            val subtitle = hObj["subtitle"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
            val tips = hObj["tips"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
            val cover = hObj["cover"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
            val repCnt = hObj["repeatCnt"].asJsonPrimitiveOrNull?.intOrNull ?: 0
            val jump = hObj["jumpTo"].asJsonPrimitiveOrNull?.contentOrNull ?: ""
            val iconUrls =
                hObj["iconsUrl"].asJsonArrayOrNull?.mapNotNull {
                    it.asJsonPrimitiveOrNull?.contentOrNull
                } ?: emptyList()
            if (hTitle.isNotBlank() || subtitle.isNotBlank()) {
                ListeningHighlightItem(
                    type = type,
                    title = hTitle,
                    subtitle = subtitle,
                    tips = tips,
                    coverUrl = cover,
                    repeatCnt = repCnt,
                    iconUrls = iconUrls,
                    jumpUrl = jump,
                )
            } else {
                null
            }
        } ?: emptyList()

    val textBlockObj = barData?.get("textBlock").asJsonObjectOrNull
    val textContent = textBlockObj?.get("text").asJsonPrimitiveOrNull?.contentOrNull
    val textBlock =
        if (!textContent.isNullOrBlank()) {
            val colorWords =
                textBlockObj?.get("colorWords").asJsonArrayOrNull?.mapNotNull {
                    it.asJsonPrimitiveOrNull?.contentOrNull
                } ?: emptyList()
            ListeningTextBlock(text = textContent, colorWords = colorWords)
        } else {
            null
        }

    val rawRankArray = rankData?.get("rank").asJsonArrayOrNull
    val rankSongs =
        rawRankArray?.mapIndexedNotNull { index, elem ->
            val itemObj = elem.asJsonObjectOrNull ?: return@mapIndexedNotNull null
            val trackElem = itemObj["track"] ?: elem
            val song = MusicApiService.parseSongFromElement(trackElem)
            val cnt = itemObj["listenCnt"].asJsonPrimitiveOrNull?.intOrNull ?: 0
            if (song != null) {
                ListeningRankItem(song = song, rankNo = index + 1, listenCnt = cnt)
            } else {
                null
            }
        } ?: emptyList()

    val songOfMonthObj = barData?.get("SongOfMonth").asJsonObjectOrNull
    val monthlyTopSongs =
        songOfMonthObj
            ?.entries
            ?.mapNotNull { (monthKey, songElem) ->
                val month = monthKey.toIntOrNull() ?: return@mapNotNull null
                val song = MusicApiService.parseSongFromElement(songElem) ?: return@mapNotNull null
                ListeningMonthlySongItem(
                    month = month,
                    song = song,
                    songMid = song.songMid,
                    songName = song.name,
                    singerName = song.singer,
                    coverUrl = song.coverUrl,
                )
            }?.sortedBy { it.month } ?: emptyList()

    return ListeningReportData(
        period = period,
        dateKey = dateKey,
        title = title,
        listenTimeSec = listenTimeSec,
        listenCnt = listenCnt,
        activeHour = activeHour,
        percent = percent,
        upOrDown = upOrDown,
        chartPoints = chartPoints,
        topSingers = topSingers,
        rankSongs = rankSongs,
        availableDates = availableDates,
        highlights = highlights,
        textBlock = textBlock,
        monthlyTopSongs = monthlyTopSongs,
    )
}
