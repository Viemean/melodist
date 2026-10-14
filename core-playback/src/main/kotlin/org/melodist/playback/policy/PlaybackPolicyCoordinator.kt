package org.melodist.playback.policy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 播放流策略调度协调器。
 * 集中管理并决议时长、初始音质、探针许可、音质切换拦截及展示徽标。
 */
class PlaybackPolicyCoordinator(
    scope: CoroutineScope,
    currentRoomFlow: StateFlow<*>,
    isBothVipFlow: StateFlow<Boolean>,
    currentSongFlow: StateFlow<Song?>,
    private val policies: List<PlaybackStreamPolicy>,
) {
    private val sortedPolicies = policies.sortedByDescending { it.priority }

    val isListenTogetherTrialActive: StateFlow<Boolean> =
        combine(
            currentRoomFlow,
            isBothVipFlow,
            currentSongFlow,
        ) { room, bothVip, song ->
            room != null && !bothVip && song?.isVip == true
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = false,
        )

    /**
     * 获取针对当前歌曲首个激活的策略。
     */
    fun getActivePolicy(song: Song?): PlaybackStreamPolicy? = sortedPolicies.firstOrNull { it.isActive(song) }

    /**
     * 判断指定歌曲是否处于试听流策略激活状态。
     */
    fun isTrialActive(song: Song?): Boolean {
        val s = song ?: return false
        return sortedPolicies.firstOrNull { it.isActive(s) } is ListenTogetherTrialPolicy
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
    }

    /**
     * 决议当前有效播放时长（毫秒）。
     */
    fun resolveDurationMs(song: Song): Long {
        for (policy in sortedPolicies) {
            if (policy.isActive(song)) {
                val duration = policy.resolveDurationMs(song)
                if (duration != null) return duration
            }
        }
        return if (song.durationSeconds > 0) song.durationSeconds * MILLIS_PER_SECOND else 0L
    }

    /**
     * 决议初始播放音质档位。
     */
    fun resolveInitialTier(
        song: Song,
        preferredTier: AudioQualityTier,
    ): AudioQualityTier? {
        for (policy in sortedPolicies) {
            if (policy.isActive(song)) {
                val tier = policy.resolveInitialTier(song, preferredTier)
                if (tier != null) return tier
            }
        }
        return null
    }

    /**
     * 检查当前歌曲是否允许执行音质探针任务。
     */
    fun isQualityProbeAllowed(song: Song): Boolean {
        for (policy in sortedPolicies) {
            if (policy.isActive(song)) {
                if (!policy.isQualityProbeAllowed(song)) return false
            }
        }
        return true
    }

    /**
     * 决议当前歌曲是否应绕过离线或已下载缓存。
     */
    fun shouldBypassCache(song: Song): Boolean {
        for (policy in sortedPolicies) {
            if (policy.isActive(song) && policy.shouldBypassCache(song)) return true
        }
        return false
    }

    /**
     * 决议当前歌曲是否强制拉取试听流。
     */
    fun isForceTrial(song: Song): Boolean {
        for (policy in sortedPolicies) {
            if (policy.isActive(song) && policy.isForceTrial(song)) return true
        }
        return false
    }

    /**
     * 决议当前歌曲是否应绕过预拉取 URL。
     */
    fun shouldBypassPrefetch(song: Song): Boolean {
        for (policy in sortedPolicies) {
            if (policy.isActive(song) && policy.shouldBypassPrefetch(song)) return true
        }
        return false
    }

    /**
     * 评估是否拦截当前音质切换。
     *
     * @return 拦截时返回提示文案；返回 null 表示放行
     */
    fun evaluateSwitchTierInterception(
        song: Song,
        targetTier: AudioQualityTier,
    ): String? {
        for (policy in sortedPolicies) {
            if (policy.isActive(song)) {
                val reason = policy.evaluateSwitchTierInterception(song, targetTier)
                if (reason != null) return reason
            }
        }
        return null
    }

    /**
     * 决议当前歌曲界面展示的音质文本徽标。
     */
    fun resolveTierBadgeText(
        song: Song,
        currentTier: AudioQualityTier,
    ): String {
        for (policy in sortedPolicies) {
            if (policy.isActive(song)) {
                val badge = policy.resolveTierBadgeText(song, currentTier)
                if (badge != null) return badge
            }
        }
        return AudioQualityTier.getBadge(currentTier)
    }
}
