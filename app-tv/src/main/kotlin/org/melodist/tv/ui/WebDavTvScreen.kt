package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import org.melodist.api.WebDavService
import org.melodist.data.WebDavManager
import org.melodist.model.Song
import org.melodist.model.WebDavItem
import org.melodist.model.WebDavServer
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics

enum class WebDavViewMode {
    Directory, // 目录树浏览
    Library, // 云端音乐库
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WebDavTvScreen(
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit,
) {
    val metrics = rememberTvWindowMetrics()
    val scope = rememberCoroutineScope()
    val webDavService = remember { WebDavService() }

    var currentServer by remember { mutableStateOf(WebDavManager.getActiveServer()) }
    var currentPath by remember { mutableStateOf(currentServer?.rootPath?.ifBlank { "/" } ?: "/") }
    var pathHistory by remember { mutableStateOf<List<String>>(listOf(currentPath)) }

    val cachedSongs =
        remember(currentServer) {
            val server = currentServer ?: return@remember emptyList()
            server.cachedSongs.map { cache ->
                val s = cache.toSong()
                if (s.coverUrl.isBlank()) {
                    val existing = WebDavManager.getSongCoverPath(server.id, cache.href)
                    if (!existing.isNullOrBlank()) s.copy(coverUrl = existing) else s
                } else {
                    s
                }
            }
        }
    var viewMode by remember(currentServer?.id) {
        mutableStateOf(if (cachedSongs.isNotEmpty()) WebDavViewMode.Library else WebDavViewMode.Directory)
    }

    var items by remember { mutableStateOf<List<WebDavItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var isScanningMetadata by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showConfigDialog by remember { mutableStateOf(false) }
    var showServerSelectDialog by remember { mutableStateOf(false) }

    fun loadPath(targetPath: String) {
        val server = currentServer
        if (server == null) {
            items = emptyList()
            return
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val list = webDavService.listDirectory(server, targetPath)
                items = list
                currentPath = targetPath
            } catch (e: Exception) {
                errorMessage = "读取目录失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun startScan(targetFolder: String) {
        val server = currentServer ?: return
        if (isScanning) return
        isScanning = true
        scanStatus = "准备递归扫描 WebDAV 目录..."
        scope.launch {
            try {
                val songs =
                    WebDavManager.scanAndEnrichFolder(server, targetFolder) { title, cur, total ->
                        scanStatus = if (total > 0) "正在扫描解析: $title ($cur/$total)" else title
                    }
                scanStatus = "扫描完成，共收录 ${songs.size} 首歌曲！点击上方【音乐库】即可浏览"
                currentServer = WebDavManager.getActiveServer()
            } catch (e: Exception) {
                scanStatus = "扫描出错: ${e.message}"
            } finally {
                isScanning = false
            }
        }
    }

    fun startScanMissingMetadata() {
        val server = currentServer ?: return
        if (isScanningMetadata) return
        isScanningMetadata = true
        scanStatus = "准备检查并补充歌曲信息..."
        scope.launch {
            try {
                val songs =
                    WebDavManager.reEnrichMissingMetadata(server) { title, cur, total ->
                        scanStatus = if (total > 0) "正在补充信息: $title ($cur/$total)" else title
                    }
                scanStatus = "信息更新完成，曲库已刷新"
                currentServer = WebDavManager.getActiveServer()
            } catch (e: Exception) {
                scanStatus = "补充信息出错: ${e.message}"
            } finally {
                isScanningMetadata = false
            }
        }
    }

    LaunchedEffect(currentServer) {
        if (currentServer != null) {
            val root = currentServer?.rootPath?.ifBlank { "/" } ?: "/"
            currentPath = root
            pathHistory = listOf(root)
            loadPath(root)
        } else {
            items = emptyList()
        }
    }

    // 系统返回键：仅在目录浏览多级路径时回退上一级目录，音乐库界面或根目录直接退出至主界面
    BackHandler {
        if (viewMode == WebDavViewMode.Directory && pathHistory.size > 1) {
            val newHistory = pathHistory.dropLast(1)
            pathHistory = newHistory
            loadPath(newHistory.last())
        } else {
            onBack()
        }
    }

    val isRoot = pathHistory.size <= 1 && currentPath == (currentServer?.rootPath?.ifBlank { "/" } ?: "/")

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(
                    horizontal = metrics.horizontalSafePadding,
                    vertical = metrics.verticalSafePadding,
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 顶部导航与操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 顶部的返回按钮直接退出到上层主界面
                    WebDavNavButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        text = "返回界面",
                        onClick = onBack,
                    )

                    Text(
                        text = currentServer?.name ?: "WebDAV",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MelodistColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (currentServer != null) {
                        WebDavNavButton(
                            icon = Icons.Default.LibraryMusic,
                            text = "音乐库 (${cachedSongs.size} 首)",
                            isAccent = (viewMode == WebDavViewMode.Library),
                            onClick = { viewMode = WebDavViewMode.Library },
                        )

                        WebDavNavButton(
                            icon = Icons.Default.Folder,
                            text = "目录浏览",
                            isAccent = (viewMode == WebDavViewMode.Directory),
                            onClick = { viewMode = WebDavViewMode.Directory },
                        )
                    }

                    if (WebDavManager.getServers().isNotEmpty()) {
                        WebDavNavButton(
                            icon = Icons.Default.SwapHoriz,
                            text = "切换",
                            onClick = { showServerSelectDialog = true },
                        )
                    }

                    WebDavNavButton(
                        icon = Icons.Default.Settings,
                        text = if (currentServer != null) "配置管理" else "添加服务",
                        onClick = { showConfigDialog = true },
                    )

                    if (currentServer != null && viewMode == WebDavViewMode.Library) {
                        WebDavNavButton(
                            icon = Icons.Default.Sync,
                            text = if (isScanningMetadata) "扫描中..." else "扫描补充信息",
                            onClick = { startScanMissingMetadata() },
                        )
                    }

                    if (currentServer != null && viewMode == WebDavViewMode.Directory) {
                        WebDavNavButton(
                            icon = Icons.Default.Sync,
                            text = if (isScanning) "扫描中..." else "扫描本目录",
                            isAccent = true,
                            onClick = { startScan(currentPath) },
                        )

                        val audioItems = items.filter { !it.isDirectory }
                        if (audioItems.isNotEmpty()) {
                            WebDavNavButton(
                                icon = Icons.Default.PlayArrow,
                                text = "播放本目录 (${audioItems.size} 首)",
                                onClick = {
                                    val server = currentServer ?: return@WebDavNavButton
                                    val cachedMap = currentServer?.cachedSongs?.associateBy { it.href }
                                    val songList =
                                        audioItems.map { item ->
                                            val (title, artist) = WebDavService.inferTitleArtist(item.name)
                                            val cached = cachedMap?.get(item.href)
                                            val coverUrl =
                                                cached?.coverPath?.let { if (it.startsWith("/")) "file://$it" else it }
                                                    ?: WebDavManager.getSongCoverPath(server.id, item.href)
                                            item.toSong(server.id, title, artist, coverUrl = coverUrl)
                                        }
                                    PlaybackManager.setPlaylist(songList, startIndex = 0)
                                    onNavigateToPlayer()
                                },
                            )
                        }
                    }
                }
            }

            if (viewMode == WebDavViewMode.Library) {
                // 展示 WebDAV 音乐库视图
                WebDavLibraryView(
                    cachedSongs = cachedSongs,
                    isScanningMetadata = isScanningMetadata,
                    onScanMissingMetadata = { startScanMissingMetadata() },
                    onClearLibrary = {
                        val sid = currentServer?.id
                        if (sid != null) {
                            WebDavManager.clearCachedSongs(sid)
                            currentServer = WebDavManager.getActiveServer()
                            scanStatus = "曲库已清空"
                        }
                    },
                    onSongClick = { index ->
                        PlaybackManager.setPlaylist(cachedSongs, startIndex = index)
                        onNavigateToPlayer()
                    },
                    onPlayAll = {
                        if (cachedSongs.isNotEmpty()) {
                            PlaybackManager.setPlaylist(cachedSongs, startIndex = 0)
                            onNavigateToPlayer()
                        }
                    },
                    onSwitchToDirectory = {
                        viewMode = WebDavViewMode.Directory
                    },
                )
            } else {
                // 展示目录浏览视图
                // 面包屑路径指示条与扫描状态
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MelodistColors.AccentGreen,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "当前路径: $currentPath",
                            fontSize = 13.sp,
                            color = MelodistColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (scanStatus != null) {
                        Text(
                            text = scanStatus ?: "",
                            fontSize = 13.sp,
                            color = if (isScanning) MelodistColors.FocusTeal else MelodistColors.AccentGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                // 目录与音频文件主体列表
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) {
                    if (currentServer == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = MelodistColors.TextMuted,
                                    modifier = Modifier.size(64.dp),
                                )
                                Text(
                                    text = "暂未配置 WebDAV 私有云盘服务",
                                    fontSize = 16.sp,
                                    color = MelodistColors.TextSecondary,
                                )
                                WebDavNavButton(
                                    icon = Icons.Default.Add,
                                    text = "立即添加 WebDAV 服务器",
                                    isAccent = true,
                                    onClick = { showConfigDialog = true },
                                )
                            }
                        }
                    } else if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "正在读取 WebDAV 目录树...", color = MelodistColors.AccentGreen, fontSize = 16.sp)
                        }
                    } else if (errorMessage != null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = errorMessage ?: "读取失败", color = Color.Red, fontSize = 15.sp)
                        }
                    } else if (items.isEmpty() && isRoot) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "当前目录为空或未发现支持的音频文件", color = MelodistColors.TextMuted, fontSize = 15.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 非根目录时，列表首项显示返回上一级 ..
                            if (!isRoot) {
                                item(key = "parent_dir_back") {
                                    WebDavParentFolderRow(
                                        onClick = {
                                            if (pathHistory.size > 1) {
                                                val newHistory = pathHistory.dropLast(1)
                                                pathHistory = newHistory
                                                loadPath(newHistory.last())
                                            } else {
                                                val parent = currentPath.trimEnd('/').substringBeforeLast('/', "")
                                                val target = if (parent.isBlank()) "/" else "$parent/"
                                                loadPath(target)
                                            }
                                        },
                                    )
                                }
                            }

                            itemsIndexed(items, key = { _, item -> item.href }) { _, item ->
                                WebDavItemRow(
                                    item = item,
                                    onClick = {
                                        if (item.isDirectory) {
                                            pathHistory = pathHistory + item.href
                                            loadPath(item.href)
                                        } else {
                                            val server = currentServer ?: return@WebDavItemRow
                                            val audioItems = items.filter { !it.isDirectory }
                                            val cachedMap = currentServer?.cachedSongs?.associateBy { it.href }
                                            val songList =
                                                audioItems.map { itm ->
                                                    val (t, a) = WebDavService.inferTitleArtist(itm.name)
                                                    val cached = cachedMap?.get(itm.href)
                                                    val coverUrl =
                                                        cached?.coverPath?.let { if (it.startsWith("/")) "file://$it" else it }
                                                            ?: WebDavManager.getSongCoverPath(server.id, itm.href)
                                                    itm.toSong(server.id, t, a, coverUrl = coverUrl)
                                                }
                                            val targetIdx = audioItems.indexOf(item).coerceAtLeast(0)
                                            PlaybackManager.setPlaylist(songList, startIndex = targetIdx)
                                            onNavigateToPlayer()
                                        }
                                    },
                                    onLongClick =
                                        if (item.isDirectory) {
                                            { startScan(item.href) }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 配置/添加服务器弹窗
        if (showConfigDialog) {
            WebDavServerConfigDialog(
                initialServer = currentServer ?: WebDavServer(),
                onSave = { updated ->
                    WebDavManager.saveServer(updated)
                    currentServer = WebDavManager.getActiveServer()
                    showConfigDialog = false
                },
                onDelete = { serverId ->
                    WebDavManager.removeServer(serverId)
                    currentServer = WebDavManager.getActiveServer()
                    showConfigDialog = false
                },
                onDismiss = { showConfigDialog = false },
            )
        }

        // 切换服务器选择弹窗
        if (showServerSelectDialog) {
            WebDavServerSelectDialog(
                servers = WebDavManager.getServers(),
                activeServerId = currentServer?.id.orEmpty(),
                onSelect = { server ->
                    WebDavManager.setActiveServer(server.id)
                    currentServer = server
                    showServerSelectDialog = false
                },
                onDismiss = { showServerSelectDialog = false },
            )
        }
    }
}

/**
 * WebDAV 音乐库视图
 */
@Composable
private fun WebDavLibraryView(
    cachedSongs: List<Song>,
    isScanningMetadata: Boolean = false,
    onScanMissingMetadata: () -> Unit = {},
    onClearLibrary: () -> Unit = {},
    onSongClick: (Int) -> Unit,
    onPlayAll: () -> Unit,
    onSwitchToDirectory: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 曲库概览与批量操作栏
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = MelodistColors.AccentGreen,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "WebDAV 云端曲库（共 ${cachedSongs.size} 首歌曲）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MelodistColors.TextPrimary,
                )
            }

            if (cachedSongs.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WebDavNavButton(
                        icon = Icons.Default.PlayArrow,
                        text = "播放全部",
                        isAccent = true,
                        onClick = onPlayAll,
                    )

                    WebDavNavButton(
                        icon = Icons.Default.Sync,
                        text = if (isScanningMetadata) "正在提取信息..." else "扫描信息",
                        onClick = onScanMissingMetadata,
                    )

                    WebDavNavButton(
                        icon = Icons.Default.DeleteSweep,
                        text = "清空曲库",
                        onClick = onClearLibrary,
                    )
                }
            }
        }

        if (cachedSongs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = null,
                        tint = MelodistColors.TextMuted,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = "WebDAV 音乐库暂无歌曲",
                        fontSize = 16.sp,
                        color = MelodistColors.TextSecondary,
                    )
                    Text(
                        text = "请切换至【目录浏览】并在音频目录点击【扫描本目录】开始收录",
                        fontSize = 13.sp,
                        color = MelodistColors.TextMuted,
                    )
                    WebDavNavButton(
                        icon = Icons.Default.Folder,
                        text = "前往目录浏览",
                        isAccent = true,
                        onClick = onSwitchToDirectory,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(cachedSongs, key = { index, s -> "${s.songMid}_$index" }) { index, song ->
                    WebDavLibrarySongRow(
                        index = index + 1,
                        song = song,
                        onClick = { onSongClick(index) },
                    )
                }
            }
        }
    }
}

