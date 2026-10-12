package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.melodist.model.SongDiaryData
import org.melodist.model.SongDiaryRecentMonth
import org.melodist.model.SongListenRecordItem
import org.melodist.model.SongListenRecordsPage

/**
 * 拉取指定歌曲的“单曲倒带日记”概览数据。
 *
 * @param songMid 歌曲唯一标识
 * @return 单曲倒带日记概览数据，失败或无数据时返回 null
 */
suspend fun MusicApiService.getSongDiary(songMid: String): SongDiaryData? =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank()) return@withContext null
        val uin = getEffectiveUin()
        val numericUin = uin.toLongOrNull() ?: 0L

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
                    "req_diary",
                    buildJsonObject {
                        put("module", "music.individuation.SongDiarySvr")
                        put("method", "Diary")
                        put("param", buildJsonObject { put("Song", songMid) })
                    },
                )
            }.toString()

        try {
            val responseStr = postGateway(payload)
            parseSongDiary(responseStr, songMid)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiSongDiary", "getSongDiary failed for songMid=$songMid", e)
            null
        }
    }

/**
 * 拉取指定歌曲的历史收听流水记录列表。
 *
 * @param songMid 歌曲唯一标识
 * @param cursor 分页游标（首次传 null 或 ""）
 * @return 历史收听记录分页，失败时返回 null
 */
suspend fun MusicApiService.getSongListenRecords(
    songMid: String,
    cursor: String? = null,
): SongListenRecordsPage? =
    withContext(Dispatchers.IO) {
        if (songMid.isBlank()) return@withContext null
        val uin = getEffectiveUin()
        val numericUin = uin.toLongOrNull() ?: 0L

        val paramObj =
            buildJsonObject {
                put("Song", songMid)
                if (!cursor.isNullOrBlank()) {
                    put("Cursor", cursor)
                }
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
                    "req_record",
                    buildJsonObject {
                        put("module", "music.individuation.SongDiarySvr")
                        put("method", "LsnRecord")
                        put("param", paramObj)
                    },
                )
            }.toString()

        try {
            val responseStr = postGateway(payload)
            parseSongListenRecords(responseStr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiSongDiary", "getSongListenRecords failed for songMid=$songMid", e)
            null
        }
    }

/**
 * 解析单曲倒带日记概览响应 JSON 报文。
 *
 * @param jsonStr 网关回包原始 JSON 字符串
 * @param fallbackMid 歌曲唯一标识后备值
 * @return 单曲倒带日记数据对象，解析失败或非预期报文时返回 null
 */
