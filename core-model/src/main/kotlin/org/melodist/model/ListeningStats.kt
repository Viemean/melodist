package org.melodist.model

import kotlinx.serialization.Serializable

/**
 * 听歌统计周期枚举。
 */
@Serializable
enum class ListeningReportPeriod(val typeCode: Int, val label: String) {
    Day(1, "日"),
    Week(2, "周"),
    Month(3, "月"),
    Year(4, "年");

    companion object {
        fun fromTypeCode(code: Int): ListeningReportPeriod =
            entries.firstOrNull { it.typeCode == code } ?: Day
    }
}

/**
 * 听歌统计分布维度枚举。
 */
@Serializable
enum class ListeningOptionType(val optionCode: Int) {
    Singer(1),
    Genre(2),
    ListenCnt(3);
}

/**
 * 常听歌手统计项。
 */
@Serializable
data class ListeningSingerStat(
    val singerId: Long,
    val singerName: String,
    val singerCoverUrl: String = "",
    val listenTimeSec: Long = 0L,
    val rank: Int = 0,
)

/**
 * 时间刻度图表点位数据。
 */
@Serializable
data class ListeningChartPoint(
    val slot: String,
    val value: Long = 0L,
    val avgValue: Long = 0L,
    val text: String = "",
    val avatarUrl: String = "",
)

/**
 * 高光时刻统计项。
 */
@Serializable
data class ListeningHighlightItem(
    val type: Int = 0,
    val title: String = "",
    val subtitle: String = "",
    val tips: String = "",
    val coverUrl: String = "",
    val repeatCnt: Int = 0,
    val iconUrls: List<String> = emptyList(),
    val jumpUrl: String = "",
)

/**
 * 音乐基因深度解读文本。
 */
@Serializable
data class ListeningTextBlock(
    val text: String = "",
    val colorWords: List<String> = emptyList(),
)

/**
 * 歌曲收听排行项。
 */
@Serializable
data class ListeningRankItem(
    val song: Song,
    val rankNo: Int,
    val listenCnt: Int = 0,
)

/**
 * 综合听歌报告统计数据对象。
 */
@Serializable
data class ListeningReportData(
    val period: ListeningReportPeriod = ListeningReportPeriod.Day,
    val dateKey: String = "",
    val title: String = "",
    val listenTimeSec: Long = 0L,
    val listenCnt: Int = 0,
    val activeHour: Int = 0,
    val percent: Float = 0f,
    val upOrDown: Int = 0,
    val chartPoints: List<ListeningChartPoint> = emptyList(),
    val topSingers: List<ListeningSingerStat> = emptyList(),
    val rankSongs: List<ListeningRankItem> = emptyList(),
    val availableDates: List<String> = emptyList(),
    val highlights: List<ListeningHighlightItem> = emptyList(),
    val textBlock: ListeningTextBlock? = null,
)
