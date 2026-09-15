package org.melodist.mobile.ui.webdav

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.melodist.api.WebDavService
import org.melodist.data.WebDavManager
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.mobile.ui.storage.StorageDirectoryListView
import org.melodist.mobile.ui.storage.StorageItemModel
import org.melodist.mobile.ui.storage.StoragePathBreadcrumbs
import org.melodist.mobile.ui.storage.StorageScanProgressCard
import org.melodist.model.WebDavItem
import org.melodist.model.WebDavServer
import org.melodist.playback.PlaybackManager

enum class WebDavViewMode {
    Library, // 已扫描云端音乐库
    Directory, // 目录树文件浏览
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavMobileScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webDavService = remember { WebDavService() }

    var currentServer by remember { mutableStateOf(WebDavManager.getActiveServer()) }
    var servers by remember { mutableStateOf(WebDavManager.getServers()) }

    var cachedSongs by remember(currentServer?.id) {
        mutableStateOf(WebDavManager.getAllCachedSongs(currentServer?.id))
    }

    var viewMode by remember(currentServer?.id) {
        mutableStateOf(if (cachedSongs.isNotEmpty()) WebDavViewMode.Library else WebDavViewMode.Directory)
    }

    var currentPath by remember(currentServer?.id) {
        mutableStateOf(currentServer?.rootPath?.ifBlank { "/" } ?: "/")
    }
    var pathHistory by remember(currentServer?.id) {
        mutableStateOf<List<String>>(listOf(currentPath))
    }

    var directoryItems by remember { mutableStateOf<List<WebDavItem>>(emptyList()) }
    var isLoadingDirectory by remember { mutableStateOf(false) }

