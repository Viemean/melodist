package org.melodist.mobile.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.api.MusicApiService
import org.melodist.api.UserSession
import org.melodist.api.refreshCurrentUserProfile
import org.melodist.mobile.ui.acr.AcrPillBar
import org.melodist.mobile.ui.acr.AcrRecognitionController
import org.melodist.mobile.ui.acr.AcrResultCard
import org.melodist.mobile.ui.acr.MobileAcrUiState
import org.melodist.mobile.ui.acr.MobileAcrViewModel
import org.melodist.mobile.ui.album.AlbumDetailScreen
import org.melodist.mobile.ui.album.FavoriteAlbumsScreen
import org.melodist.mobile.ui.artist.ArtistDetailScreen
import org.melodist.mobile.ui.auth.MobileLoginDialog
import org.melodist.mobile.ui.connect.RemoteControlMobileScreen
import org.melodist.mobile.ui.discover.DiscoverScreen
import org.melodist.mobile.ui.download.DownloadMobileScreen
import org.melodist.mobile.ui.library.LibraryScreen
import org.melodist.mobile.ui.local.LocalMusicMobileScreen
import org.melodist.mobile.ui.navigation.AppNavigationController
import org.melodist.mobile.ui.navigation.LocalAppNavigation
import org.melodist.mobile.ui.navigation.ScreenDestination
import org.melodist.mobile.ui.player.PlayerContainer
import org.melodist.mobile.ui.playlist.PlaylistDetailScreen
import org.melodist.mobile.ui.recent.RecentPlaybackScreen
import org.melodist.mobile.ui.search.SearchScreen
import org.melodist.mobile.ui.settings.MobileSettingsScreen
import org.melodist.mobile.ui.webdav.WebDavMobileScreen
import org.melodist.model.Playlist
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

enum class HomeFilter(
    val title: String,
) {
    Discover("每日推荐"),
    Library("我的收藏"),
    Recent("最近播放"),
    Download("下载管理"),
    WebDav("WebDAV"),
    Local("本地音乐"),
    Remote("远程控制"),
}

private sealed interface AppScreen {
    val depth: Int

    data object Home : AppScreen {
        override val depth: Int = 0
    }

    data object Search : AppScreen {
        override val depth: Int = 1
    }

    data class Detail(
        val destination: ScreenDestination,
        override val depth: Int,
    ) : AppScreen
}

data class MobileAcrSuccessData(
    val song: Song,
    val offsetSeconds: Double,
    val anchorRealtimeMs: Long,
    val durationText: String,
)

private const val MAX_BACK_DEPTH = 3

