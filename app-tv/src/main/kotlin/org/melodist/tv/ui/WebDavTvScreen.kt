package org.melodist.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import kotlinx.coroutines.launch
import org.melodist.api.WebDavService
import org.melodist.data.WebDavManager
import org.melodist.model.WebDavItem
import org.melodist.model.WebDavServer
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.rememberTvWindowMetrics
import org.melodist.tv.ui.webdav.WebDavItemRow
import org.melodist.tv.ui.webdav.WebDavLibraryView
import org.melodist.tv.ui.webdav.WebDavNavButton
import org.melodist.tv.ui.webdav.WebDavParentFolderRow
import org.melodist.tv.ui.webdav.WebDavServerConfigDialog
import org.melodist.tv.ui.webdav.WebDavServerSelectDialog

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
