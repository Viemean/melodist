package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getAlbumSongs
import org.melodist.api.getFavoriteAlbums
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.getPlaylistSongs
import org.melodist.api.getPlaylists
import org.melodist.data.DailyRecommendCacheManager
import org.melodist.data.UserLibraryCacheManager
import org.melodist.model.Album
import org.melodist.model.AudioQualityTier
import org.melodist.model.PlaybackSourceContext
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.TvSplitPlaybackScaffold
import org.melodist.tv.ui.playlist.*
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

typealias PlaylistScreenCache = org.melodist.tv.ui.playlist.PlaylistScreenCache

enum class PlaylistScreenMode {
    List,
    Player,
}

// 预设/演示歌单数据
private val DefaultSampleSongs =
    listOf(
        Song(
            songId = 101L,
            songMid = "0039MnYb0qxYhV",
            name = "七里香",
            singer = "周杰伦",
            album = "七里香",
            durationSeconds = 299,
            currentTier = AudioQualityTier.Atmos,
            coverUrl = "https://y.qq.com/music/photo_new/T002R300x300M00000333Ukj2Backend.jpg",
        ),
        Song(
            songId = 102L,
            songMid = "003aAP403wPVIe",
            name = "晴天",
            singer = "周杰伦",
            album = "叶惠美",
            durationSeconds = 269,
            currentTier = AudioQualityTier.Master,
        ),
        Song(
            songId = 103L,
            songMid = "002m8J8s0Tvhj2",
            name = "夜曲",
            singer = "周杰伦",
            album = "十一月的萧邦",
            durationSeconds = 226,
            currentTier = AudioQualityTier.HiRes,
        ),
        Song(
            songId = 104L,
            songMid = "002qDynamicTest4",
            name = "青花瓷",
            singer = "周杰伦",
            album = "我很忙",
            durationSeconds = 239,
            currentTier = AudioQualityTier.SQ,
        ),
        Song(
            songId = 105L,
            songMid = "001MapleAutumn5",
            name = "枫",
            singer = "周杰伦",
            album = "11月的萧邦",
            durationSeconds = 275,
            currentTier = AudioQualityTier.SQ,
        ),
        Song(
            songId = 106L,
            songMid = "003NorthRoad006",
            name = "一路向北",
            singer = "周杰伦",
            album = "J III MP3",
            durationSeconds = 292,
            currentTier = AudioQualityTier.SQ,
        ),
        Song(
            songId = 107L,
            songMid = "001RiceFragrance",
            name = "稻香",
            singer = "周杰伦",
            album = "魔杰座",
            durationSeconds = 223,
            currentTier = AudioQualityTier.Dolby,
        ),
        Song(
            songId = 108L,
            songMid = "002StepBackWard8",
            name = "退后",
            singer = "周杰伦",
            album = "依然范特西",
            durationSeconds = 261,
            currentTier = AudioQualityTier.SQ,
        ),
        Song(
            songId = 109L,
            songMid = "004SecretSound09",
            name = "不能说的秘密",
            singer = "周杰伦",
            album = "电影原声带",
            durationSeconds = 296,
            currentTier = AudioQualityTier.Master,
        ),
    )

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistTvScreen(
    surfaceColor: Color = MonetColorExtractor.DefaultSurfaceColor,
    categoryId: String = "favorites",
    title: String = "我喜欢的音乐",
    subtitle: String = "已收藏单曲列表",
    coverUrl: String = "",
    dirId: Long = 0L,
    tid: Long = 0L,
    isFav: Boolean = false,
    albumMid: String = "",
    isFavoritePlaylist: Boolean = (categoryId == "favorites"),
    isReturningFromPlayer: Boolean = false,
    songs: List<Song> = emptyList(),
    currentPlayingSongId: Long? = null,
    onPlayAll: () -> Unit = {},
    onPlayShuffle: () -> Unit = {},
    onSongClick: (Song) -> Unit = {},
    onNavigateToArtist: (String, String) -> Unit = { _, _ -> },
    onNavigateToAlbum: (String, String) -> Unit = { _, _ -> },
    onNavigateToSettings: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val playAllRequester = remember { FocusRequester() }
    val firstSongRequester = remember { FocusRequester() }
    val returnSongRequester = remember { FocusRequester() }
    var isPlayAllFocused by remember { mutableStateOf(true) }
    var lastBackHandledTime by remember { mutableLongStateOf(0L) }
    var actionSong by remember { mutableStateOf<Song?>(null) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackHandledTime < 450L) {
            return@BackHandler
        }
        lastBackHandledTime = now
        if (!isPlayAllFocused) {
            playAllRequester.requestFocus()
            isPlayAllFocused = true
        } else {
            onBack()
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val metrics = rememberTvWindowMetrics()
    val apiService = remember { MusicApiService() }

    val cacheKey = "${categoryId}_${dirId}_${tid}_$albumMid"
    val hasValidCache = PlaylistScreenCache.matches(cacheKey)

    val tvSourceContext = remember(categoryId, dirId, tid, albumMid) {
        when {
            albumMid.isNotBlank() -> PlaybackSourceContext.Album(albumMid = albumMid)
            categoryId == "radar" || categoryId == "favorites" -> null
            tid > 0L -> PlaybackSourceContext.Playlist(tid.toString())
            dirId > 0L && dirId != 201L -> PlaybackSourceContext.Playlist(dirId.toString())
            else -> null
        }
    }

    var playlistSongs by remember(cacheKey) {
        mutableStateOf(if (hasValidCache) PlaylistScreenCache.songs else songs)
    }
    var userPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var selectedPlaylistIndex by remember { mutableIntStateOf(0) }
    var userAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var selectedAlbumIndex by remember { mutableIntStateOf(0) }

    var totalCount by remember(cacheKey) {
        mutableStateOf(if (hasValidCache) PlaylistScreenCache.totalCount else 0)
    }
    var currentPage by remember { mutableStateOf(1) }
    var hasMore by remember(cacheKey) {
        mutableStateOf(if (hasValidCache) PlaylistScreenCache.hasMore else false)
    }
    var isLoading by remember(cacheKey) { mutableStateOf(!hasValidCache && songs.isEmpty()) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var focusedSongIndex by remember { mutableStateOf<Int?>(null) }
    val currentSong by PlaybackManager.currentSong.collectAsState()
    val userProfile by UserSession.profileFlow.collectAsState()

    var lockedCoverUrl by remember(cacheKey) {
        mutableStateOf(
            if (hasValidCache && PlaylistScreenCache.lockedCoverUrl.isNotBlank()) {
                PlaylistScreenCache.lockedCoverUrl
            } else {
                coverUrl
            },
        )
    }
    var lockedAlbumMid by remember(cacheKey) {
        mutableStateOf(
            if (hasValidCache && PlaylistScreenCache.lockedAlbumMid.isNotBlank()) {
                PlaylistScreenCache.lockedAlbumMid
            } else {
                albumMid
            },
        )
    }

    LaunchedEffect(isFavoritePlaylist) {
        if (!isFavoritePlaylist) return@LaunchedEffect
        PlaybackManager.songFavoriteToggledEvent.collect { (song, isFav) ->
            PlaylistScreenCache.onSongFavoriteChanged(song, isFav)
            val current = playlistSongs.toMutableList()
            current.removeAll { it.songMid == song.songMid || (song.songId > 0 && it.songId == song.songId) }
            if (isFav) {
                current.add(0, song)
                totalCount++
                if (song.coverUrl.isNotBlank()) {
                    lockedCoverUrl = song.coverUrl
                }
            } else {
                totalCount = (totalCount - 1).coerceAtLeast(0)
            }
            playlistSongs = current
        }
    }

    fun saveToCache(
        list: List<Song>,
        total: Int,
        more: Boolean,
    ) {
        val finalCover = if (lockedCoverUrl.isNotBlank()) lockedCoverUrl else (list.firstOrNull()?.coverUrl.orEmpty())
        val finalMid = if (lockedAlbumMid.isNotBlank()) lockedAlbumMid else (list.firstOrNull()?.albumMid.orEmpty())
        if (lockedCoverUrl.isBlank() && finalCover.isNotBlank()) lockedCoverUrl = finalCover
        if (lockedAlbumMid.isBlank() && finalMid.isNotBlank()) lockedAlbumMid = finalMid
        PlaylistScreenCache.save(cacheKey, list, total, more, finalCover, finalMid)
    }

    var syncJob: Job? by remember { mutableStateOf(null) }

    fun syncFullPlaylistToPlayback() {
        if (!hasMore && totalCount <= playlistSongs.size) return
        if (syncJob?.isActive == true) return

        syncJob =
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    var p = currentPage + 1
                    var more = true
                    while (more && UserSession.isLoggedIn) {
                        when (categoryId) {
                            "favorites" -> {
                                val res = apiService.getFavoriteSongsDetail(page = p, pageSize = 100)
                                if (res.songs.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        playlistSongs = playlistSongs + res.songs
                                        currentPage = p
                                        hasMore = res.hasMore
                                        saveToCache(playlistSongs, totalCount, hasMore)

                                        val curQueue = PlaybackManager.playlist.value
                                        if (PlaybackManager.queueTag.value == cacheKey &&
                                            curQueue.isNotEmpty() &&
                                            playlistSongs.any {
                                                it.songMid == curQueue.firstOrNull()?.songMid ||
                                                    it.songMid == PlaybackManager.currentSong.value?.songMid
                                            }
                                        ) {
                                            PlaybackManager.appendPlaylist(res.songs, targetTag = cacheKey)
                                        }
                                        PlaybackManager.addFavoriteSongMids(res.songs.map { it.songMid })
                                    }
                                    p++
                                    more = res.hasMore
                                } else {
                                    more = false
                                    withContext(Dispatchers.Main) { hasMore = false }
                                }
                            }
                            "playlists", "playlist_detail" -> {
                                val cur = userPlaylists.getOrNull(selectedPlaylistIndex)
                                val targetDirId = if (dirId > 0L) dirId else cur?.dirId ?: 0L
                                val targetTid = if (tid > 0L) tid else cur?.tid ?: 0L
                                val targetIsFav = if (dirId > 0L || tid > 0L) isFav else cur?.isFav ?: false
                                if (targetDirId > 0L || targetTid > 0L) {
                                    val moreSongs =
                                        apiService.getPlaylistSongs(
                                            dirId = targetDirId,
                                            tid = targetTid,
                                            isFav = targetIsFav,
                                            page = p,
                                            pageSize = 100,
                                        )
                                    if (moreSongs.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            playlistSongs = playlistSongs + moreSongs
                                            currentPage = p
                                            hasMore = moreSongs.size >= 100
                                            saveToCache(playlistSongs, totalCount, hasMore)

                                            val curQueue = PlaybackManager.playlist.value
                                            if (curQueue.isNotEmpty() &&
                                                playlistSongs.any {
                                                    it.songMid == curQueue.firstOrNull()?.songMid ||
                                                        it.songMid == PlaybackManager.currentSong.value?.songMid
                                                }
                                            ) {
                                                PlaybackManager.appendPlaylist(moreSongs)
                                            }
                                        }
                                        p++
                                        more = moreSongs.size >= 100
                                    } else {
                                        more = false
                                        withContext(Dispatchers.Main) { hasMore = false }
                                    }
                                } else {
                                    more = false
                                }
                            }
                            else -> more = false
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("MelodistPlaylist", "Failed to sync full playlist", e)
                }
            }
    }

    fun loadMoreSongs() {
        if (!hasMore || isLoading || isLoadingMore || !UserSession.isLoggedIn) return
        isLoadingMore = true
        coroutineScope.launch {
            try {
                when (categoryId) {
                    "favorites" -> {
                        val nextPage = currentPage + 1
                        val favResult = apiService.getFavoriteSongsDetail(page = nextPage, pageSize = 50)
                        if (favResult.songs.isNotEmpty()) {
                            playlistSongs = playlistSongs + favResult.songs
                            PlaybackManager.addFavoriteSongMids(favResult.songs.map { it.songMid })
                            currentPage = nextPage
                            hasMore = favResult.hasMore
                            if (favResult.total > 0) totalCount = favResult.total
                        } else {
                            hasMore = false
                        }
                    }
                    "radar" -> {
                        val moreSongs = apiService.getGuessRecommendSongs(count = 20)
                        val existingMids = playlistSongs.map { it.songMid }.toSet()
                        val newSongs = moreSongs.filter { it.songMid.isNotBlank() && !existingMids.contains(it.songMid) }
                        if (newSongs.isNotEmpty()) {
                            playlistSongs = playlistSongs + newSongs
                        } else {
                            hasMore = false
                        }
                    }
                    "playlists", "playlist_detail" -> {
                        val cur = userPlaylists.getOrNull(selectedPlaylistIndex)
                        val targetDirId = if (dirId > 0L) dirId else cur?.dirId ?: 0L
                        val targetTid = if (tid > 0L) tid else cur?.tid ?: 0L
                        val targetIsFav = if (dirId > 0L || tid > 0L) isFav else cur?.isFav ?: false
                        if (targetDirId > 0L || targetTid > 0L) {
                            val nextPage = currentPage + 1
                            val moreSongs =
                                apiService.getPlaylistSongs(
                                    dirId = targetDirId,
                                    tid = targetTid,
                                    isFav = targetIsFav,
                                    page = nextPage,
                                    pageSize = 100,
                                )
                            if (moreSongs.isNotEmpty()) {
                                playlistSongs = playlistSongs + moreSongs
                                currentPage = nextPage
                                hasMore = moreSongs.size >= 100
                                saveToCache(playlistSongs, totalCount, hasMore)

                                val curQueue = PlaybackManager.playlist.value
                                if (curQueue.isNotEmpty() &&
                                    playlistSongs.any {
                                        it.songMid == curQueue.firstOrNull()?.songMid ||
                                            it.songMid == PlaybackManager.currentSong.value?.songMid
                                    }
                                ) {
                                    PlaybackManager.appendPlaylist(moreSongs)
                                }
                            } else {
                                hasMore = false
                            }
                        } else {
                            hasMore = false
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                isLoadingMore = false
            }
        }
    }

    val listState = rememberLazyListState()
    val coverSize = (metrics.screenHeightDp * 0.42f).coerceIn(180.dp, 260.dp)

    // 数据加载生命周期
    LaunchedEffect(categoryId, dirId, tid, isFav, albumMid, userProfile, selectedPlaylistIndex, selectedAlbumIndex) {
        syncJob?.cancel()
        syncJob = null
        if (!UserSession.isLoggedIn) {
            playlistSongs = emptyList()
            userPlaylists = emptyList()
            userAlbums = emptyList()
            isLoading = false
            return@LaunchedEffect
        }

        if (hasValidCache && isReturningFromPlayer) {
            playlistSongs = PlaylistScreenCache.songs
            totalCount = PlaylistScreenCache.totalCount
            hasMore = PlaylistScreenCache.hasMore
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        currentPage = 1
        loadError = null

        try {
            when (categoryId) {
                "favorites" -> {
                    val cachedFavs = UserLibraryCacheManager.favoriteSongsFlow.value
                    if (cachedFavs.isNotEmpty()) {
                        playlistSongs = cachedFavs
                        totalCount = cachedFavs.size
                        hasMore = false
                        saveToCache(cachedFavs, cachedFavs.size, false)
                        PlaybackManager.addFavoriteSongMids(cachedFavs.map { it.songMid })
                        // 后台静默执行第一页轻量差分探测
                        launch {
                            try {
                                val updated = UserLibraryCacheManager.probeAndSyncFavoritesFirstPage(apiService)
                                if (updated != cachedFavs && updated.isNotEmpty()) {
                                    playlistSongs = updated
                                    totalCount = updated.size
                                    saveToCache(updated, updated.size, false)
                                    PlaybackManager.addFavoriteSongMids(updated.map { it.songMid })
                                }
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        val favSongs = UserLibraryCacheManager.loadFavoriteSongs(apiService, forceRefresh = false)
                        playlistSongs = favSongs
                        totalCount = favSongs.size
                        hasMore = false
                        saveToCache(favSongs, favSongs.size, false)
                        if (favSongs.isNotEmpty()) {
                            PlaybackManager.addFavoriteSongMids(favSongs.map { it.songMid })
                        }
                    }
                }
                "daily" -> {
                    val cachedData = DailyRecommendCacheManager.recommendFlow.value
                    val currentUin = if (UserSession.isLoggedIn) UserSession.profile.uin else ""
                    val songs =
                        if (DailyRecommendCacheManager.isCacheValidInCycle(cachedData, currentUin)) {
                            cachedData.songs
                        } else {
                            DailyRecommendCacheManager.loadRecommendSongs(apiService).songs
                        }
                    playlistSongs = songs
                    totalCount = songs.size
                    hasMore = false
                    saveToCache(songs, songs.size, false)
                }
                "radar" -> {
                    val radarSongs = apiService.getGuessRecommendSongs(count = 30)
                    playlistSongs = radarSongs
                    totalCount = radarSongs.size
                    hasMore = true
                }
                "million" -> {
                    val cachedData = org.melodist.data.MillionRecommendManager.resultFlow.value
                    val songs =
                        if (cachedData.songs.isNotEmpty()) {
                            cachedData.songs
                        } else {
                            org.melodist.data.MillionRecommendManager
                                .refresh(apiService, forceRefresh = false)
                            org.melodist.data.MillionRecommendManager.resultFlow.value.songs
                        }
                    playlistSongs = songs
                    totalCount = songs.size
                    hasMore = false
                    saveToCache(songs, songs.size, false)
                }
                "recent" -> {
                    val recents = org.melodist.data.RecentPlaybackManager.recentSongsFlow.value
                    playlistSongs = recents
                    totalCount = recents.size
                    hasMore = false
                    saveToCache(recents, recents.size, false)
                    if (UserSession.isLoggedIn) {
                        launch {
                            try {
                                org.melodist.data.RecentPlaybackManager.syncFromCloud(force = false)
                                val updated = org.melodist.data.RecentPlaybackManager.recentSongsFlow.value
                                if (updated.isNotEmpty()) {
                                    playlistSongs = updated
                                    totalCount = updated.size
                                    saveToCache(updated, updated.size, false)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
                "playlists", "playlist_detail" -> {
                    val activePlaylist =
                        if (dirId > 0L || tid > 0L) {
                            null
                        } else {
                            if (userPlaylists.isEmpty()) {
                                userPlaylists =
                                    UserLibraryCacheManager.libraryFlow.value.playlists
                                        .filterNot { it.isMyFavorite }
                                        .ifEmpty { apiService.getPlaylists().filterNot { it.isMyFavorite } }
                            }
                            userPlaylists.getOrNull(selectedPlaylistIndex)
                        }

                    val targetDirId = if (dirId > 0L) dirId else activePlaylist?.dirId ?: 0L
                    val targetTid = if (tid > 0L) tid else activePlaylist?.tid ?: 0L
                    val targetIsFav = if (dirId > 0L || tid > 0L) isFav else activePlaylist?.isFav ?: false
                    val targetCount = if (activePlaylist != null) activePlaylist.songCount else 0

                    if (targetDirId > 0L || targetTid > 0L) {
                        val cached = UserLibraryCacheManager.getCachedPlaylistSongs(targetDirId, targetTid)
                        if (cached != null && cached.isNotEmpty()) {
                            playlistSongs = cached
                            totalCount = if (targetCount > 0) targetCount else cached.size
                            hasMore = false
                            saveToCache(cached, totalCount, false)
                            // 后台智能差分探测第一页
                            launch {
                                try {
                                    val synced =
                                        UserLibraryCacheManager.probeAndSyncPlaylistFirstPage(
                                            apiService,
                                            targetDirId,
                                            targetTid,
                                            targetIsFav,
                                            targetCount,
                                        )
                                    if (synced != cached && synced.isNotEmpty()) {
                                        playlistSongs = synced
                                        totalCount = synced.size
                                        saveToCache(synced, synced.size, false)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        } else {
                            val pSongs =
                                UserLibraryCacheManager.probeAndSyncPlaylistFirstPage(
                                    apiService,
                                    targetDirId,
                                    targetTid,
                                    targetIsFav,
                                    targetCount,
                                )
                            playlistSongs = pSongs
                            totalCount = if (targetCount > 0) targetCount else pSongs.size
                            hasMore = false
                            saveToCache(pSongs, totalCount, false)
                        }
                    } else {
                        playlistSongs = emptyList()
                        hasMore = false
                    }
                }
                "collections", "album_detail" -> {
                    if (albumMid.isNotBlank()) {
                        val aSongs = apiService.getAlbumSongs(albumMid)
                        playlistSongs = aSongs
                        totalCount = aSongs.size
                        hasMore = false
                    } else {
                        var albums = userAlbums
                        if (albums.isEmpty()) {
                            albums = apiService.getFavoriteAlbums()
                            userAlbums = albums
                        }
                        val active = albums.getOrNull(selectedAlbumIndex)
                        if (active != null) {
                            val aSongs = apiService.getAlbumSongs(active.mid)
                            playlistSongs = aSongs
                            totalCount = aSongs.size
                            hasMore = false
                        } else {
                            playlistSongs = emptyList()
                            hasMore = false
                        }
                    }
                }
                else -> {
                    if (songs.isNotEmpty()) {
                        playlistSongs = songs
                        totalCount = songs.size
                    }
                    hasMore = false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MelodistPlaylist", "Failed to load category $categoryId", e)
            loadError = e.message
        } finally {
            isLoading = false
            if (playlistSongs.isNotEmpty()) {
                saveToCache(playlistSongs, totalCount, hasMore)
            }
        }
    }

    // 动态计算激活项与展示标题/副标题
    // 绑定当前歌单首首曲目信息
    val activePlaylist = userPlaylists.getOrNull(selectedPlaylistIndex)
    val activeAlbum = userAlbums.getOrNull(selectedAlbumIndex)
    val playlistFirstSong = playlistSongs.firstOrNull()

    val resolvedTitle =
        when {
            title.isNotBlank() && title != "我喜欢的音乐" && title != "自建歌单" && title != "我的收藏" -> title
            categoryId in listOf("playlists", "playlist_detail") && activePlaylist != null -> activePlaylist.name
            categoryId in listOf("collections", "album_detail") && activeAlbum != null -> activeAlbum.title
            else -> title
        }

    val displaySubtitle =
        when (categoryId) {
            "favorites" -> {
                if (UserSession.isLoggedIn) {
                    if (isLoading) {
                        "正在同步云端收藏曲目..."
                    } else if (totalCount > 0) {
                        "云端收藏共 $totalCount 首单曲"
                    } else {
                        "已同步云端收藏 ${playlistSongs.size} 首单曲"
                    }
                } else {
                    "未登录账号 · 前往设置扫码可同步 QQ 音乐云端资产"
                }
            }
            "daily" -> {
                if (UserSession.isLoggedIn) {
                    if (isLoading) {
                        "正在拉取今日推荐..."
                    } else {
                        val updateTimeStr =
                            org.melodist.data.DailyRecommendCacheManager
                                .getFormattedLocalDailyRecommendUpdateTime()
                        "今日 30 首专属推荐 · 每日 $updateTimeStr 更新"
                    }
                } else {
                    "未登录账号 · 前往设置扫码可获取个性化推荐"
                }
            }
            "radar" -> {
                if (UserSession.isLoggedIn) {
                    if (isLoading) {
                        "正在推演雷达曲目..."
                    } else {
                        "基于偏好实时推演 · 共 ${playlistSongs.size} 首"
                    }
                } else {
                    "未登录账号 · 前往设置扫码可获取雷达推荐"
                }
            }
            "million" -> {
                if (UserSession.isLoggedIn) {
                    if (isLoading) {
                        "正在拉取百万推荐..."
                    } else {
                        "官方精选高赞专栏 · 共 ${playlistSongs.size} 首"
                    }
                } else {
                    "未登录账号 · 前往设置扫码可获取百万推荐"
                }
            }
            "recent" -> {
                if (isLoading) {
                    "正在同步最近播放记录..."
                } else if (playlistSongs.isNotEmpty()) {
                    "最近收听足迹 · 共 ${playlistSongs.size} 首"
                } else {
                    "暂无最近播放记录"
                }
            }
            "playlists" -> {
                if (UserSession.isLoggedIn) {
                    if (isLoading) {
                        "正在加载歌单曲目..."
                    } else if (activePlaylist != null) {
                        "${activePlaylist.name} · 共 ${playlistSongs.size} 首"
                    } else {
                        subtitle
                    }
                } else {
                    "未登录账号 · 前往设置扫码可同步自建歌单"
                }
            }
            "collections" -> {
                if (UserSession.isLoggedIn) {
                    if (isLoading) {
                        "正在加载专辑曲目..."
                    } else if (activeAlbum != null) {
                        "${activeAlbum.artist} · 共 ${playlistSongs.size} 首"
                    } else {
                        subtitle
                    }
                } else {
                    "未登录账号 · 前往设置扫码可同步收藏专辑"
                }
            }
            else -> subtitle
        }

    // 左侧封面联动逻辑：随列表光标上下移动动态展示当前获焦单曲封面；光标在控制区时展示歌单固定封面
    val activeCoverSong =
        if (focusedSongIndex != null) {
            playlistSongs.getOrNull(focusedSongIndex!!)
        } else {
            null
        }

    val displayAlbumMid =
        when {
            activeCoverSong != null && activeCoverSong.albumMid.isNotBlank() -> activeCoverSong.albumMid
            lockedAlbumMid.isNotBlank() -> lockedAlbumMid
            categoryId in listOf("collections", "album_detail") && !activeAlbum?.mid.isNullOrEmpty() -> activeAlbum.mid
            playlistSongs.firstOrNull()?.albumMid?.isNotBlank() == true -> playlistSongs.first().albumMid
            else -> ""
        }

    val displayCoverUrl =
        when {
            activeCoverSong != null && activeCoverSong.coverUrl.isNotBlank() -> activeCoverSong.coverUrl
            activeCoverSong != null && displayAlbumMid.isNotBlank() -> MusicApiService.getAlbumCoverUrl(displayAlbumMid)
            lockedCoverUrl.isNotBlank() -> lockedCoverUrl
            categoryId in listOf("playlists", "playlist_detail") && !activePlaylist?.picUrl.isNullOrEmpty() -> activePlaylist.picUrl
            categoryId in listOf("collections", "album_detail") && !activeAlbum?.coverUrl.isNullOrEmpty() -> activeAlbum.coverUrl
            playlistSongs.firstOrNull()?.coverUrl?.isNotBlank() == true -> playlistSongs.first().coverUrl
            displayAlbumMid.isNotBlank() -> MusicApiService.getAlbumCoverUrl(displayAlbumMid)
            else -> ""
        }

    val returnTargetIndex =
        remember(playlistSongs, isReturningFromPlayer) {
            if (!isReturningFromPlayer || playlistSongs.isEmpty()) {
                -1
            } else {
                val playingIndex =
                    playlistSongs.indexOfFirst {
                        (it.songMid.isNotBlank() && it.songMid == currentSong?.songMid) ||
                            (it.songId != 0L && it.songId == currentSong?.songId)
                    }
                when {
                    playingIndex >= 0 -> playingIndex
                    PlaylistScreenCache.lastFocusedIndex in playlistSongs.indices -> PlaylistScreenCache.lastFocusedIndex
                    else -> PlaylistScreenCache.lastPlayedIndex.coerceIn(0, playlistSongs.size - 1)
                }
            }
        }

    var screenMode by remember { mutableStateOf(PlaylistScreenMode.List) }
    var hasEverBeenInPlayerMode by remember { mutableStateOf(false) }
    var dynamicReturnTargetIndex by remember { mutableIntStateOf(-1) }
    var isControlsHidden by remember { mutableStateOf(false) }
    var showQueueSidebar by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showArtistAlbumDialog by remember { mutableStateOf(false) }
    var lastInteractionTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val playlist by PlaybackManager.playlist.collectAsState()
    val isPlaying by PlaybackManager.isPlaying.collectAsState()
    val durationMs by PlaybackManager.durationMs.collectAsState()
    val selectedTier by PlaybackManager.currentTier.collectAsState()
    val lyrics by PlaybackManager.lyrics.collectAsState()
    val loopMode by PlaybackManager.loopMode.collectAsState()
    val isPlaybackLoading by PlaybackManager.isLoading.collectAsState()
    val playbackErrorMessage by PlaybackManager.errorMessage.collectAsState()
    val favoriteSongMids by PlaybackManager.favoriteSongMids.collectAsState()

    val themeHighlightColor =
        remember(surfaceColor) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(surfaceColor.toArgb(), hsv)
            val hue = hsv[0]
            val rawSat = hsv[1]
            val sat = (rawSat * 1.15f).coerceIn(0.40f, 0.68f)
            val value = 0.94f
            Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
        }

    // 播放态 10 秒无操作全屏隐藏
    LaunchedEffect(screenMode, isControlsHidden, showQueueSidebar, showQualityDialog, showArtistAlbumDialog, lastInteractionTimeMs) {
        if (screenMode == PlaylistScreenMode.Player && !isControlsHidden && !showQueueSidebar && !showQualityDialog && !showArtistAlbumDialog) {
            kotlinx.coroutines.delay(10_000L)
            isControlsHidden = true
        }
    }

    // 播放态拦截返回键：平滑收起歌词并回到歌单列表
    BackHandler(enabled = screenMode == PlaylistScreenMode.Player) {
        if (showArtistAlbumDialog) {
            showArtistAlbumDialog = false
        } else if (showQualityDialog) {
            showQualityDialog = false
        } else if (showQueueSidebar) {
            showQueueSidebar = false
        } else if (isControlsHidden) {
            isControlsHidden = false
            lastInteractionTimeMs = System.currentTimeMillis()
        } else {
            screenMode = PlaylistScreenMode.List
        }
    }

    // 切回歌单态时的焦点还原
    LaunchedEffect(screenMode) {
        if (screenMode == PlaylistScreenMode.Player) {
            hasEverBeenInPlayerMode = true
        } else if (screenMode == PlaylistScreenMode.List && hasEverBeenInPlayerMode && playlistSongs.isNotEmpty()) {
            val playingIndex =
                playlistSongs.indexOfFirst {
                    (it.songMid.isNotBlank() && it.songMid == currentSong?.songMid) ||
                        (it.songId != 0L && it.songId == currentSong?.songId)
                }
            val target =
                when {
                    playingIndex in playlistSongs.indices -> playingIndex
                    PlaylistScreenCache.lastFocusedIndex in playlistSongs.indices -> PlaylistScreenCache.lastFocusedIndex
                    else -> PlaylistScreenCache.lastPlayedIndex.coerceIn(0, playlistSongs.size - 1)
                }
            dynamicReturnTargetIndex = target
            listState.scrollToItem((target - 1).coerceAtLeast(0))
            var focused = false
            for (attempt in 0..5) {
                kotlinx.coroutines.delay(if (attempt == 0) 100L else 60L)
                try {
                    returnSongRequester.requestFocus()
                    focused = true
                    break
                } catch (_: Exception) {
                }
            }
            if (!focused) {
                try {
                    playAllRequester.requestFocus()
                } catch (_: Exception) {
                }
            }
        }
    }

    // 焦点与滚动生命周期：首次进入默认选中“播放全部”按钮；播放后或二级页面返回时定位并聚焦到记忆曲目
    LaunchedEffect(isReturningFromPlayer, playlistSongs.size) {
        if (isReturningFromPlayer && returnTargetIndex >= 0 && playlistSongs.isNotEmpty()) {
            listState.scrollToItem((returnTargetIndex - 1).coerceAtLeast(0))
            var focused = false
            for (attempt in 0..5) {
                kotlinx.coroutines.delay(if (attempt == 0) 100L else 60L)
                try {
                    returnSongRequester.requestFocus()
                    focused = true
                    break
                } catch (_: Exception) {
                }
            }
            if (!focused) {
                try {
                    playAllRequester.requestFocus()
                } catch (_: Exception) {
                }
            }
        } else if (!isReturningFromPlayer) {
            kotlinx.coroutines.delay(60)
            try {
                playAllRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    TvSplitPlaybackScaffold(
        surfaceColor = surfaceColor,
        isControlsHidden = (screenMode == PlaylistScreenMode.Player && isControlsHidden),
        controlsBottomPadding = if (screenMode == PlaylistScreenMode.Player) 100.dp else 0.dp,
        onUserInteraction = { lastInteractionTimeMs = System.currentTimeMillis() },
        leftPanel = { coverSize ->
            PlaylistTvLeftPanel(
                coverSize = coverSize,
                isPlayerMode = (screenMode == PlaylistScreenMode.Player),
                activePlayingSong = currentSong,
                displayCoverUrl = displayCoverUrl,
                displayAlbumMid = displayAlbumMid,
                resolvedTitle = resolvedTitle,
                displaySubtitle = displaySubtitle,
                categoryId = categoryId,
                playbackErrorMessage = playbackErrorMessage,
                hasSongs = playlistSongs.isNotEmpty(),
                playAllRequester = playAllRequester,
                firstSongRequester = firstSongRequester,
                onPlayAllClick = {
                    if (playlistSongs.isNotEmpty()) {
                        val isSamePlaylist = (PlaybackManager.queueTag.value == cacheKey)
                        val curQueue = PlaybackManager.playlist.value
                        if (isSamePlaylist && curQueue.size > playlistSongs.size && curQueue.firstOrNull()?.songMid == playlistSongs.first().songMid) {
                            PlaybackManager.playSong(playlistSongs.first())
                        } else {
                            PlaybackManager.setPlaylist(
                                playlistSongs,
                                0,
                                isRadio = (categoryId == "radar"),
                                queueTag = cacheKey,
                                sourceContext = tvSourceContext,
                            )
                        }
                        syncFullPlaylistToPlayback()
                        screenMode = PlaylistScreenMode.Player
                    }
                    onPlayAll()
                },
                onPlayAllFocusChanged = { focused ->
                    isPlayAllFocused = focused
                    if (focused) focusedSongIndex = null
                },
            )
        },
        rightContent = {
            // 模式 1：歌单单曲列表
            PlaylistTvTrackList(
                visible = (screenMode == PlaylistScreenMode.List),
                categoryId = categoryId,
                userPlaylists = userPlaylists,
                selectedPlaylistIndex = selectedPlaylistIndex,
                onSelectPlaylistIndex = { selectedPlaylistIndex = it },
                userAlbums = userAlbums,
                selectedAlbumIndex = selectedAlbumIndex,
                onSelectAlbumIndex = { selectedAlbumIndex = it },
                isLoading = isLoading,
                playlistSongs = playlistSongs,
                listState = listState,
                currentSong = currentSong,
                dynamicReturnTargetIndex = dynamicReturnTargetIndex,
                returnTargetIndex = returnTargetIndex,
                returnSongRequester = returnSongRequester,
                firstSongRequester = firstSongRequester,
                playAllRequester = playAllRequester,
                hasMore = hasMore,
                isLoadingMore = isLoadingMore,
                onLoadMore = { loadMoreSongs() },
                onFocusSongChanged = { focusedSongIndex = it },
                onSongClick = { song, index ->
                    PlaylistScreenCache.lastPlayedIndex = index
                    val isSamePlaylist = (PlaybackManager.queueTag.value == cacheKey)
                    val curQueue = PlaybackManager.playlist.value
                    if (isSamePlaylist && curQueue.size > playlistSongs.size && curQueue.any { it.songMid == song.songMid }) {
                        PlaybackManager.playSong(song)
                    } else {
                        PlaybackManager.setPlaylist(
                            playlistSongs,
                            index,
                            isRadio = (categoryId == "radar"),
                            queueTag = cacheKey,
                            sourceContext = tvSourceContext,
                        )
                    }
                    syncFullPlaylistToPlayback()
                    screenMode = PlaylistScreenMode.Player
                    onSongClick(song)
                },
                onSongLongClick = { song ->
                    if (song.canShowArtistAlbumDialog) {
                        actionSong = song
                    }
                },
                onNavigateToSettings = onNavigateToSettings,
            )

            // 模式 2：沉浸双语逐字歌词流
            PlaylistTvLyricsOverlay(
                visible = (screenMode == PlaylistScreenMode.Player),
                lyrics = lyrics,
                isPlaybackLoading = isPlaybackLoading,
                themeHighlightColor = themeHighlightColor,
            )
        },
        bottomBar = {
            PlaylistTvBottomBar(
                visible = (screenMode == PlaylistScreenMode.Player && !isControlsHidden),
                surfaceColor = surfaceColor,
                accentColor = themeHighlightColor,
                horizontalSafePadding = metrics.horizontalSafePadding,
                activePlayingSong = currentSong,
                durationMs = durationMs,
                isPlaying = isPlaying,
                favoriteSongMids = favoriteSongMids,
                loopMode = loopMode,
                selectedTier = selectedTier,
                queueCount = playlist.size,
                onFavoriteClick = { PlaybackManager.toggleCurrentSongFavorite() },
                onPrevClick = { PlaybackManager.playPrevious() },
                onPlayPauseClick = { PlaybackManager.togglePlayPause() },
                onNextClick = { PlaybackManager.playNext() },
                onLoopClick = { PlaybackManager.cycleLoopMode() },
                onQualityClick = {
                    if (!PlaybackManager.isLocalOrWebDavSong(currentSong)) {
                        showQualityDialog = true
                    }
                },
                onFullscreenClick = { isControlsHidden = true },
                onSeekBy = { deltaMs ->
                    val totalDurationMs = if (durationMs > 0L) durationMs else currentSong?.durationSeconds?.toLong()?.times(1000L) ?: 240000L
                    val currentPos = PlaybackManager.currentPositionMs.value
                    val targetMs = (currentPos + deltaMs).coerceIn(0L, totalDurationMs)
                    PlaybackManager.seekTo(targetMs)
                },
                onQueueClick = { showQueueSidebar = true },
                onDownPress = {
                    if (currentSong?.canShowArtistAlbumDialog == true) {
                        showArtistAlbumDialog = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
        overlay = {
            PlaylistTvFullscreenRestoreOverlay(
                visible = (screenMode == PlaylistScreenMode.Player && isControlsHidden),
                onWakeControls = {
                    isControlsHidden = false
                    lastInteractionTimeMs = System.currentTimeMillis()
                },
            )

            PlaylistTvDialogs(
                showQueueSidebar = showQueueSidebar,
                onDismissQueueSidebar = { showQueueSidebar = false },
                playlist = playlist,
                currentSong = currentSong,
                surfaceColor = surfaceColor,
                onSelectQueueSong = { selectedSong -> PlaybackManager.playSong(selectedSong) },
                showQualityDialog = showQualityDialog,
                selectedTier = selectedTier,
                onSelectTier = { PlaybackManager.switchTier(it) },
                onDismissQualityDialog = { showQualityDialog = false },
                showArtistAlbumDialog = showArtistAlbumDialog,
                onDismissArtistAlbumDialog = { showArtistAlbumDialog = false },
                actionSong = actionSong,
                onDismissActionSongDialog = { actionSong = null },
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
            )
        },
    )
}
