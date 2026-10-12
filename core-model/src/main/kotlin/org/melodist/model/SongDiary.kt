package org.melodist.model

import kotlinx.serialization.Serializable

/**
 * 单曲倒带日记整体数据对象。
 */
@Serializable
data class SongDiaryData(
    val songMid: String = "",
    val title: String = "",
    val subTitle: String = "",
    val coverUrl: String = "",
    val favDate: String = "",
    val favBottom: String = "",
    val firstLsnTimeCn: String = "",
    val firstLsnTime: String = "",
    val firstLsnDevice: String = "",
    val firstLsnDayCnt: Int = 0,
    val firstLsnPicUrl: String = "",
    val totalLsnCountText: String = "",
    val totalLsnBottom: String = "",
    val totalLsnYears: List<String> = emptyList(),
    val totalLsnCounts: List<Int> = emptyList(),
    val recentLsnCountText: String = "",
    val recentLsnBottom: String = "",
    val recentLsnMonths: List<SongDiaryRecentMonth> = emptyList(),
    val lastLsnTime: String = "",
    val lastLsnDate: String = "",
    val milestoneTitle: String = "",
    val milestoneTime: String = "",
    val milestoneBottom: String = "",
    val hasListenRecord: Boolean = false,
)

/**
 * 近半年播放打卡月份明细。
 */
@Serializable
data class SongDiaryRecentMonth(
    val month: Int = 0,
    val activeDays: List<Int> = emptyList(),
)

/**
 * 单曲历史收听记录条目。
 */
@Serializable
data class SongListenRecordItem(
    val timestampSec: Long = 0L,
    val device: String = "",
    val source: String = "",
)

/**
 * 单曲历史收听记录分页结果。
 */
@Serializable
data class SongListenRecordsPage(
    val items: List<SongListenRecordItem> = emptyList(),
    val nextCursor: String = "",
)
