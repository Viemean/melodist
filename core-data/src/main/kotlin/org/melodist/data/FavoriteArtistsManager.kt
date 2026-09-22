package org.melodist.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.checkSingerFollowStatus
import org.melodist.api.getFollowedSingerList
import org.melodist.api.toggleSingerFollow

object FavoriteArtistsManager {
    private const val TAG = "FavoriteArtistsManager"
    private const val PREFS_NAME = "melodist_favorite_artists"
    private const val KEY_ARTISTS_SET = "favorite_artist_mids"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var appContext: Context? = null
    private val apiService = MusicApiService()

    private val _followedArtistMids = MutableStateFlow<Set<String>>(emptySet())
    val followedArtistMids: StateFlow<Set<String>> = _followedArtistMids.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        try {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedSet = prefs.getStringSet(KEY_ARTISTS_SET, null).orEmpty()
            _followedArtistMids.value = savedSet.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load favorite artists from prefs", e)
            _followedArtistMids.value = emptySet()
        }

        // 监听登录状态自动触发全量云端关注列表同步
        scope.launch {
            UserSession.profileFlow.collect {
                if (UserSession.isLoggedIn) {
                    syncFromCloud()
                }
            }
        }
    }

    fun isFollowed(artistMid: String): Boolean {
        if (artistMid.isBlank()) return false
        return _followedArtistMids.value.contains(artistMid)
    }

    fun syncFromCloud() {
        if (!UserSession.isLoggedIn) return
        scope.launch {
            try {
                val cloudMids = mutableSetOf<String>()
                var from = 0
                val pageSize = 50
                var hasMore = true
                while (hasMore) {
                    val (artists, more) = apiService.getFollowedSingerList(from = from, size = pageSize)
                    if (artists.isEmpty()) break
                    artists.forEach { cloudMids.add(it.mid) }
                    hasMore = more
                    from += artists.size
                }
                if (cloudMids.isNotEmpty()) {
                    val merged = _followedArtistMids.value.toMutableSet().apply { addAll(cloudMids) }
                    _followedArtistMids.value = merged
                    persist(merged)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun checkStatus(artistMid: String) {
        if (!UserSession.isLoggedIn || artistMid.isBlank()) return
        scope.launch {
            try {
                val isFollowedOnline = apiService.checkSingerFollowStatus(artistMid)
                val currentSet = _followedArtistMids.value.toMutableSet()
                val changed =
                    if (isFollowedOnline) {
                        currentSet.add(artistMid)
                    } else {
                        currentSet.remove(artistMid)
                    }
                if (changed) {
                    _followedArtistMids.value = currentSet
                    persist(currentSet)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun toggleFollow(artistMid: String): Boolean {
        if (artistMid.isBlank()) return false
        val currentSet = _followedArtistMids.value.toMutableSet()
        val willFollow = !currentSet.contains(artistMid)
        if (willFollow) {
            currentSet.add(artistMid)
        } else {
            currentSet.remove(artistMid)
        }
        _followedArtistMids.value = currentSet
        persist(currentSet)

        // 云端异步同步与失败回退
        scope.launch {
            try {
                val success = apiService.toggleSingerFollow(artistMid, willFollow)
                if (!success && UserSession.isLoggedIn) {
                    val rollbackSet = _followedArtistMids.value.toMutableSet()
                    if (willFollow) {
                        rollbackSet.remove(artistMid)
                    } else {
                        rollbackSet.add(artistMid)
                    }
                    _followedArtistMids.value = rollbackSet
                    persist(rollbackSet)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed to sync singer follow status to cloud", e)
            }
        }

        return willFollow
    }

    private fun persist(set: Set<String>) {
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(KEY_ARTISTS_SET, set).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist favorite artists to prefs", e)
            }
        }
    }
}
