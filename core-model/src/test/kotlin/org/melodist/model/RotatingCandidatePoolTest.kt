package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RotatingCandidatePoolTest {
    @Test
    fun `buildCandidatePool with less than 30 items returns original list`() {
        val list = (1..20).toList()
        val result = RotatingCandidatePool.buildCandidatePool(list, fixedCount = 5, randomCount = 25)
        assertEquals(list, result)
    }

    @Test
    fun `buildCandidatePool with more than 30 items keeps first 5 and takes 25 random from remainder`() {
        val list = (1..100).toList()
        val result = RotatingCandidatePool.buildCandidatePool(list, fixedCount = 5, randomCount = 25)
        assertEquals(30, result.size)
        // 前 5 项必须完全保留
        assertEquals((1..5).toList(), result.take(5))
        // 后 25 项必须来自于 6..100
        val remaining = result.drop(5)
        assertEquals(25, remaining.size)
        assertTrue(remaining.all { it in 6..100 })
        assertEquals(25, remaining.toSet().size)
    }

    @Test
    fun `createShuffledQueue avoids immediate duplicate with lastItem`() {
        val pool = listOf(1, 2, 3, 4, 5)
        for (i in 1..20) {
            val shuffled = RotatingCandidatePool.createShuffledQueue(pool, lastItem = 1)
            assertEquals(5, shuffled.size)
            assertNotEquals(1, shuffled.first())
        }
    }

    @Test
    fun `RotatingQueueState retains queue and index when source is identical`() {
        val state = RotatingQueueState<String>()
        val list = listOf("A", "B", "C")
        val updated = state.updateSource(list)
        assertTrue(updated)
        assertEquals(3, state.shuffledQueue.size)

        val firstItem = state.currentItem
        assertNotNull(firstItem)
        // Advance once
        state.advance()
        val secondItem = state.currentItem
        assertEquals(1, state.currentIndex)
        assertNotEquals(firstItem, secondItem)

        // Calling updateSource with identical list should not reset index or re-shuffle
        val reUpdated = state.updateSource(list)
        org.junit.jupiter.api.Assertions
            .assertFalse(reUpdated)
        assertEquals(1, state.currentIndex)
        assertEquals(secondItem, state.currentItem)

        // Advance until wrap
        state.advance() // index 2
        assertEquals(2, state.currentIndex)
        state.advance() // index 0 (wrapped and reshuffled)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `RotatingQueueState with sampling retains candidate pool and index when source is identical`() {
        val state = RotatingQueueState<Int>()
        val list = (1..100).toList()
        val updated = state.updateSource(list, fixedCount = 5, randomCount = 25)
        assertTrue(updated)
        assertEquals(30, state.candidatePool.size)
        assertEquals(30, state.shuffledQueue.size)
        val initialItem = state.currentItem
        assertNotNull(initialItem)

        // Advance once
        state.advance()
        assertEquals(1, state.currentIndex)
        val current = state.currentItem

        // Calling updateSource with same 100 items should not re-sample or reset index
        val reUpdated = state.updateSource(list, fixedCount = 5, randomCount = 25)
        org.junit.jupiter.api.Assertions.assertFalse(reUpdated)
        assertEquals(1, state.currentIndex)
        assertEquals(current, state.currentItem)
    }
}
