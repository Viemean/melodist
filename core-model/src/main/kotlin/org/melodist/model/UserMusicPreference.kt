package org.melodist.model

import kotlinx.serialization.Serializable

/**
 * 听歌偏好细分项分值数据。
 */
@Serializable
data class MusicPreferenceItem(
    val profileKey: String,
    val profileName: String,
    val currentScore: Int,
    val isBlack: Boolean = false,
    val isChangeProfile: Boolean = false,
) {
    companion object {
        const val KEY_FAMILIARITY = "10008" // 歌曲熟悉度
        const val KEY_EMOTION = "10009" // 音乐情绪
        const val KEY_COVERS = "10010" // 翻唱&现场
        const val KEY_ACOUSTIC = "10007" // 纯音乐
        const val KEY_CHINESE = "10003" // 国语
        const val KEY_ENGLISH = "10004" // 英语
        const val KEY_JAPANESE = "10006" // 日语
        const val KEY_KOREAN = "10005" // 韩语
        const val KEY_CANTONESE = "10011" // 粤语
        const val KEY_AI_SONG = "10012" // AI歌曲
    }
}

/**
 * 用户听歌偏好设置概览。
 */
@Serializable
data class UserMusicPreference(
    val updateTime: Long = 0L,
    val isExpired: Boolean = false,
    val scores: List<MusicPreferenceItem> = emptyList(),
) {
    /** 歌曲熟悉度得分 (25: 探索, 50: 现状, 75: 熟悉) */
    val familiarityScore: Int
        get() = scores.firstOrNull { it.profileKey == MusicPreferenceItem.KEY_FAMILIARITY }?.currentScore ?: 50

    /** 音乐情绪得分 (25: 伤感, 50: 现状, 75: 欢快) */
    val emotionScore: Int
        get() = scores.firstOrNull { it.profileKey == MusicPreferenceItem.KEY_EMOTION }?.currentScore ?: 50

    /** 风格偏好列表 (连续 0 ~ 100 分) */
    val styleItems: List<MusicPreferenceItem>
        get() = scores.filter {
            it.profileKey != MusicPreferenceItem.KEY_FAMILIARITY &&
                it.profileKey != MusicPreferenceItem.KEY_EMOTION
        }
}