/**
 * 音乐库单曲行组件
 */
@Composable
private fun WebDavLibrarySongRow(
    index: Int,
    song: Song,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                        ),
                    shape = RoundedCornerShape(10.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 序号
        Text(
            text = String.format("%02d", index),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (isFocused) Color.Black else Color.White,
            modifier = Modifier.width(36.dp),
        )

        Spacer(modifier = Modifier.width(10.dp))

        // 歌名
        Text(
            text = song.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.44f),
        )

        // 艺术家（靠左对齐，白色）
        Text(
            text = song.singer,
            fontSize = 13.sp,
            color = if (isFocused) Color.Black else Color.White,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(0.28f)
                    .padding(horizontal = 8.dp),
        )

        // 专辑（靠左对齐，白色）
        Text(
            text = song.album,
            fontSize = 13.sp,
            color = if (isFocused) Color.Black else Color.White,
            textAlign = TextAlign.Start,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(0.28f)
                    .padding(horizontal = 8.dp),
        )
    }
}

/**
 * 列表首项展示的返回上一级目录 ..
 */
@Composable
private fun WebDavParentFolderRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.08f),
                        ),
                    shape = RoundedCornerShape(10.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = if (isFocused) Color.Black else MelodistColors.AccentGreen,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = "..  (返回上一级目录)",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else MelodistColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WebDavItemRow(
    item: WebDavItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                        ),
                    shape = RoundedCornerShape(10.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.MusicNote,
            contentDescription = null,
            tint =
                if (isFocused) {
                    Color.Black
                } else if (item.isDirectory) {
                    MelodistColors.AccentGreen
                } else {
                    Color.White
                },
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = item.name,
            fontSize = 15.sp,
            fontWeight = if (item.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isFocused) Color.Black else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!item.isDirectory && item.contentLength > 0L) {
            val sizeMb = "%.1f MB".format(item.contentLength / (1024.0 * 1024.0))
            Text(
                text = sizeMb,
                fontSize = 13.sp,
                color = if (isFocused) Color.DarkGray else Color.White.copy(alpha = 0.7f),
            )
        } else if (item.isDirectory) {
            Text(
                text = "长按递归扫描",
                fontSize = 12.sp,
                color = if (isFocused) Color.DarkGray else MelodistColors.TextMuted,
            )
        }
    }
}

