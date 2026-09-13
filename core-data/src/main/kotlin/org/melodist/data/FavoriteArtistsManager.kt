package org.melodist.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.melodist.api.MusicApiService
import org.melodist.api.toggleSingerFollow

object FavoriteArtistsManager {
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
        } catch (_: Exception) {
            _followedArtistMids.value = emptySet()
        }
    }

    fun isFollowed(artistMid: String): Boolean {
        if (artistMid.isBlank()) return false
        return _followedArtistMids.value.contains(artistMid)
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

        // 本地持久化
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putStringSet(KEY_ARTISTS_SET, currentSet).apply()
            } catch (_: Exception) {
            }
        }

        // 云端异步同步
        scope.launch {
            try {
                apiService.toggleSingerFollow(artistMid, willFollow)
            } catch (_: Exception) {
            }
        }

        return willFollow
    }
}
