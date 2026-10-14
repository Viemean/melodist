package org.melodist.model

import kotlinx.serialization.Serializable

/**
 * 一起听房间状态与元数据实体。
 */
@Serializable
data class ListenTogetherRoomInfo(
    val showId: String = "",
    val creatorUin: String = "",
    val shareUrl: String = "",
    val currentSongId: Long = 0L,
    val currentSongTitle: String = "",
    val usersCount: Int = 0,
    val isValid: Boolean = true,
)

/**
 * 一起听心跳同步状态数据。
 */
@Serializable
data class ListenTogetherSyncState(
    val showId: String = "",
    val currentSongId: Long = 0L,
    val songTitle: String = "",
    val playStatus: Int = 0, // 1: 播放中, 2: 暂停
    val songOffsetMs: Long = 0L,
    val songPlayVersion: Long = 0L,
    val mediaVersion: Long = 0L,
    val usersCount: Int = 0,
    val isRoomValid: Boolean = true,
    val userEncUins: List<String> = emptyList(),
    val userVipMap: Map<String, Boolean> = emptyMap(),
)

/**
 * 一起听历史房间记录条目。
 */
@Serializable
data class ListenTogetherHistoryRecord(
    val showId: String = "",
    val createTime: Long = 0L,
    val closeTime: Long = 0L,
    val friendNick: String = "",
    val friendAvatar: String = "",
    val lastSongName: String = "",
    val lastSinger: String = "",
)

/**
 * 一起听好友空间统计数据。
 */
@Serializable
data class ListenTogetherSpaceStats(
    val listenMinutes: Long = 0L,
    val listenSongsCount: Int = 0,
    val favoriteSongsCount: Int = 0,
)
