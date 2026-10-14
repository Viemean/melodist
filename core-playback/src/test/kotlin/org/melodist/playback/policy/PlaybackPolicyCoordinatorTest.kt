package org.melodist.playback.policy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song

class PlaybackPolicyCoordinatorTest {
    private fun createSong(
        mid: String,
        isVip: Boolean,
        durationSeconds: Int = 180,
    ): Song =
        Song(
            songId = mid.hashCode().toLong(),
            songMid = mid,
            name = mid,
            singer = "Singer",
            album = "Album",
            isVip = isVip,
            durationSeconds = durationSeconds,
        )

    @Test
    fun `test ListenTogetherTrialPolicy behavior when active and inactive`() {
        var isInRoom = false
        var isBothVip = true
        val policy =
            ListenTogetherTrialPolicy(
                isInRoomProvider = { isInRoom },
                isBothVipProvider = { isBothVip },
            )

        val vipSong = createSong("vip_track", isVip = true, durationSeconds = 240)
        val freeSong = createSong("free_track", isVip = false, durationSeconds = 180)

        // 场景 1：未进房时，不激活
        assertFalse(policy.isActive(vipSong))

        // 场景 2：在房且双 VIP 时，不激活
        isInRoom = true
        isBothVip = true
        assertFalse(policy.isActive(vipSong))

        // 场景 3：在房但非双 VIP，播放免费歌曲时，不激活
        isBothVip = false
        assertFalse(policy.isActive(freeSong))

        // 场景 4：在房但非双 VIP，播放 VIP 歌曲，策略激活
        assertTrue(policy.isActive(vipSong))
        assertEquals(60_000L, policy.resolveDurationMs(vipSong))
        assertEquals(AudioQualityTier.Standard, policy.resolveInitialTier(vipSong, AudioQualityTier.Master))
        assertFalse(policy.isQualityProbeAllowed(vipSong))
        assertTrue(policy.shouldBypassCache(vipSong))
        assertTrue(policy.isForceTrial(vipSong))
        assertTrue(policy.shouldBypassPrefetch(vipSong))
        assertEquals("试听音乐不支持切换音质哦", policy.evaluateSwitchTierInterception(vipSong, AudioQualityTier.HQ))
        assertEquals("试听", policy.resolveTierBadgeText(vipSong, AudioQualityTier.Standard))
    }

    @Test
    fun `test PlaybackPolicyCoordinator resolves fallback when no policy active`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val roomFlow = MutableStateFlow<String?>(null)
        val bothVipFlow = MutableStateFlow(true)
        val currentSongFlow = MutableStateFlow<Song?>(null)

        val policy =
            ListenTogetherTrialPolicy(
                isInRoomProvider = { roomFlow.value != null },
                isBothVipProvider = { bothVipFlow.value },
            )

        val coordinator =
            PlaybackPolicyCoordinator(
                scope = scope,
                currentRoomFlow = roomFlow,
                isBothVipFlow = bothVipFlow,
                currentSongFlow = currentSongFlow,
                policies = listOf(policy),
            )

        val normalSong = createSong("normal_track", isVip = false, durationSeconds = 200)

        assertEquals(200_000L, coordinator.resolveDurationMs(normalSong))
        assertNull(coordinator.resolveInitialTier(normalSong, AudioQualityTier.HiRes))
        assertTrue(coordinator.isQualityProbeAllowed(normalSong))
        assertFalse(coordinator.shouldBypassCache(normalSong))
        assertFalse(coordinator.isForceTrial(normalSong))
        assertFalse(coordinator.shouldBypassPrefetch(normalSong))
        assertNull(coordinator.evaluateSwitchTierInterception(normalSong, AudioQualityTier.SQ))
        assertEquals("母带", coordinator.resolveTierBadgeText(normalSong, AudioQualityTier.Master))
        assertFalse(coordinator.isTrialActive(normalSong))
        assertFalse(coordinator.isListenTogetherTrialActive.value)

        // 模拟进房且非双 VIP 播放 VIP
        val vipSong = createSong("vip_track", isVip = true, durationSeconds = 300)
        roomFlow.value = "room_1"
        bothVipFlow.value = false
        currentSongFlow.value = vipSong

        assertTrue(coordinator.isTrialActive(vipSong))
        assertTrue(coordinator.isListenTogetherTrialActive.value)
        assertEquals(60_000L, coordinator.resolveDurationMs(vipSong))
        assertEquals(AudioQualityTier.Standard, coordinator.resolveInitialTier(vipSong, AudioQualityTier.Master))
        assertFalse(coordinator.isQualityProbeAllowed(vipSong))
        assertTrue(coordinator.shouldBypassCache(vipSong))
        assertTrue(coordinator.isForceTrial(vipSong))
        assertTrue(coordinator.shouldBypassPrefetch(vipSong))
        assertEquals("试听音乐不支持切换音质哦", coordinator.evaluateSwitchTierInterception(vipSong, AudioQualityTier.SQ))
        assertEquals("试听", coordinator.resolveTierBadgeText(vipSong, AudioQualityTier.Standard))
    }
}