@Composable
fun MainNavigationScreen(modifier: Modifier = Modifier) {
    val screenStack = remember { mutableStateListOf<ScreenDestination>() }
    var isSearching by remember { mutableStateOf(false) }

    fun pushDestination(
        destination: ScreenDestination,
        clearStack: Boolean,
    ) {
        if (clearStack) {
            isSearching = false
            screenStack.clear()
            screenStack.add(destination)
        } else {
            while (screenStack.size >= MAX_BACK_DEPTH) {
                screenStack.removeAt(0)
            }
            screenStack.add(destination)
        }
    }

    val navController =
        remember {
            object : AppNavigationController {
                override fun navigateToPlaylist(
                    playlist: Playlist,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.PlaylistDetail(playlist), clearStack)
                }

                override fun navigateToArtist(
                    artistMid: String,
                    artistName: String,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.ArtistDetail(artistMid, artistName), clearStack)
                }

                override fun navigateToAlbum(
                    albumMid: String,
                    albumName: String,
                    clearStack: Boolean,
                ) {
                    pushDestination(ScreenDestination.AlbumDetail(albumMid, albumName), clearStack)
                }

                override fun navigateToFavoriteAlbums(clearStack: Boolean) {
                    pushDestination(ScreenDestination.FavoriteAlbums, clearStack)
                }

                override fun navigateBack(): Boolean =
                    if (screenStack.isNotEmpty()) {
                        screenStack.removeLastOrNull()
                        true
                    } else if (isSearching) {
                        isSearching = false
                        true
                    } else {
                        false
                    }

                override val currentDestination: ScreenDestination?
                    get() = screenStack.lastOrNull()
            }
        }

    val pagerState =
        rememberPagerState(
            initialPage = 0,
            pageCount = { HomeFilter.entries.size },
        )
    val coroutineScope = rememberCoroutineScope()
    val chipScrollState = rememberScrollState()

    LaunchedEffect(pagerState.currentPage) {
        val targetScroll = (pagerState.currentPage * 75).coerceAtLeast(0)
        chipScrollState.animateScrollTo(targetScroll)
    }

    var showLoginDialog by remember { mutableStateOf(false) }
    var isFullPlayerExpanded by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }

    // ACR 识曲 ViewModel（提升到 Screen 级别，生命周期与页面一致）
    val acrViewModel = remember { MobileAcrViewModel() }
    val acrUiState by acrViewModel.uiState.collectAsState()
    // 识别成功后保存歌曲数据、时间戳与耗时文案，用于计算展开时的进度补偿及胶囊展示
    var acrSuccessData by remember { mutableStateOf<MobileAcrSuccessData?>(null) }
    // 识别成功卡片展示状态（弹出后平滑缩回胶囊，胶囊继续常驻）
    var showAcrResultCard by remember { mutableStateOf(false) }
    // 卡片触摸/拖拽交互中状态（交互中挂起倒计时，防止中途强行缩回）
    var isAcrCardInteracting by remember { mutableStateOf(false) }
    // 是否正在挂载无 UI 识别控制器
    var isAcrControllerActive by remember { mutableStateOf(false) }

    val userProfile by UserSession.profileFlow.collectAsState()
    val isLoggedIn = UserSession.isLoggedIn

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            withContext(Dispatchers.IO) {
                try {
                    MusicApiService().refreshCurrentUserProfile()
                } catch (_: Exception) {
                }
            }
        }
    }

    // 识别结果状态机：成功时解析耗时文案并保存数据；失败时延时复位
    LaunchedEffect(acrUiState) {
        val state = acrUiState
        when (state) {
            is MobileAcrUiState.Success -> {
                isAcrControllerActive = false
                val durationSec = state.matchDurationSeconds
                val durationText =
                    if (durationSec <= 0.1f) {
                        "3秒识别成功"
                    } else {
                        val secText = "%.1f".format(Locale.CHINA, durationSec).removeSuffix(".0")
                        "${secText}秒识别成功"
                    }
                acrSuccessData =
                    MobileAcrSuccessData(
                        song = state.song,
                        offsetSeconds = state.offsetSeconds,
                        anchorRealtimeMs = state.anchorRealtimeMs,
                        durationText = durationText,
                    )
            }
            is MobileAcrUiState.Failed -> {
                isAcrControllerActive = false
                showAcrResultCard = false
                isAcrCardInteracting = false
                delay(2000L)
                acrViewModel.reset()
            }
            else -> {}
        }
    }

    // 独立控制结果卡片 8 秒自动收起，交互中暂停计时，抬起后重新计时
    LaunchedEffect(acrSuccessData, isAcrCardInteracting) {
        if (acrSuccessData != null) {
            showAcrResultCard = true
            if (!isAcrCardInteracting) {
                delay(8000L)
                showAcrResultCard = false
            }
        } else {
            showAcrResultCard = false
        }
    }

    val pageBackAnimatable = remember { Animatable(0f) }
    var isPageBackActive by remember { mutableStateOf(false) }
    var backSwipeEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    var settingsBackProgress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = showSettingsScreen) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                settingsBackProgress = backEvent.progress
            }
            showSettingsScreen = false
        } catch (_: CancellationException) {
            settingsBackProgress = 0f
        } finally {
            settingsBackProgress = 0f
        }
    }

    BackHandler(enabled = !showSettingsScreen && acrSuccessData != null) {
        acrSuccessData = null
        showAcrResultCard = false
        acrViewModel.reset()
    }

    PredictiveBackHandler(
        enabled = !showSettingsScreen && acrSuccessData == null && (screenStack.isNotEmpty() || isSearching),
    ) { progressFlow ->
        try {
            isPageBackActive = true
            progressFlow.collect { backEvent ->
                pageBackAnimatable.snapTo(backEvent.progress)
                backSwipeEdge = backEvent.swipeEdge
            }
            navController.navigateBack()
        } catch (_: CancellationException) {
            pageBackAnimatable.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
        } finally {
            pageBackAnimatable.snapTo(0f)
            isPageBackActive = false
        }
    }

    val activeScreen: AppScreen =
        when {
            screenStack.isNotEmpty() -> {
                val baseDepth = if (isSearching) 1 else 0
                AppScreen.Detail(
                    destination = screenStack.last(),
                    depth = baseDepth + screenStack.size,
                )
            }
            isSearching -> AppScreen.Search
            else -> AppScreen.Home
        }

    val previousScreen: AppScreen? =
        when {
            screenStack.size > 1 -> {
                val baseDepth = if (isSearching) 1 else 0
                AppScreen.Detail(
                    destination = screenStack[screenStack.size - 2],
                    depth = baseDepth + screenStack.size - 1,
                )
            }
            screenStack.size == 1 -> {
                if (isSearching) AppScreen.Search else AppScreen.Home
            }
            isSearching -> AppScreen.Home
            else -> null
        }

    CompositionLocalProvider(LocalAppNavigation provides navController) {
        Box(modifier = modifier.fillMaxSize()) {
            PlayerContainer(
                modifier = Modifier.fillMaxSize(),
                isFullPlayerExpanded = isFullPlayerExpanded,
                onFullPlayerExpandChange = { isFullPlayerExpanded = it },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    // 若正在触发预测返回手势，在底层渲染上一级页面提供实时预览
                    if (isPageBackActive && previousScreen != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val progress = pageBackAnimatable.value
                                        val scale = 0.92f + (progress * 0.08f)
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = 0.6f + (progress * 0.4f)
                                        val sign = if (backSwipeEdge == BackEventCompat.EDGE_RIGHT) 1f else -1f
                                        translationX = sign * size.width * (1f - progress) * 0.08f
                                    },
                        ) {
                            RenderAppScreen(
                                screen = previousScreen,
                                innerPadding = innerPadding,
                                navController = navController,
                                onSearchClick = { isSearching = true },
                                onBackFromSearch = { isSearching = false },
                                onOpenSettings = { showSettingsScreen = true },
                                onOpenLogin = { showLoginDialog = true },
                                onOpenPlayer = { isFullPlayerExpanded = true },
                                acrViewModel = acrViewModel,
                                acrUiState = acrUiState,
                                acrSuccessData = acrSuccessData,
                                onActivateAcr = { isAcrControllerActive = true },
                                onCancelAcr = {
                                    isAcrControllerActive = false
                                    acrViewModel.reset()
                                },
                                onCloseAcr = {
                                    acrSuccessData = null
                                    showAcrResultCard = false
                                    isAcrCardInteracting = false
                                    acrViewModel.reset()
                                },
                                onExpandAcr = {
                                    val data = acrSuccessData
                                    if (data != null) {
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                        showAcrResultCard = false
                                    }
                                    isFullPlayerExpanded = true
                                },
                                onPlayAcrCard = {
                                    val data = acrSuccessData
                                    if (data != null) {
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                        showAcrResultCard = false
                                    }
                                },
                                onCollapseAcrCard = { showAcrResultCard = false },
                                onAcrCardInteractionChange = { isAcrCardInteracting = it },
                                showAcrResultCard = showAcrResultCard,
                                pagerState = pagerState,
                                chipScrollState = chipScrollState,
                                coroutineScope = coroutineScope,
                            )
                        }
                    }

                    // 当前顶层页面：手势过程中跟随手指实时缩放与位移
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val progress = pageBackAnimatable.value
                                    if (isPageBackActive && progress > 0f) {
                                        val scale = 1f - (progress * 0.08f)
                                        scaleX = scale
                                        scaleY = scale
                                        val sign = if (backSwipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
                                        translationX = sign * size.width * (progress * 0.35f)
                                        shape = RoundedCornerShape((progress * 24).dp)
                                        clip = true
                                    }
                                },
                    ) {
                        AnimatedContent(
                            targetState = activeScreen,
                            transitionSpec = {
                                val isForward = targetState.depth >= initialState.depth
                                if (isForward) {
                                    (
                                        slideInHorizontally(
                                            initialOffsetX = { fullWidth -> fullWidth },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                        ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
                                    ) togetherWith
                                        (
                                            slideOutHorizontally(
                                                targetOffsetX = { fullWidth -> -fullWidth / 3 },
                                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
                                        )
                                } else {
                                    (
                                        slideInHorizontally(
                                            initialOffsetX = { fullWidth -> -fullWidth / 3 },
                                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                                        ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing))
                                    ) togetherWith
                                        (
                                            slideOutHorizontally(
                                                targetOffsetX = { fullWidth -> fullWidth },
                                                animationSpec = tween(300, easing = FastOutSlowInEasing),
                                            ) + fadeOut(animationSpec = tween(200, easing = FastOutSlowInEasing))
                                        )
                                }
                            },
                            label = "main_screen_transition",
                            modifier = Modifier.fillMaxSize(),
                        ) { targetScreen ->
                            RenderAppScreen(
                                screen = targetScreen,
                                innerPadding = innerPadding,
                                navController = navController,
                                onSearchClick = { isSearching = true },
                                onBackFromSearch = { isSearching = false },
                                onOpenSettings = { showSettingsScreen = true },
                                onOpenLogin = { showLoginDialog = true },
                                onOpenPlayer = { isFullPlayerExpanded = true },
                                acrViewModel = acrViewModel,
                                acrUiState = acrUiState,
                                acrSuccessData = acrSuccessData,
                                onActivateAcr = { isAcrControllerActive = true },
                                onCancelAcr = {
                                    isAcrControllerActive = false
                                    acrViewModel.reset()
                                },
                                onCloseAcr = {
                                    acrSuccessData = null
                                    showAcrResultCard = false
                                    isAcrCardInteracting = false
                                    acrViewModel.reset()
                                },
                                onExpandAcr = {
                                    val data = acrSuccessData
                                    if (data != null) {
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                        showAcrResultCard = false
                                    }
                                    isFullPlayerExpanded = true
                                },
                                onPlayAcrCard = {
                                    val data = acrSuccessData
                                    if (data != null) {
                                        val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                        val prepLatencyMs = 850L
                                        val seekMs =
                                            ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                                .coerceAtLeast(0L)
                                        PlaybackManager.insertAndPlay(
                                            song = data.song,
                                            seekToMs = seekMs,
                                        )
                                        showAcrResultCard = false
                                    }
                                },
                                onCollapseAcrCard = { showAcrResultCard = false },
                                onAcrCardInteractionChange = { isAcrCardInteracting = it },
                                showAcrResultCard = showAcrResultCard,
                                pagerState = pagerState,
                                chipScrollState = chipScrollState,
                                coroutineScope = coroutineScope,
                            )
                        }
                    }
                }
            }

            if (showLoginDialog) {
                MobileLoginDialog(
                    onDismissRequest = { showLoginDialog = false },
                    onLoginSuccess = { showLoginDialog = false },
                )
            }

            // 无 UI 识曲控制器：挂载时自动检权并开始识别
            if (isAcrControllerActive) {
                AcrRecognitionController(
                    viewModel = acrViewModel,
                    onDismiss = { isAcrControllerActive = false },
                )
            }

            AnimatedVisibility(
                visible = showSettingsScreen,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (settingsBackProgress > 0f) {
                                val scale = 1f - (settingsBackProgress * 0.08f)
                                scaleX = scale
                                scaleY = scale
                                translationY = size.height * (settingsBackProgress * 0.15f)
                                shape = RoundedCornerShape((settingsBackProgress * 28).dp)
                                clip = true
                                alpha = 1f - (settingsBackProgress * 0.2f)
                            }
                        },
            ) {
                MobileSettingsScreen(
                    onBack = { showSettingsScreen = false },
                    onOpenLogin = { showLoginDialog = true },
                )
            }
        }
    }
}

