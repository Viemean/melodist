package org.melodist.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
}
