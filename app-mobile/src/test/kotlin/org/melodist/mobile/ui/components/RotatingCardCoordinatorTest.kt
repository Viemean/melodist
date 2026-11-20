package org.melodist.mobile.ui.components

import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RotatingCardCoordinatorTest {
    @Test
    fun `getOrCreate returns identical RotatingQueueState singleton for matching key`() {
        val state1 = RotatingCardCoordinator.getOrCreate<String>("favorites_carousel")
        val state2 = RotatingCardCoordinator.getOrCreate<String>("favorites_carousel")

        assertSame(state1, state2)
    }

    @Test
    fun `getOrCreate returns distinct instances for distinct keys`() {
        val stateA = RotatingCardCoordinator.getOrCreate<String>("key_category_a")
        val stateB = RotatingCardCoordinator.getOrCreate<String>("key_category_b")

        assertNotSame(stateA, stateB)
    }

    @Test
    fun `getOrCreateLazyListState maintains state and removeLazyListState releases instance`() {
        val listState1 = RotatingCardCoordinator.getOrCreateLazyListState("scroll_carousel_1")
        val listState2 = RotatingCardCoordinator.getOrCreateLazyListState("scroll_carousel_1")

        assertSame(listState1, listState2)

        val removed = RotatingCardCoordinator.removeLazyListState("scroll_carousel_1")
        assertSame(listState1, removed)

        val listState3 = RotatingCardCoordinator.getOrCreateLazyListState("scroll_carousel_1")
        assertNotSame(listState1, listState3)

        RotatingCardCoordinator.removeLazyListState("scroll_carousel_1")
    }

    @Test
    fun `removeLazyListState returns null for nonexistent key`() {
        val nonExistent = RotatingCardCoordinator.removeLazyListState("non_existent_key_xyz")
        assertNull(nonExistent)
    }
}
