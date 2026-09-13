package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.getAlbumSongs
import org.melodist.api.getDailyRecommendSongs
import org.melodist.api.getFavoriteAlbums
import org.melodist.api.getFavoriteSongsDetail
import org.melodist.api.getGuessRecommendSongs
import org.melodist.api.getPlaylistSongs
import org.melodist.api.getPlaylists
import org.melodist.model.Album
import org.melodist.model.AudioQualityTier
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.MelodistElevatedCover
import org.melodist.tv.ui.components.TvPlaylistSongItem
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.MonetColorExtractor
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

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
            currentTier = AudioQualityTier.Atmos71,
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
            currentTier = AudioQualityTier.Atmos51,
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

object PlaylistScreenCache {
    var lastCacheKey: String = ""
    var songs: List<Song> = emptyList()
    var totalCount: Int = 0
    var hasMore: Boolean = false
    var lockedCoverUrl: String = ""
    var lockedAlbumMid: String = ""
    var lastPlayedIndex: Int = 0

    fun matches(key: String): Boolean = lastCacheKey == key && songs.isNotEmpty()

    fun save(
        key: String,
        songs: List<Song>,
        total: Int,
        more: Boolean,
        cover: String,
        albumMid: String,
    ) {
        this.lastCacheKey = key
        this.songs = songs
        this.totalCount = total
        this.hasMore = more
        this.lockedCoverUrl = cover
        this.lockedAlbumMid = albumMid
    }
}

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
                                        if (curQueue.isNotEmpty() &&
                                            playlistSongs.any {
                                                it.songMid == curQueue.firstOrNull()?.songMid ||
                                                    it.songMid == PlaybackManager.currentSong.value?.songMid
                                            }
                                        ) {
                                            PlaybackManager.appendPlaylist(res.songs)
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
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        currentPage = 1
        loadError = null

        try {
            when (categoryId) {
                "favorites" -> {
                    val favResult = apiService.getFavoriteSongsDetail(page = 1, pageSize = 100)
                    val currentList = favResult.songs
                    playlistSongs = currentList
                    totalCount = favResult.total
                    UserSession.updateFavoriteSongCount(favResult.total)
                    hasMore = favResult.hasMore
                    if (favResult.songs.isNotEmpty()) {
                        PlaybackManager.addFavoriteSongMids(favResult.songs.map { it.songMid })
                    }
                    if (hasMore) {
                        syncFullPlaylistToPlayback()
                    }
                }
                "daily" -> {
                    val dailySongs = apiService.getDailyRecommendSongs()
                    playlistSongs = dailySongs
                    totalCount = dailySongs.size
                    hasMore = false
                }
                "radar" -> {
                    val radarSongs = apiService.getGuessRecommendSongs(count = 30)
                    playlistSongs = radarSongs
                    totalCount = radarSongs.size
                    hasMore = true
                }
                "playlists", "playlist_detail" -> {
                    val activePlaylist =
                        if (dirId > 0L || tid > 0L) {
                            null
                        } else {
                            if (userPlaylists.isEmpty()) {
                                userPlaylists = apiService.getPlaylists().filterNot { it.isMyFavorite }
                            }
                            userPlaylists.getOrNull(selectedPlaylistIndex)
                        }

                    val targetDirId = if (dirId > 0L) dirId else activePlaylist?.dirId ?: 0L
                    val targetTid = if (tid > 0L) tid else activePlaylist?.tid ?: 0L
                    val targetIsFav = if (dirId > 0L || tid > 0L) isFav else activePlaylist?.isFav ?: false
                    val targetCount = if (activePlaylist != null) activePlaylist.songCount else 0

                    if (targetDirId > 0L || targetTid > 0L) {
                        val pSongs =
                            apiService.getPlaylistSongs(
                                dirId = targetDirId,
                                tid = targetTid,
                                isFav = targetIsFav,
                                page = 1,
                                pageSize = 100,
                            )
                        playlistSongs = pSongs
                        totalCount = if (targetCount > 0) targetCount else pSongs.size
                        hasMore = (targetCount > pSongs.size) || (pSongs.size >= 100)

                        // 若歌单曲目总数超过第一页，立即在后台自动全量拉取合并，确保选项列表与队列完整
                        if (hasMore) {
                            syncFullPlaylistToPlayback()
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
                        "今日 30 首专属推荐 · 每日 6:00 更新"
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
            categoryId in listOf("collections", "album_detail") && !activeAlbum?.mid.isNullOrEmpty() -> activeAlbum!!.mid
            playlistSongs.firstOrNull()?.albumMid?.isNotBlank() == true -> playlistSongs.first().albumMid
            else -> ""
        }

    val displayCoverUrl =
        when {
            activeCoverSong != null && activeCoverSong.coverUrl.isNotBlank() -> activeCoverSong.coverUrl
            activeCoverSong != null && displayAlbumMid.isNotBlank() -> MusicApiService.getAlbumCoverUrl(displayAlbumMid)
            lockedCoverUrl.isNotBlank() -> lockedCoverUrl
            categoryId in listOf("playlists", "playlist_detail") && !activePlaylist?.picUrl.isNullOrEmpty() -> activePlaylist!!.picUrl
            categoryId in listOf("collections", "album_detail") && !activeAlbum?.coverUrl.isNullOrEmpty() -> activeAlbum!!.coverUrl
            playlistSongs.firstOrNull()?.coverUrl?.isNotBlank() == true -> playlistSongs.first().coverUrl
            displayAlbumMid.isNotBlank() -> MusicApiService.getAlbumCoverUrl(displayAlbumMid)
            else -> ""
        }

    val returnTargetIndex =
        remember(playlistSongs, isReturningFromPlayer) {
            if (isReturningFromPlayer && playlistSongs.isNotEmpty()) {
                val playingIndex =
                    playlistSongs.indexOfFirst {
                        (it.songMid.isNotBlank() && it.songMid == currentSong?.songMid) ||
                            (it.songId != 0L && it.songId == currentSong?.songId)
                    }
                if (playingIndex >= 0) playingIndex else PlaylistScreenCache.lastPlayedIndex.coerceIn(0, playlistSongs.size - 1)
            } else {
                0
            }
        }

    // 焦点与滚动生命周期：首次进入默认选中“播放全部”按钮；播放后返回时定位并聚焦到播放曲目
    LaunchedEffect(isReturningFromPlayer, playlistSongs.size) {
        if (isReturningFromPlayer && playlistSongs.isNotEmpty()) {
            listState.scrollToItem((returnTargetIndex - 1).coerceAtLeast(0))
            kotlinx.coroutines.delay(80)
            try {
                returnSongRequester.requestFocus()
            } catch (_: Exception) {
            }
        } else if (!isReturningFromPlayer) {
            kotlinx.coroutines.delay(60)
            try {
                playAllRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // 纯色背景填充
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(
                    start = metrics.horizontalSafePadding,
                    end = metrics.horizontalSafePadding,
                    top = metrics.verticalSafePadding,
                    bottom = metrics.verticalSafePadding,
                ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            // 左侧：封面与信息主控 (封面向下对齐 36dp 与右侧单曲列表顶端对齐)
            Column(
                modifier =
                    Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .padding(top = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 封面展示
                if (displayCoverUrl.isNotEmpty() || displayAlbumMid.isNotEmpty()) {
                    MelodistElevatedCover(
                        coverUrl = displayCoverUrl,
                        albumMid = displayAlbumMid,
                        contentDescription = resolvedTitle,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.size(240.dp),
                    )
                } else if (categoryId == "favorites") {
                    Box(
                        modifier =
                            Modifier
                                .size(240.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = RoundedCornerShape(6.dp),
                                    ambientColor = Color.Black.copy(alpha = 0.25f),
                                    spotColor = Color.Black.copy(alpha = 0.45f),
                                ).clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF38151D))
                                .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Favorite,
                                contentDescription = "我喜欢",
                                tint = MelodistColors.FavoriteRed,
                                modifier = Modifier.size(88.dp),
                            )
                            Text(
                                text = "MELODIST FAVORITES",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(240.dp)
                                .shadow(
                                    elevation = 10.dp,
                                    shape = RoundedCornerShape(6.dp),
                                    ambientColor = Color.Black.copy(alpha = 0.25f),
                                    spotColor = Color.Black.copy(alpha = 0.45f),
                                ).clip(RoundedCornerShape(6.dp))
                                .background(MelodistColors.ContainerDarkSecondary)
                                .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Melodist TV",
                            color = MelodistColors.TextMuted,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // 歌单标题与副信息
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = resolvedTitle,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        fontSize = 13.sp,
                        color = MelodistColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // 播放全部按钮 (充满左栏，移除随机播放按钮)
                Button(
                    onClick = {
                        if (playlistSongs.isNotEmpty()) {
                            val curQueue = PlaybackManager.playlist.value
                            if (curQueue.size > playlistSongs.size && curQueue.firstOrNull()?.songMid == playlistSongs.first().songMid) {
                                PlaybackManager.playSong(playlistSongs.first())
                            } else {
                                PlaybackManager.setPlaylist(playlistSongs, 0, isRadio = (categoryId == "radar"))
                            }
                            syncFullPlaylistToPlayback()
                        }
                        onPlayAll()
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .focusRequester(playAllRequester)
                            .onFocusChanged {
                                isPlayAllFocused = it.isFocused
                                if (it.isFocused) focusedSongIndex = null
                            }.then(
                                if (playlistSongs.isNotEmpty()) {
                                    Modifier.focusProperties { right = firstSongRequester }
                                } else {
                                    Modifier
                                },
                            ),
                    shape =
                        ButtonDefaults.shape(
                            shape = MelodistShapes.CardCorner,
                            focusedShape = MelodistShapes.CardCorner,
                        ),
                    colors =
                        ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = MelodistColors.AccentGreen,
                            contentColor = Color.White,
                            focusedContentColor = Color.Black,
                        ),
                    border =
                        ButtonDefaults.border(
                            border =
                                Border(
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                    shape = MelodistShapes.CardCorner,
                                ),
                            focusedBorder =
                                Border(
                                    border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                    shape = MelodistShapes.CardCorner,
                                ),
                        ),
                    scale = ButtonDefaults.scale(focusedScale = 1.0f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "播放全部",
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = "播放全部",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // 右侧：纵向歌曲列表与子分类选择条 (横向展示宽度大幅扩展)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
            ) {
                // 歌单 / 收藏专辑横向筛选 Chips
                if (categoryId == "playlists" && userPlaylists.isNotEmpty()) {
                    LazyRow(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(userPlaylists) { pIdx, pl ->
                            PlaylistFilterChip(
                                text = "${pl.name} (${pl.songCount})",
                                isSelected = pIdx == selectedPlaylistIndex,
                                onClick = { selectedPlaylistIndex = pIdx },
                            )
                        }
                    }
                } else if (categoryId == "collections" && userAlbums.isNotEmpty()) {
                    LazyRow(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(userAlbums) { aIdx, alb ->
                            PlaylistFilterChip(
                                text = "${alb.title} · ${alb.artist}",
                                isSelected = aIdx == selectedAlbumIndex,
                                onClick = { selectedAlbumIndex = aIdx },
                            )
                        }
                    }
                }

                // 表头 (与左侧封面顶部对齐)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "#", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    Text(
                        text = "歌曲",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.45f),
                    )
                    Text(
                        text = "歌手",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.28f),
                    )
                    Text(
                        text = "专辑",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.27f),
                    )
                }

                if (!UserSession.isLoggedIn) {
                    // 未登录引导卡片
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = "未登录",
                            tint = MelodistColors.TextMuted,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "尚未登录 QQ 音乐账号",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MelodistColors.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "登录后即可同步云端喜欢、收藏歌单、每日推荐与雷达专属曲库",
                            fontSize = 14.sp,
                            color = MelodistColors.TextMuted,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onNavigateToSettings,
                            shape = ButtonDefaults.shape(MelodistShapes.PillCorner),
                            colors =
                                ButtonDefaults.colors(
                                    containerColor = MelodistColors.AccentGreen,
                                    focusedContainerColor = Color.White,
                                    contentColor = Color.Black,
                                    focusedContentColor = Color.Black,
                                ),
                            border =
                                ButtonDefaults.border(
                                    focusedBorder =
                                        Border(
                                            border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                                            shape = MelodistShapes.PillCorner,
                                        ),
                                ),
                            scale = ButtonDefaults.scale(focusedScale = 1.06f),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "前往设置扫码登录",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                } else if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在同步曲目列表...",
                            color = MelodistColors.TextSecondary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else if (playlistSongs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "曲目列表中暂无歌曲",
                            color = MelodistColors.TextMuted,
                            fontSize = 16.sp,
                        )
                    }
                } else {
                    // 单曲列表与滚动监听
                    val shouldLoadMore by remember {
                        derivedStateOf {
                            val totalItems = listState.layoutInfo.totalItemsCount
                            val lastVisibleIndex =
                                listState.layoutInfo.visibleItemsInfo
                                    .lastOrNull()
                                    ?.index ?: 0
                            totalItems > 0 && lastVisibleIndex >= totalItems - 4
                        }
                    }

                    LaunchedEffect(shouldLoadMore) {
                        if (shouldLoadMore && hasMore && !isLoading && !isLoadingMore && UserSession.isLoggedIn) {
                            isLoadingMore = true
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

                    var pageTargetFocusIndex by remember { mutableStateOf<Int?>(null) }
                    val pageFocusRequester = remember { FocusRequester() }

                    LaunchedEffect(pageTargetFocusIndex) {
                        val target = pageTargetFocusIndex
                        if (target != null) {
                            kotlinx.coroutines.delay(60)
                            try {
                                pageFocusRequester.requestFocus()
                            } catch (_: Exception) {
                            }
                            pageTargetFocusIndex = null
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        itemsIndexed(playlistSongs) { index, song ->
                            val isFirst = index == 0
                            val isTarget = index == pageTargetFocusIndex
                            val isReturnTarget = isReturningFromPlayer && index == returnTargetIndex
                            val isCurrentPlaying =
                                (currentSong?.songMid == song.songMid && song.songMid.isNotBlank()) ||
                                    (song.songId != 0L && currentSong?.songId == song.songId)
                            val itemModifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(68.dp)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            focusedSongIndex = index
                                        }
                                    }.then(
                                        when {
                                            isReturnTarget -> Modifier.focusRequester(returnSongRequester)
                                            isTarget -> Modifier.focusRequester(pageFocusRequester)
                                            isFirst -> Modifier.focusRequester(firstSongRequester)
                                            else -> Modifier
                                        },
                                    ).focusProperties {
                                        left = playAllRequester
                                    }.onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionRight -> {
                                                    val targetIndex = (index + 8).coerceAtMost(playlistSongs.size - 1)
                                                    if (targetIndex > index) {
                                                        pageTargetFocusIndex = targetIndex
                                                        coroutineScope.launch {
                                                            listState.animateScrollToItem(targetIndex)
                                                        }
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                Key.DirectionLeft -> {
                                                    if (index >= 8) {
                                                        val targetIndex = (index - 8).coerceAtLeast(0)
                                                        pageTargetFocusIndex = targetIndex
                                                        coroutineScope.launch {
                                                            listState.animateScrollToItem(targetIndex)
                                                        }
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                else -> false
                                            }
                                        } else {
                                            false
                                        }
                                    }

                            TvPlaylistSongItem(
                                index = index + 1,
                                song = song,
                                isPlaying = isCurrentPlaying,
                                modifier = itemModifier,
                                onClick = {
                                    PlaylistScreenCache.lastPlayedIndex = index
                                    val curQueue = PlaybackManager.playlist.value
                                    if (curQueue.size > playlistSongs.size && curQueue.any { it.songMid == song.songMid }) {
                                        PlaybackManager.playSong(song)
                                    } else {
                                        PlaybackManager.setPlaylist(playlistSongs, index, isRadio = (categoryId == "radar"))
                                    }
                                    syncFullPlaylistToPlayback()
                                    onSongClick(song)
                                },
                                onLongClick = {
                                    if (song.canShowArtistAlbumDialog) {
                                        actionSong = song
                                    }
                                },
                            )
                        }

                        if (isLoadingMore) {
                            item {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "正在载入更多曲目...",
                                        color = MelodistColors.TextMuted,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 歌曲关联资产弹窗（长按呼出）
        actionSong?.let { song ->
            org.melodist.tv.ui.components.SongArtistAlbumDialog(
                song = song,
                onDismissRequest = { actionSong = null },
                onSelectArtist = { mid, name ->
                    onNavigateToArtist(mid, name)
                },
                onSelectAlbum = { mid, name ->
                    onNavigateToAlbum(mid, name)
                },
            )
        }
    }
}

@Composable
private fun PlaylistFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(MelodistShapes.PillCorner),
        colors =
            ButtonDefaults.colors(
                containerColor =
                    if (isSelected) {
                        MelodistColors.AccentGreen.copy(alpha = 0.25f)
                    } else {
                        MelodistColors.ContainerDarkSecondary
                    },
                focusedContainerColor = Color.White,
                contentColor = if (isSelected) MelodistColors.AccentGreen else MelodistColors.TextSecondary,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border =
                            BorderStroke(
                                1.dp,
                                if (isSelected) MelodistColors.AccentGreen else Color.White.copy(alpha = 0.1f),
                            ),
                        shape = MelodistShapes.PillCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.PillCorner,
                    ),
            ),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
