package org.melodist.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArtistAlbumCacheManagerTest {
    @Test
    fun testTimedLruCacheHitAndExpiration() {
        var currentTime = 1000L
        val cache = TimedLruCache<String, String>(
            maxSize = 3,
            ttlMillis = 30_000L,
            timeProvider = { currentTime },
        )

        cache.put("k1", "v1")
        assertEquals("v1", cache.get("k1"))

        // 前进 29 秒，仍应命中
        currentTime += 29_000L
        assertEquals("v1", cache.get("k1"))

        // 前进 2 秒（累计 31 秒），超过 30 秒应失效返回 null
        currentTime += 2_000L
        assertNull(cache.get("k1"))
        assertEquals(0, cache.size)
    }

    @Test
    fun testTimedLruCacheCapacityEviction() {
        var currentTime = 1000L
        val cache = TimedLruCache<String, String>(
            maxSize = 2,
            ttlMillis = 30_000L,
            timeProvider = { currentTime },
        )

        cache.put("k1", "v1")
        cache.put("k2", "v2")
        assertEquals(2, cache.size)

        // 访问 k1，使 k2 变为最久未访问 (LRU)
        assertEquals("v1", cache.get("k1"))

        // 插入 k3，超出容量 2，应淘汰 k2
        cache.put("k3", "v3")
        assertEquals(2, cache.size)
        assertEquals("v1", cache.get("k1"))
        assertNull(cache.get("k2"))
        assertEquals("v3", cache.get("k3"))
    }

    @Test
    fun testTimedLruCacheClear() {
        val cache = TimedLruCache<String, String>(
            maxSize = 5,
            ttlMillis = 30_000L,
        )
        cache.put("k1", "v1")
        cache.put("k2", "v2")
        assertEquals(2, cache.size)

        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get("k1"))
    }
}