@Composable
private fun RenderAppScreen(
    screen: AppScreen,
    innerPadding: PaddingValues,
    navController: AppNavigationController,
    onSearchClick: () -> Unit,
    onBackFromSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenPlayer: () -> Unit,
    acrViewModel: MobileAcrViewModel,
    acrUiState: MobileAcrUiState,
    acrSuccessData: MobileAcrSuccessData?,
    onActivateAcr: () -> Unit,
    onCancelAcr: () -> Unit,
    onCloseAcr: () -> Unit,
    onExpandAcr: () -> Unit,
    onPlayAcrCard: () -> Unit,
    onCollapseAcrCard: () -> Unit,
    onAcrCardInteractionChange: (Boolean) -> Unit,
    showAcrResultCard: Boolean,
    pagerState: androidx.compose.foundation.pager.PagerState,
    chipScrollState: androidx.compose.foundation.ScrollState,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (screen) {
            is AppScreen.Home -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                ) {
                    // 顶部头部：搜索胶囊 + 识曲胶囊/按钮 + 设置按钮
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val isAcrActive =
                            acrUiState is MobileAcrUiState.Listening ||
                                acrSuccessData != null

                        AnimatedContent(
                            targetState = isAcrActive,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                            label = "search_bar_morph",
                            modifier = if (isAcrActive) Modifier else Modifier.weight(1f),
                        ) { acrActive ->
                            if (acrActive) {
                                Surface(
                                    onClick = onSearchClick,
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = "搜索",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .clickable { onSearchClick() },
                                    shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border =
                                        androidx.compose.foundation.BorderStroke(
                                            0.5.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                        ),
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = "搜索",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "搜索歌曲、歌手或专辑...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedContent(
                            targetState = isAcrActive,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                            label = "acr_button_morph",
                            modifier = if (isAcrActive) Modifier.weight(1f) else Modifier,
                        ) { acrActive ->
                            if (acrActive) {
                                val pillUiState =
                                    if (acrSuccessData != null) {
                                        MobileAcrUiState.Success(
                                            song = acrSuccessData.song,
                                            offsetSeconds = acrSuccessData.offsetSeconds,
                                            anchorRealtimeMs = acrSuccessData.anchorRealtimeMs,
                                        )
                                    } else {
                                        acrUiState
                                    }
                                AcrPillBar(
                                    uiState = pillUiState,
                                    onCancel = onCancelAcr,
                                    onExpand = onExpandAcr,
                                    onClose = onCloseAcr,
                                    showBriefSuccess = showAcrResultCard,
                                    briefSuccessText = acrSuccessData?.durationText ?: "识别成功",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                FilledTonalIconButton(
                                    onClick = onActivateAcr,
                                    modifier = Modifier.size(44.dp),
                                    colors =
                                        IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.primary,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.GraphicEq,
                                        contentDescription = "听歌识曲",
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }

                        FilledTonalIconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(44.dp),
                            colors =
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "设置",
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    // 听歌识曲识别成功卡片（弹出 4 秒，宽度与顶栏胶囊一致，平滑缩回胶囊）
                    AnimatedVisibility(
                        visible = showAcrResultCard && acrSuccessData != null,
                        enter =
                            slideInVertically(
                                initialOffsetY = { -it },
                                animationSpec = tween(380, easing = FastOutSlowInEasing),
                            ) +
                                scaleIn(
                                    initialScale = 0.88f,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                    animationSpec = tween(380, easing = FastOutSlowInEasing),
                                ) + fadeIn(tween(280)),
                        exit =
                            slideOutVertically(
                                targetOffsetY = { -it },
                                animationSpec = tween(380, easing = FastOutSlowInEasing),
                            ) +
                                scaleOut(
                                    targetScale = 0.88f,
                                    transformOrigin = TransformOrigin(0.5f, 0f),
                                    animationSpec = tween(380, easing = FastOutSlowInEasing),
                                ) + fadeOut(tween(240)),
                    ) {
                        val song = acrSuccessData?.song
                        if (song != null) {
                            val currentPlayingSong by PlaybackManager.currentSong.collectAsState()
                            val isPlaying by PlaybackManager.isPlaying.collectAsState()
                            val isThisSongPlaying = currentPlayingSong?.songMid == song.songMid && isPlaying

                            AcrResultCard(
                                song = song,
                                isPlaying = isThisSongPlaying,
                                onPlayClick = onPlayAcrCard,
                                onCollapse = onCollapseAcrCard,
                                onInteractionStateChange = onAcrCardInteractionChange,
                                onPrepareSong = {
                                    val data = acrSuccessData
                                    val elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime() - data.anchorRealtimeMs
                                    val prepLatencyMs = 850L
                                    val seekMs =
                                        ((data.offsetSeconds * 1000).toLong() + elapsedRealtimeMs + prepLatencyMs)
                                            .coerceAtLeast(0L)
                                    PlaybackManager.insertAndPlay(
                                        song = data.song,
                                        seekToMs = seekMs,
                                    )
                                },
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(chipScrollState)
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HomeFilter.entries.forEachIndexed { index, filter ->
                            FilterChip(
                                selected = pagerState.currentPage == index,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                label = { Text(filter.title) },
                                shape = RoundedCornerShape(8.dp),
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                            )
                        }
                    }

                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) { page ->
                        when (HomeFilter.entries[page]) {
                            HomeFilter.Discover ->
                                DiscoverScreen(
                                    contentPadding = innerPadding,
                                    onRequireLogin = onOpenLogin,
                                    onOpenPlayer = onOpenPlayer,
                                )
                            HomeFilter.Library ->
                                LibraryScreen(
                                    contentPadding = innerPadding,
                                    onRequireLogin = onOpenLogin,
                                    onOpenPlaylist = { playlist -> navController.navigateToPlaylist(playlist) },
                                )
                            HomeFilter.Recent ->
                                RecentPlaybackScreen(
                                    contentPadding = innerPadding,
                                )
                            HomeFilter.Download ->
                                DownloadMobileScreen(
                                    contentPadding = innerPadding,
                                )
                            HomeFilter.WebDav ->
                                WebDavMobileScreen(
                                    contentPadding = innerPadding,
                                )
                            HomeFilter.Local ->
                                LocalMusicMobileScreen(
                                    contentPadding = innerPadding,
                                )
                            HomeFilter.Remote ->
                                RemoteControlMobileScreen(
                                    contentPadding = innerPadding,
                                )
                        }
                    }
                }
            }
            is AppScreen.Search -> {
                SearchScreen(
                    contentPadding = innerPadding,
                    onBack = onBackFromSearch,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding(),
                )
            }
            is AppScreen.Detail -> {
                when (val currentScreen = screen.destination) {
                    is ScreenDestination.PlaylistDetail -> {
                        PlaylistDetailScreen(
                            playlist = currentScreen.playlist,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                        )
                    }
                    is ScreenDestination.ArtistDetail -> {
                        ArtistDetailScreen(
                            artistMid = currentScreen.artistMid,
                            artistName = currentScreen.artistName,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                        )
                    }
                    is ScreenDestination.AlbumDetail -> {
                        AlbumDetailScreen(
                            albumMid = currentScreen.albumMid,
                            albumName = currentScreen.albumName,
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                        )
                    }
                    is ScreenDestination.FavoriteAlbums -> {
                        FavoriteAlbumsScreen(
                            contentPadding = innerPadding,
                            onBack = { navController.navigateBack() },
                            onAlbumClick = { album ->
                                navController.navigateToAlbum(album.mid, album.name)
                            },
                        )
                    }
                }
            }
        }
    }
}
