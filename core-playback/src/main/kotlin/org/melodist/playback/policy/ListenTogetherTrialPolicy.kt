package org.melodist.playback.policy

import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 一起听非双 VIP 房间试听流策略。
 * 当房间成员非双方均为 VIP 且播放 VIP 歌曲时，截断时长为 60 秒，强制使用标准音质，跳过探针并阻止切换音质。
 */
class ListenTogetherTrialPolicy(
    private val isInRoomProvider: () -> Boolean,
    private val isBothVipProvider: () -> Boolean,
) : PlaybackStreamPolicy {
    override val name: String = "ListenTogetherTrial"

    override val priority: Int = 100

    override fun isActive(song: Song?): Boolean {
        if (song == null) return false
        return isInRoomProvider() && !isBothVipProvider() && song.isVip
    }

    override fun resolveDurationMs(song: Song): Long = 60_000L

    override fun resolveInitialTier(
        song: Song,
        preferredTier: AudioQualityTier,
    ): AudioQualityTier = AudioQualityTier.Standard

    override fun isQualityProbeAllowed(song: Song): Boolean = false

    override fun shouldBypassCache(song: Song): Boolean = true

    override fun isForceTrial(song: Song): Boolean = true

    override fun shouldBypassPrefetch(song: Song): Boolean = true

    override fun evaluateSwitchTierInterception(
        song: Song,
        targetTier: AudioQualityTier,
    ): String = "试听音乐不支持切换音质哦"

    override fun resolveTierBadgeText(
        song: Song,
        currentTier: AudioQualityTier,
    ): String = "试听"
}
