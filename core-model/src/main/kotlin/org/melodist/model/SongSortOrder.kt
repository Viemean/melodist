package org.melodist.model

import kotlinx.serialization.Serializable

@Serializable
enum class SongSortOrder(
    val label: String,
) {
    DEFAULT("默认排序"),
    NAME("歌曲排序"),
    ARTIST("歌手排序"),
    DATE_ADDED("时间排序"),
}
