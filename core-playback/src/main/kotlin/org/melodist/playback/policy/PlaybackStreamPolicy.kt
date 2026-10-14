package org.melodist.playback.policy

import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

/**
 * 播放流生命周期策略接口。
 * 允许在播放决策各环节注入业务规则（如试听限制、网络限额、本地文件等）。
 */
interface PlaybackStreamPolicy {
    /**
     * 策略名称标识。
     */
    val name: String

    /**
     * 策略优先级（数值越大越优先评估）。
     */
    val priority: Int get() = 0

    /**
     * 当前策略是否针对指定歌曲处于激活状态。
     */
    fun isActive(song: Song?): Boolean = false

    /**
     * 决议当前有效播放时长（毫秒）。
     *
     * @return 干预后的时长；返回 null 表示不干预，使用歌曲原本时长
     */
    fun resolveDurationMs(song: Song): Long? = null

    /**
     * 决议初始播放音质档位。
     *
     * @return 干预后的音质；返回 null 表示不干预，使用全局配置与缓存推导音质
     */
    fun resolveInitialTier(
        song: Song,
        preferredTier: AudioQualityTier,
    ): AudioQualityTier? = null

    /**
     * 是否允许发起网络音质探针。
     */
    fun isQualityProbeAllowed(song: Song): Boolean = true

    /**
     * 是否应绕过本地或已下载的高规格缓存。
     * 防止限制播放场景命中完整歌曲的高品质离线缓存。
     */
    fun shouldBypassCache(song: Song): Boolean = false

    /**
     * 是否强制请求试听流。
     */
    fun isForceTrial(song: Song): Boolean = false

    /**
     * 是否应绕过预拉取的音频 URL。
     */
    fun shouldBypassPrefetch(song: Song): Boolean = false

    /**
     * 评估是否拦截当前音质切换。
     *
     * @return 拦截时返回提示文案；返回 null 表示放行
     */
    fun evaluateSwitchTierInterception(
        song: Song,
        targetTier: AudioQualityTier,
    ): String? = null

    /**
     * 决议界面展示的音质文本徽标。
     *
     * @return 干预后的徽标文本；返回 null 表示不干预，按标准 Tier 徽标展示
     */
    fun resolveTierBadgeText(
        song: Song,
        currentTier: AudioQualityTier,
    ): String? = null
}
