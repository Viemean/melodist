package org.melodist.mobile.ui.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.melodist.data.LocalFileItem
import org.melodist.data.LocalMusicManager
import org.melodist.mobile.ui.components.CommonSongList
import org.melodist.mobile.ui.components.SongListDeleteType
import org.melodist.mobile.ui.storage.StorageDirectoryListView
import org.melodist.mobile.ui.storage.StorageItemModel
import org.melodist.mobile.ui.storage.StoragePathBreadcrumbs
import org.melodist.mobile.ui.storage.StorageScanProgressCard
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import java.io.File

enum class LocalMusicViewMode {
    Library, // 已扫描本地音乐库
    Directory, // 本地目录树浏览
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMusicMobileScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 检查与申请权限
    val permissionToRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permissionToRequest) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasPermission = granted
            if (!granted) {
                Toast.makeText(context, "未授予存储权限，无法读取本地音频", Toast.LENGTH_SHORT).show()
            }
        }

    val scannedSongs by LocalMusicManager.scannedSongsFlow.collectAsState()
    var viewMode by remember {
        mutableStateOf(if (scannedSongs.isNotEmpty()) LocalMusicViewMode.Library else LocalMusicViewMode.Directory)
    }

    val storageDrives =
        remember(hasPermission) {
            if (hasPermission) LocalMusicManager.detectStorageDrives(context) else emptyList()
        }
    var currentDrive by remember(storageDrives) {
        mutableStateOf(storageDrives.firstOrNull())
    }

    var currentPath by remember(currentDrive) {
        val last = LocalMusicManager.getLastDirectory()
        val defaultPath = currentDrive?.path ?: "/storage/emulated/0"
        mutableStateOf(if (File(last).exists()) last else defaultPath)
    }
    var pathHistory by remember(currentDrive) {
        mutableStateOf<List<String>>(listOf(currentPath))
    }

    var directoryItems by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    var isLoadingDirectory by remember { mutableStateOf(false) }

    var isScanning by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    var showClearConfirmDialog by remember { mutableStateOf(false) }

    fun refreshScannedSongs() {
        LocalMusicManager.notifyScannedSongsChanged()
    }

    fun loadDirectory(targetPath: String) {
        isLoadingDirectory = true
        scope.launch {
            try {
                directoryItems = LocalMusicManager.listDirectory(targetPath)
                currentPath = targetPath
                LocalMusicManager.setLastDirectory(targetPath)
            } catch (e: Exception) {
                Toast.makeText(context, "打开目录失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingDirectory = false
            }
        }
    }

    fun startScan(targetFolder: String) {
        if (isScanning) return
        isScanning = true
        scanStatus = "正在检索目录中的音频文件..."
        scanJob =
            scope.launch {
                try {
                    LocalMusicManager.scanDirectory(targetFolder) { title, cur, total ->
                        scanStatus = if (total > 0) "[$cur/$total] $title" else "[$cur] $title"
                    }
                    refreshScannedSongs()
                    viewMode = LocalMusicViewMode.Library
                    Toast.makeText(context, "本地音乐扫描完成", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isScanning = false
                    scanStatus = null
                }
            }
    }

    fun startSystemScan() {
        if (isScanning) return
        isScanning = true
        scanStatus = "正在检索系统媒体库中的音频..."
        scanJob =
            scope.launch {
                try {
                    val songs =
                        LocalMusicManager.scanSystemMediaStore(context) { title, cur, total ->
                            scanStatus = if (total > 0) "[$cur/$total] $title" else "[$cur] $title"
                        }
                    refreshScannedSongs()
                    viewMode = LocalMusicViewMode.Library
                    Toast.makeText(context, "已导入 ${songs.size} 首歌曲", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "系统扫描失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isScanning = false
                    scanStatus = null
                }
            }
    }

    LaunchedEffect(hasPermission, viewMode) {
        if (hasPermission) {
            if (viewMode == LocalMusicViewMode.Directory) {
                loadDirectory(currentPath)
            } else if (viewMode == LocalMusicViewMode.Library) {
                LocalMusicManager.healMissingCovers()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!hasPermission) {
            // 未授权提示卡片
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
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "需要存储访问权限",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "请授予读取音频文件权限，以扫描并播放本地音乐",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(permissionToRequest) }) {
                        Text("授权访问本地音乐")
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部控制栏：模式切换药丸
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = viewMode == LocalMusicViewMode.Library,
                            onClick = {
                                viewMode = LocalMusicViewMode.Library
                                refreshScannedSongs()
                            },
                            label = { Text("音乐库 (${scannedSongs.size})") },
                            leadingIcon = {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                        FilterChip(
                            selected = viewMode == LocalMusicViewMode.Directory,
                            onClick = {
                                viewMode = LocalMusicViewMode.Directory
                                loadDirectory(currentPath)
                            },
                            label = { Text("文件目录") },
                            leadingIcon = {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                    }

                    // 扫描系统音乐选项
                    FilledTonalButton(
                        onClick = { startSystemScan() },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("扫描", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // 扫描进度卡片
                if (isScanning && scanStatus != null) {
                    StorageScanProgressCard(
                        scanStatus = scanStatus!!,
                        onCancel = {
                            scanJob?.cancel()
                            isScanning = false
                            scanStatus = null
                            refreshScannedSongs()
                        },
                    )
                }

                // 主内容区
                when (viewMode) {
                    LocalMusicViewMode.Library -> {
                        if (scannedSongs.isEmpty()) {
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
                                        text = "暂无已扫描的本地歌曲",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { startSystemScan() }) {
                                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("扫描系统音乐")
                                        }
                                        FilledTonalButton(onClick = {
                                            viewMode = LocalMusicViewMode.Directory
                                            loadDirectory(currentDrive?.path ?: "/storage/emulated/0")
                                        }) {
                                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("浏览文件夹")
                                        }
                                    }
                                }
                            }
                        } else {
                            CommonSongList(
                                songs = scannedSongs,
                                showLocalBadge = false,
                                deleteType = SongListDeleteType.LocalFile,
                                enableDownload = false,
                                contentPadding =
                                    PaddingValues(
                                        top = 2.dp,
                                        bottom = contentPadding.calculateBottomPadding() + 16.dp,
                                    ),
                                headerItems = {
                                    item(key = "local_library_header") {
                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "共 ${scannedSongs.size} 首歌曲",
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

                    LocalMusicViewMode.Directory -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            StoragePathBreadcrumbs(
                                currentPath = currentPath,
                                onNavigateToSegment = { segmentPath ->
                                    pathHistory = pathHistory.takeWhile { it != segmentPath } + segmentPath
                                    loadDirectory(segmentPath)
                                },
                                onNavigateUp = {
                                    if (pathHistory.size > 1) {
                                        val nextHist = pathHistory.dropLast(1)
                                        pathHistory = nextHist
                                        loadDirectory(nextHist.last())
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
                                        val detail =
                                            if (item.isDirectory) {
                                                "文件夹"
                                            } else {
                                                "%.1f MB".format(item.size / (1024f * 1024f))
                                            }
                                        StorageItemModel(
                                            id = item.path,
                                            name = item.name,
                                            isDirectory = item.isDirectory,
                                            detailText = detail,
                                            raw = item,
                                        )
                                    }

                                StorageDirectoryListView(
                                    items = storageItems,
                                    onItemClick = { clicked ->
                                        val item = clicked.raw as LocalFileItem
                                        if (item.isDirectory) {
                                            pathHistory = pathHistory + item.path
                                            loadDirectory(item.path)
                                        } else {
                                            val (inferredTitle, inferredArtist) = LocalMusicManager.inferTitleArtist(item.name)
                                            val song =
                                                Song(
                                                    songMid = "local_${item.path.hashCode()}",
                                                    name = inferredTitle,
                                                    singer = inferredArtist,
                                                    album = "本地音频",
                                                    localFilePath = item.path,
                                                )
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

    // 存储盘选择底部弹窗
    // 清空本地音乐库缓存确认
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空本地音乐库") },
            text = { Text("确定要清除本地音乐库的索引与缓存吗？设备中的实际音频文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    LocalMusicManager.clearLibrary()
                    refreshScannedSongs()
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
}
