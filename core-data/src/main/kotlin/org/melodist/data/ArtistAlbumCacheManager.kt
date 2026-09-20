package org.melodist.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.getAlbumDetail
import org.melodist.api.getArtistDetail
import org.melodist.api.getSingerAlbumList
import org.melodist.api.getSingerSongList
import org.melodist.model.Album
import org.melodist.model.AlbumDetail
import org.melodist.model.ArtistDetail
import org.melodist.model.Song

/**
 * 通用带时间戳与容量上限的内存 LRU 缓存容器
 */
class TimedLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val ttlMillis: Long = 30_000L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {
    private data class CacheEntry<V>(
        val timestamp: Long,
        val value: V,
    )

    private val lock = Any()
    private val map =
        object : LinkedHashMap<K, CacheEntry<V>>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean = size > maxSize
        }

    fun get(key: K): V? =
        synchronized(lock) {
            val entry = map[key] ?: return null
            if (timeProvider() - entry.timestamp > ttlMillis) {
                map.remove(key)
                return null
            }
            return entry.value
        }

    fun put(
        key: K,
        value: V,
    ) = synchronized(lock) {
        map[key] = CacheEntry(timeProvider(), value)
    }

    fun remove(key: K): V? =
        synchronized(lock) {
            map.remove(key)?.value
        }

    fun clear() =
        synchronized(lock) {
            map.clear()
        }

    val size: Int get() = synchronized(lock) { map.size }
}

/**
 * 歌手与专辑详情及首页列表短时缓存管理器
 * 默认缓存 30 秒，上限 20 条，避免反复进出页面造成频繁网络请求与白屏
 */
object ArtistAlbumCacheManager {
    const val DEFAULT_TTL_MILLIS = 30_000L
    const val DEFAULT_MAX_SIZE = 20

    internal var timeProvider: () -> Long = { System.currentTimeMillis() }

    internal val albumDetailCache = TimedLruCache<String, AlbumDetail>(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS) { timeProvider() }
    internal val artistDetailCache = TimedLruCache<String, ArtistDetail>(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS) { timeProvider() }
    internal val artistSongsCache = TimedLruCache<String, Pair<List<Song>, Int>>(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS) { timeProvider() }
    internal val artistAlbumsCache = TimedLruCache<String, Pair<List<Album>, Int>>(DEFAULT_MAX_SIZE, DEFAULT_TTL_MILLIS) { timeProvider() }

    private val apiService = MusicApiService()

    private fun log(msg: String) {
        try {
            android.util.Log.i("ArtistAlbumCache", msg)
        } catch (_: Throwable) {
            println("[ArtistAlbumCache] $msg")
        }
    }

    suspend fun getAlbumDetail(
        albumMid: String,
        forceRefresh: Boolean = false,
    ): AlbumDetail? =
        withContext(Dispatchers.IO) {
            if (albumMid.isBlank()) return@withContext null
            if (!forceRefresh) {
                albumDetailCache.get(albumMid)?.let {
                    log("[Album] Hit cache: $albumMid")
                    return@withContext it
                }
            }
            log("[Album] Fetch from network: $albumMid, forceRefresh=$forceRefresh")
            val remote = apiService.getAlbumDetail(albumMid)
            if (remote != null) {
                albumDetailCache.put(albumMid, remote)
            }
            remote
        }

    suspend fun getArtistDetail(
        artistMid: String,
        forceRefresh: Boolean = false,
    ): ArtistDetail? =
        withContext(Dispatchers.IO) {
            if (artistMid.isBlank()) return@withContext null
            if (!forceRefresh) {
                artistDetailCache.get(artistMid)?.let {
                    log("[Artist] Hit cache: $artistMid")
                    return@withContext it
                }
            }
            log("[Artist] Fetch from network: $artistMid, forceRefresh=$forceRefresh")
            val remote = apiService.getArtistDetail(artistMid)
            if (remote != null) {
                artistDetailCache.put(artistMid, remote)
            }
            remote
        }

    suspend fun getSingerSongList(
        singerMid: String,
        page: Int = 1,
        pageSize: Int = 30,
        isHotOrder: Boolean = true,
        forceRefresh: Boolean = false,
    ): Pair<List<Song>, Int> =
        withContext(Dispatchers.IO) {
            if (singerMid.isBlank()) return@withContext Pair(emptyList(), 0)
            val cacheKey = "$singerMid:$isHotOrder:$pageSize"

            if (page == 1 && !forceRefresh) {
                artistSongsCache.get(cacheKey)?.let {
                    log("[ArtistSongs] Hit cache: $cacheKey")
                    return@withContext it
                }
            }

            log("[ArtistSongs] Fetch from network: page=$page, key=$cacheKey, forceRefresh=$forceRefresh")
            val remote =
                apiService.getSingerSongList(
                    singerMid = singerMid,
                    page = page,
                    pageSize = pageSize,
                    isHotOrder = isHotOrder,
                )

            if (page == 1 && remote.first.isNotEmpty()) {
                artistSongsCache.put(cacheKey, remote)
            }
            remote
        }

    suspend fun getSingerAlbumList(
        singerMid: String,
        page: Int = 1,
        pageSize: Int = 30,
        forceRefresh: Boolean = false,
    ): Pair<List<Album>, Int> =
        withContext(Dispatchers.IO) {
            if (singerMid.isBlank()) return@withContext Pair(emptyList(), 0)
            val cacheKey = "$singerMid:$pageSize"

            if (page == 1 && !forceRefresh) {
                artistAlbumsCache.get(cacheKey)?.let {
                    log("[ArtistAlbums] Hit cache: $cacheKey")
                    return@withContext it
                }
            }

            log("[ArtistAlbums] Fetch from network: page=$page, key=$cacheKey, forceRefresh=$forceRefresh")
            val remote =
                apiService.getSingerAlbumList(
                    singerMid = singerMid,
                    page = page,
                    pageSize = pageSize,
                )

            if (page == 1 && remote.first.isNotEmpty()) {
                artistAlbumsCache.put(cacheKey, remote)
            }
            remote
        }

    fun clearCache() {
        albumDetailCache.clear()
        artistDetailCache.clear()
        artistSongsCache.clear()
        artistAlbumsCache.clear()
    }
}