@Composable
private fun WebDavNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    isAccent: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val baseBgColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
    val baseBorderColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.50f)
        } else {
            Color.White.copy(alpha = 0.18f)
        }
    val baseContentColor =
        if (isAccent) {
            MelodistColors.AccentGreen
        } else {
            MelodistColors.TextPrimary
        }

    Row(
        modifier =
            modifier
                .height(40.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else baseBorderColor,
                        ),
                    shape = MelodistShapes.PillCorner,
                ).background(
                    color = if (isFocused) Color.White else baseBgColor,
                    shape = MelodistShapes.PillCorner,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (isFocused) Color.Black else baseContentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            color = if (isFocused) Color.Black else baseContentColor,
        )
    }
}

@Composable
private fun WebDavServerConfigDialog(
    initialServer: WebDavServer,
    onSave: (WebDavServer) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialServer.name) }
    var url by remember { mutableStateOf(initialServer.url) }
    var username by remember { mutableStateOf(initialServer.username) }
    var password by remember { mutableStateOf(initialServer.password) }
    var rootPath by remember { mutableStateOf(initialServer.rootPath.ifBlank { "/" }) }
    var trustSelfSigned by remember { mutableStateOf(initialServer.trustSelfSigned) }

    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val webDavService = remember { WebDavService() }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(680.dp)
                    .clip(MelodistShapes.CardCorner)
                    .background(MelodistColors.ContainerDark)
                    .border(BorderStroke(2.dp, MelodistColors.FocusTeal.copy(alpha = 0.6f)), MelodistShapes.CardCorner)
                    .padding(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (initialServer.id.isNotBlank()) "编辑 WebDAV 服务器" else "添加 WebDAV 服务器",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )

                WebDavInputField(
                    label = "名称",
                    value = name,
                    keyboardType = KeyboardType.Text,
                    onValueChange = { name = it },
                )
                WebDavInputField(
                    label = "地址",
                    value = url,
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { url = it },
                )
                WebDavInputField(
                    label = "账号",
                    value = username,
                    keyboardType = KeyboardType.Ascii,
                    onValueChange = { username = it },
                )
                WebDavInputField(
                    label = "密码",
                    value = password,
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    onValueChange = { password = it },
                )
                WebDavInputField(
                    label = "路径",
                    value = rootPath,
                    keyboardType = KeyboardType.Uri,
                    onValueChange = { rootPath = it },
                )

                if (testStatus != null) {
                    Text(
                        text = testStatus ?: "",
                        fontSize = 13.sp,
                        color = if (testStatus?.contains("成功") == true) MelodistColors.AccentGreen else Color.Red,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (initialServer.id.isNotBlank()) {
                        WebDavNavButton(
                            icon = Icons.Default.Delete,
                            text = "删除服务",
                            onClick = { onDelete(initialServer.id) },
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WebDavNavButton(
                            icon = Icons.Default.NetworkCheck,
                            text = if (isTesting) "测试中..." else "测试连接",
                            onClick = {
                                if (url.isBlank()) {
                                    testStatus = "请输入服务器地址"
                                    return@WebDavNavButton
                                }
                                isTesting = true
                                testStatus = "正在测试连接..."
                                scope.launch {
                                    val temp =
                                        initialServer.copy(
                                            name = name,
                                            url = url,
                                            username = username,
                                            password = password,
                                            rootPath = rootPath,
                                            trustSelfSigned = trustSelfSigned,
                                        )
                                    val (ok, msg) = webDavService.testConnection(temp)
                                    testStatus = msg
                                    isTesting = false
                                }
                            },
                        )

                        WebDavNavButton(
                            icon = Icons.Default.Close,
                            text = "取消",
                            onClick = onDismiss,
                        )

                        WebDavNavButton(
                            icon = Icons.Default.Check,
                            text = "保存",
                            isAccent = true,
                            onClick = {
                                val updated =
                                    initialServer.copy(
                                        name = name.ifBlank { "我的 WebDAV" },
                                        url = url,
                                        username = username,
                                        password = password,
                                        rootPath = rootPath.ifBlank { "/" },
                                        trustSelfSigned = trustSelfSigned,
                                    )
                                onSave(updated)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDavInputField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(MelodistColors.ContainerDarkSecondary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .border(
                    width = if (isFocused) 2.dp else 1.dp,
                    color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MelodistColors.TextSecondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(64.dp),
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = keyboardType,
                    autoCorrectEnabled = false,
                ),
            textStyle =
                TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                ),
            cursorBrush = SolidColor(MelodistColors.AccentGreen),
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WebDavServerSelectDialog(
    servers: List<WebDavServer>,
    activeServerId: String,
    onSelect: (WebDavServer) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(520.dp)
                    .clip(MelodistShapes.CardCorner)
                    .background(MelodistColors.ContainerDark)
                    .border(BorderStroke(2.dp, MelodistColors.FocusTeal.copy(alpha = 0.6f)), MelodistShapes.CardCorner)
                    .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "切换 WebDAV 服务器",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MelodistColors.TextPrimary,
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(servers) { _, s ->
                        val isCurrent = s.id == activeServerId
                        WebDavNavButton(
                            icon = if (isCurrent) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            text = "${s.name} (${s.url})",
                            isAccent = isCurrent,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelect(s) },
                        )
                    }
                }
            }
        }
    }
}