internal fun parseSongDiary(jsonStr: String, fallbackMid: String): SongDiaryData? {
    val root = Json.parseToJsonElement(jsonStr).asJsonObjectOrNull ?: return null
    val reqDiary = root["req_diary"].asJsonObjectOrNull ?: return null
    if (reqDiary["code"].asJsonPrimitiveOrNull?.intOrNull != 0) return null
    val dataObj = reqDiary["data"].asJsonObjectOrNull ?: return null

    val base = dataObj["Base"].asJsonObjectOrNull
    val fav = dataObj["Fav"].asJsonObjectOrNull
    val firstLsn = dataObj["FirstLsn"].asJsonObjectOrNull
    val totalLsn = dataObj["TotalLsn"].asJsonObjectOrNull
    val recentLsn = dataObj["RecentLsn"].asJsonObjectOrNull
    val lastLsn = dataObj["LastLsn"].asJsonObjectOrNull
    val milestone = dataObj["Milestone"].asJsonObjectOrNull

    val songMid = base?.get("Song").asJsonPrimitiveOrNull?.contentOrNull ?: fallbackMid
    val title = base?.get("Title").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val subTitle = base?.get("SubTitle").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val coverUrl = base?.get("Cover").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val favDate = fav?.get("Center").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val favBottom = fav?.get("Bottom").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val firstLsnTimeCn = firstLsn?.get("TimeCn").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val firstLsnTime = firstLsn?.get("Time").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val firstLsnDevice = firstLsn?.get("Device").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val firstLsnDayCnt = firstLsn?.get("DayCnt").asJsonPrimitiveOrNull?.intOrNull ?: 0
    val firstLsnPicUrl = firstLsn?.get("Pic").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val totalCntObj = totalLsn?.get("Cnt").asJsonObjectOrNull
    val totalLsnCountText = totalCntObj?.get("Center").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val totalLsnBottom = totalCntObj?.get("Bottom").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val totalDist = totalLsn?.get("Dist").asJsonObjectOrNull
    val totalLsnYears = totalDist?.get("X").asJsonArrayOrNull?.mapNotNull { it.asJsonPrimitiveOrNull?.contentOrNull }.orEmpty()
    val totalLsnCounts = totalDist?.get("V").asJsonArrayOrNull?.mapNotNull { it.asJsonPrimitiveOrNull?.intOrNull }.orEmpty()

    val recentTimeObj = recentLsn?.get("Time").asJsonObjectOrNull
    val recentLsnCountText = recentTimeObj?.get("Center").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val recentLsnBottom = recentTimeObj?.get("Bottom").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val recentLsnMonths =
        recentLsn?.get("Dates").asJsonArrayOrNull?.mapNotNull { element ->
            val monthObj = element.asJsonObjectOrNull ?: return@mapNotNull null
            val m = monthObj["Month"].asJsonPrimitiveOrNull?.intOrNull ?: return@mapNotNull null
            val days = monthObj["DayFlag"].asJsonArrayOrNull?.mapNotNull { it.asJsonPrimitiveOrNull?.intOrNull }.orEmpty()
            SongDiaryRecentMonth(month = m, activeDays = days)
        }.orEmpty()

    val lastLsnTime = lastLsn?.get("Center").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val lastLsnDate = lastLsn?.get("Bottom").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val milestoneTitle = milestone?.get("Top").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val milestoneTime = milestone?.get("Center").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val milestoneBottom = milestone?.get("Bottom").asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    val hasListenRecord = dataObj["LsnRecord"].asJsonPrimitiveOrNull?.booleanOrNull ?: false

    return SongDiaryData(
        songMid = songMid,
        title = title,
        subTitle = subTitle,
        coverUrl = coverUrl,
        favDate = favDate,
        favBottom = favBottom,
        firstLsnTimeCn = firstLsnTimeCn,
        firstLsnTime = firstLsnTime,
        firstLsnDevice = firstLsnDevice,
        firstLsnDayCnt = firstLsnDayCnt,
        firstLsnPicUrl = firstLsnPicUrl,
        totalLsnCountText = totalLsnCountText,
        totalLsnBottom = totalLsnBottom,
        totalLsnYears = totalLsnYears,
        totalLsnCounts = totalLsnCounts,
        recentLsnCountText = recentLsnCountText,
        recentLsnBottom = recentLsnBottom,
        recentLsnMonths = recentLsnMonths,
        lastLsnTime = lastLsnTime,
        lastLsnDate = lastLsnDate,
        milestoneTitle = milestoneTitle,
        milestoneTime = milestoneTime,
        milestoneBottom = milestoneBottom,
        hasListenRecord = hasListenRecord,
    )
}

/**
 * 解析单曲历史收听记录流水分页响应 JSON 报文。
 *
 * @param jsonStr 网关回包原始 JSON 字符串
 * @return 历史收听记录分页对象，解析失败或非预期报文时返回 null
 */
internal fun parseSongListenRecords(jsonStr: String): SongListenRecordsPage? {
    val root = Json.parseToJsonElement(jsonStr).asJsonObjectOrNull ?: return null
    val reqRecord = root["req_record"].asJsonObjectOrNull ?: return null
    if (reqRecord["code"].asJsonPrimitiveOrNull?.intOrNull != 0) return null
    val dataObj = reqRecord["data"].asJsonObjectOrNull ?: return null

    val items =
        dataObj["Items"].asJsonArrayOrNull?.mapNotNull { element ->
            val itemObj = element.asJsonObjectOrNull ?: return@mapNotNull null
            val ts = itemObj["Timestamp"].asJsonPrimitiveOrNull?.longOrNull ?: 0L
            val device = itemObj["Device"].asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
            val source = itemObj["Source"].asJsonPrimitiveOrNull?.contentOrNull.orEmpty()
            SongListenRecordItem(timestampSec = ts, device = device, source = source)
        }.orEmpty()

    val nextCursor = dataObj["NextCursor"].asJsonPrimitiveOrNull?.contentOrNull.orEmpty()

    return SongListenRecordsPage(items = items, nextCursor = nextCursor)
}