    var isScanning by remember { mutableStateOf(false) }
    var isScanningMetadata by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    var showConfigDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<WebDavServer?>(null) }
    var showServerSelectSheet by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    fun refreshCachedSongs() {
        cachedSongs = WebDavManager.getAllCachedSongs(currentServer?.id)
    }

    fun loadPath(targetPath: String) {
        val server = currentServer ?: return
        isLoadingDirectory = true
        scope.launch {
            try {
                directoryItems = webDavService.listDirectory(server, targetPath)
                currentPath = targetPath
            } catch (e: Exception) {
                Toast.makeText(context, "加载目录失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingDirectory = false
            }
        }
    }

    fun startScan(targetFolder: String) {
        val server = currentServer ?: return
        if (isScanning || isScanningMetadata) return
        isScanning = true
        scanStatus = "正在检索目录中的音频文件..."
        scanJob =
            scope.launch {
                try {
                    WebDavManager.scanAndEnrichFolder(server, targetFolder) { title, cur, total ->
                        scanStatus = if (total > 0) "[$cur/$total] $title" else "[$cur] $title"
                    }
                    refreshCachedSongs()
                    viewMode = WebDavViewMode.Library
                    Toast.makeText(context, "媒体库扫描完成", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isScanning = false
                    scanStatus = null
                }
            }
    }

    fun startScanMissingMetadata(silent: Boolean = false) {
        val server = currentServer ?: return
        if (isScanning || isScanningMetadata) return
        isScanningMetadata = true
        if (!silent) {
            scanStatus = "正在检查并补充歌曲信息与专辑图片..."
        }
        scanJob =
            scope.launch {
                try {
                    val songs =
                        WebDavManager.reEnrichMissingMetadata(server) { title, cur, total ->
                            if (!silent) {
                                scanStatus = if (total > 0) "[$cur/$total] $title" else "[$cur] $title"
                            }
                        }
                    refreshCachedSongs()
                    if (!silent) {
                        Toast.makeText(context, "已更新 ${songs.size} 首歌曲信息与封面", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    if (!silent) {
                        Toast.makeText(context, "扫描补充失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    isScanningMetadata = false
                    scanStatus = null
                }
            }
    }

    LaunchedEffect(currentServer?.id, viewMode) {
        val server = currentServer
        if (server != null) {
            if (viewMode == WebDavViewMode.Directory) {
                loadPath(currentPath)
            } else if (viewMode == WebDavViewMode.Library) {
                refreshCachedSongs()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (currentServer == null) {
            // 未配置服务器状态
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "未配置 WebDAV 服务器",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "添加你的 NAS、网盘或云存储，畅听云端音乐",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        editingServer = null
                        showConfigDialog = true
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("添加 WebDAV 服务器")
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部控制栏：服务器信息行与模式切换行
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 当前服务器切换胶囊
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Surface(
                            onClick = { showServerSelectSheet = true },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = currentServer?.name ?: "WebDAV",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "切换服务器",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        // 服务器地址提示
                        currentServer?.let { server ->
                            Text(
                                text = server.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }

                    // 视图模式切换：音乐库 vs 目录文件 + 右侧扫描按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = viewMode == WebDavViewMode.Library,
                                onClick = {
                                    viewMode = WebDavViewMode.Library
                                    refreshCachedSongs()
                                },
                                label = { Text("音乐库 (${cachedSongs.size})") },
                                leadingIcon = {
                                    Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            FilterChip(
                                selected = viewMode == WebDavViewMode.Directory,
                                onClick = {
                                    viewMode = WebDavViewMode.Directory
                                    loadPath(currentPath)
                                },
                                label = { Text("文件目录") },
                                leadingIcon = {
                                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }

                        // 扫描与自愈按钮
                        FilledTonalButton(
                            onClick = {
                                if (viewMode == WebDavViewMode.Directory) {
                                    startScan(currentPath)
                                } else {
                                    startScanMissingMetadata(silent = false)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                            enabled = currentServer != null && !isScanning && !isScanningMetadata,
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (isScanning || isScanningMetadata) "扫描中" else "扫描",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                // 扫描进度卡片
                if ((isScanning || isScanningMetadata) && scanStatus != null) {
                    StorageScanProgressCard(
                        scanStatus = scanStatus!!,
                        onCancel = {
                            scanJob?.cancel()
                            isScanning = false
                            isScanningMetadata = false
                            scanStatus = null
                            refreshCachedSongs()
                        },
                    )
                }

                // 主内容区
                when (viewMode) {
                    WebDavViewMode.Library -> {
                        if (cachedSongs.isEmpty()) {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(contentPadding),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.LibraryMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.size(56.dp),
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "暂无已缓存的云端音乐",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(onClick = {
                                        viewMode = WebDavViewMode.Directory
                                        loadPath(currentServer?.rootPath?.ifBlank { "/" } ?: "/")
                                    }) {
                                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("浏览目录并扫描")
                                    }
                                }
                            }
                        } else {
                            CommonSongList(
                                songs = cachedSongs,
                                showWebDavBadge = false,
                                deleteType = SongListDeleteType.WebDavFile,
                                enableDownload = false,
                                onDeleteSelected = {
                                    cachedSongs = WebDavManager.getAllCachedSongs()
                                },
                                contentPadding =
                                    PaddingValues(
                                        top = 2.dp,
                                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                    ),
                                headerItems = {
                                    item(key = "webdav_library_header") {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "共 ${cachedSongs.size} 首歌曲",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            TextButton(onClick = { showClearConfirmDialog = true }) {
                                                Text("清空库", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }

                    WebDavViewMode.Directory -> {
                        // 面包屑导航与当前目录扫描操作
                        Column(modifier = Modifier.fillMaxSize()) {
                            StoragePathBreadcrumbs(
                                currentPath = currentPath,
                                onNavigateToSegment = { segmentPath ->
                                    pathHistory = pathHistory.takeWhile { it != segmentPath } + segmentPath
                                    loadPath(segmentPath)
                                },
                                onNavigateUp = {
                                    if (pathHistory.size > 1) {
                                        val nextHist = pathHistory.dropLast(1)
                                        pathHistory = nextHist
                                        loadPath(nextHist.last())
                                    }
                                },
                                canNavigateUp = pathHistory.size > 1,
                            )

                            // 目录快捷操作栏
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "当前包含 ${directoryItems.size} 项",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(
                                    onClick = { startScan(currentPath) },
                                    enabled = !isScanning,
                                ) {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("扫描此目录到音乐库", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (isLoadingDirectory) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                }
                            } else {
                                val storageItems =
                                    directoryItems.map { item ->
                                        val sizeFormatted =
                                            if (!item.isDirectory && item.contentLength > 0) {
                                                "%.1f MB".format(item.contentLength / (1024f * 1024f))
                                            } else {
                                                ""
                                            }
                                        StorageItemModel(
                                            id = item.href,
                                            name = item.name,
                                            isDirectory = item.isDirectory,
                                            detailText = sizeFormatted,
                                            raw = item,
                                        )
                                    }

                                StorageDirectoryListView(
                                    items = storageItems,
                                    onItemClick = { clicked ->
                                        val item = clicked.raw as WebDavItem
                                        if (item.isDirectory) {
                                            pathHistory = pathHistory + item.href
                                            loadPath(item.href)
                                        } else {
                                            val (title, artist) = WebDavService.inferTitleArtist(item.name)
                                            val coverUrl = WebDavManager.getSongCoverPath(currentServer!!.id, item.href)
                                            val song = item.toSong(currentServer!!.id, title, artist, localPath = coverUrl)
                                            PlaybackManager.playSong(song)
                                        }
                                    },
                                    contentPadding =
                                        PaddingValues(
                                            bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 服务器切换与管理底部弹窗
    if (showServerSelectSheet) {
        WebDavServerSelectSheet(
            servers = servers,
            currentServer = currentServer,
            onSelectServer = { server ->
                WebDavManager.setActiveServer(server.id)
                currentServer = server
                refreshCachedSongs()
                showServerSelectSheet = false
            },
            onAddServer = {
                showServerSelectSheet = false
                editingServer = null
                showConfigDialog = true
            },
            onEditServer = { server ->
                showServerSelectSheet = false
                editingServer = server
                showConfigDialog = true
            },
            onDeleteServer = { server ->
                WebDavManager.removeServer(server.id)
                servers = WebDavManager.getServers()
                currentServer = WebDavManager.getActiveServer()
                refreshCachedSongs()
            },
            onDismissRequest = { showServerSelectSheet = false },
        )
    }

    // 清空缓存确认对话框
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空云端音乐缓存") },
            text = { Text("确定要清除当前服务器的所有已扫描歌曲缓存吗？远端实际文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    WebDavManager.clearCachedSongs(currentServer?.id)
                    refreshCachedSongs()
                    showClearConfirmDialog = false
                }) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 编辑/新建服务器弹窗
    if (showConfigDialog) {
        WebDavConfigDialog(
            initialServer = editingServer ?: WebDavServer(),
            onDismiss = { showConfigDialog = false },
            onSave = { saved ->
                WebDavManager.saveServer(saved)
                WebDavManager.setActiveServer(saved.id)
                servers = WebDavManager.getServers()
                currentServer = saved
                refreshCachedSongs()
                showConfigDialog = false
            },
        )
    }
}
