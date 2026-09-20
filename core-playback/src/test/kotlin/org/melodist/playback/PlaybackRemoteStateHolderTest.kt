package org.melodist.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackRemoteStateHolderTest {
    @Test
    fun `estimated position returns null when remote is inactive`() {
        val holder = PlaybackRemoteStateHolder()
        holder.updateSyncTimeline(positionMs = 1000L, isPlaying = true)
        assertNull(holder.getEstimatedPositionMs(200_000L))
    }

    @Test
    fun `estimated position stays constant when paused`() {
        val holder = PlaybackRemoteStateHolder()
        holder.setRemoteActive(true, "Living Room TV")
        holder.updateSyncTimeline(positionMs = 5000L, isPlaying = false)

        Thread.sleep(50)
        assertEquals(5000L, holder.getEstimatedPositionMs(200_000L))
    }

    @Test
    fun `estimated position extrapolates forward when playing`() {
        val holder = PlaybackRemoteStateHolder()
        holder.setRemoteActive(true, "Living Room TV")
        holder.updateSyncTimeline(positionMs = 10_000L, isPlaying = true)

        Thread.sleep(60)
        val estimated = holder.getEstimatedPositionMs(200_000L)
        assertNotNull(estimated)
        assertTrue(estimated!! >= 10_050L, "Estimated pos should advance with elapsed time: $estimated")
        assertTrue(estimated <= 10_250L, "Estimated pos should not drift unreasonably: $estimated")
    }

    @Test
    fun `estimated position clamps to duration`() {
        val holder = PlaybackRemoteStateHolder()
        holder.setRemoteActive(true, "Living Room TV")
        holder.updateSyncTimeline(positionMs = 199_980L, isPlaying = true)

        Thread.sleep(50)
        val estimated = holder.getEstimatedPositionMs(200_000L)
        assertEquals(200_000L, estimated)
    }

    @Test
    fun `clearing remote playback resets timeline`() {
        val holder = PlaybackRemoteStateHolder()
        holder.setRemoteActive(true, "Living Room TV")
        holder.updateSyncTimeline(positionMs = 10_000L, isPlaying = true)
        holder.clearRemotePlayback()

        assertNull(holder.getEstimatedPositionMs(200_000L))
    }
}
