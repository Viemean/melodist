package org.melodist.tv.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.melodist.data.LocalFileItem
import org.melodist.data.LocalMusicManager
import org.melodist.data.StorageDrive
import org.melodist.model.AudioQualityTier
import org.melodist.model.Song
import org.melodist.playback.PlaybackManager
import org.melodist.tv.ui.components.MelodistElevatedCover
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.rememberTvWindowMetrics
import java.io.File

enum class LocalMusicViewMode {
    Directory, // 简单文件管理器 (选择本地与U盘目录)
    Library, // 已扫描本地音乐库
}

@Composable
fun LocalMusicTvScreen(
    surfaceColor: Color,
    onNavigateToPlayer: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val metrics = rememberTvWindowMetrics()
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf(LocalMusicManager.getLastDirectory()) }
    var pathHistory by remember { mutableStateOf<List<String>>(listOf(currentPath)) }

    var scannedSongs by remember { mutableStateOf(LocalMusicManager.getScannedSongs()) }
    var viewMode by remember {
        mutableStateOf(if (scannedSongs.isNotEmpty()) LocalMusicViewMode.Library else LocalMusicViewMode.Directory)
    }

    var items by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var showDriveSelectDialog by remember { mutableStateOf(false) }

    fun loadDirectory(targetPath: String) {
        isLoading = true
        scope.launch {
            try {
                items = LocalMusicManager.listDirectory(targetPath)
                currentPath = targetPath
                LocalMusicManager.setLastDirectory(targetPath)
            } catch (e: Exception) {
                Toast.makeText(context, "打开目录失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentPath) {
        loadDirectory(currentPath)
    }

    fun startScan(targetFolder: String) {
        if (isScanning) return
        isScanning = true
        scanStatus = "准备扫描目录音频..."
        scope.launch {
            try {
                val songs =
                    LocalMusicManager.scanDirectory(targetFolder) { title, cur, total ->
                        scanStatus = if (total > 0) "正在解析: $title ($cur/$total)" else title
                    }
                scannedSongs = songs
                scanStatus = "扫描完成，共收录 ${songs.size} 首本地曲目"
                Toast.makeText(context, "扫描完成，收录 ${songs.size} 首歌", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                scanStatus = "扫描出错: ${e.message}"
            } finally {
                isScanning = false
            }
        }
    }

    // 遥控器返回键处理
    BackHandler {
        if (viewMode == LocalMusicViewMode.Directory && pathHistory.size > 1) {
            val nextHistory = pathHistory.dropLast(1)
            pathHistory = nextHistory
            val prev = nextHistory.last()
            currentPath = prev
            loadDirectory(prev)
        } else {
            onBack()
        }
    }

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
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. 顶部控制栏与导航操作
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LocalMusicNavButton(
                        icon = Icons.Filled.ArrowBack,
                        text = "返回",
                        onClick = {
                            if (viewMode == LocalMusicViewMode.Directory && pathHistory.size > 1) {
                                val nextHistory = pathHistory.dropLast(1)
                                pathHistory = nextHistory
                                val prev = nextHistory.last()
                                currentPath = prev
                                loadDirectory(prev)
                            } else {
                                onBack()
                            }
                        },
                    )

                    // 视图切换（独立按钮，不再共用背景）
                    LocalMusicNavButton(
                        icon = Icons.Filled.LibraryMusic,
                        text = "本地曲库 (${scannedSongs.size})",
                        isAccent = (viewMode == LocalMusicViewMode.Library),
                        onClick = { viewMode = LocalMusicViewMode.Library },
                    )
                    LocalMusicNavButton(
                        icon = Icons.Filled.Folder,
                        text = "文件管理器",
                        isAccent = (viewMode == LocalMusicViewMode.Directory),
                        onClick = { viewMode = LocalMusicViewMode.Directory },
                    )

                    // 快速选择来源
                    LocalMusicNavButton(
                        icon = Icons.Filled.Storage,
                        text = "选项来源",
                        onClick = { showDriveSelectDialog = true },
                    )

                    // 扫描此目录入库
                    if (viewMode == LocalMusicViewMode.Directory) {
                        LocalMusicNavButton(
                            icon = Icons.Filled.Refresh,
                            text = if (isScanning) "扫描中..." else "扫描此目录入库",
                            isAccent = isScanning,
                            onClick = { startScan(currentPath) },
                        )
                    }
                }

                // 右侧当前路径精简提示（纯白文字）
                if (viewMode == LocalMusicViewMode.Directory) {
                    Text(
                        text = currentPath,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                }
            }

            // 扫描状态横幅
            if (isScanning || scanStatus != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MelodistColors.AccentGreen,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = scanStatus.orEmpty(),
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 主内容展示区：本地曲库 vs 文件目录
            if (viewMode == LocalMusicViewMode.Library) {
                LocalMusicLibraryView(
                    songs = scannedSongs,
                    onSongClick = { song ->
                        val idx = scannedSongs.indexOfFirst { it.songMid == song.songMid }
                        PlaybackManager.setPlaylist(scannedSongs, if (idx >= 0) idx else 0)
                        onNavigateToPlayer()
                    },
                    onPlayAll = {
                        if (scannedSongs.isNotEmpty()) {
                            PlaybackManager.setPlaylist(scannedSongs, 0)
                            onNavigateToPlayer()
                        }
                    },
                    onClearLibrary = {
                        LocalMusicManager.clearLibrary()
                        scannedSongs = emptyList()
                        Toast.makeText(context, "曲库已清空", Toast.LENGTH_SHORT).show()
                    },
                    onSwitchToDirectory = {
                        viewMode = LocalMusicViewMode.Directory
                    },
                )
            } else {
                // 目录文件列表
                val listState = rememberLazyListState()
                val currentFile = File(currentPath)
                val parentFile = currentFile.parentFile
                val canGoUp = parentFile != null && parentFile.exists() && parentFile.canRead()

                val hasStoragePermission =
                    remember {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Environment.isExternalStorageManager()
                        } else {
                            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                    }

                if (!hasStoragePermission) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "需要所有文件访问权限以读取本地及 U 盘音乐",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            LocalMusicNavButton(
                                icon = Icons.Filled.Security,
                                text = "前往系统设置授权",
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        try {
                                            val intent =
                                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            try {
                                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                context.startActivity(intent)
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "正在读取目录...", color = Color.White, fontSize = 16.sp)
                    }
                } else if (items.isEmpty() && !canGoUp) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "当前目录为空或不可读", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            LocalMusicNavButton(
                                icon = Icons.Filled.Storage,
                                text = "切换其他存储位置 / U 盘",
                                onClick = { showDriveSelectDialog = true },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 顶部第一项：返回上一级
                        if (canGoUp) {
                            item(key = "go_up") {
                                LocalMusicParentFolderRow(
                                    parentName = parentFile?.name.orEmpty().ifBlank { "上一级目录" },
                                    onClick = {
                                        val pPath = parentFile!!.absolutePath
                                        pathHistory = if (pathHistory.size > 1) pathHistory.dropLast(1) else listOf(pPath)
                                        currentPath = pPath
                                        loadDirectory(pPath)
                                    },
                                )
                            }
                        }

                        if (items.isEmpty()) {
                            item(key = "empty_dir_notice") {
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "此文件夹内未发现音频文件 (支持 mp3/flac/wav/m4a/ogg/ape 等)",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                        }

                        itemsIndexed(items, key = { _, itm -> itm.path }) { _, item ->
                            LocalMusicItemRow(
                                item = item,
                                onClick = {
                                    if (item.isDirectory) {
                                        pathHistory = pathHistory + item.path
                                        currentPath = item.path
                                        loadDirectory(item.path)
                                    } else if (item.isAudio) {
                                        val (title, artist) = LocalMusicManager.inferTitleArtist(item.name)
                                        val song =
                                            Song(
                                                songId = item.path.hashCode().toLong(),
                                                songMid = "local_${item.path.hashCode()}",
                                                name = title,
                                                singer = artist,
                                                album = currentFile.name,
                                                currentTier = AudioQualityTier.SQ,
                                                localFilePath = item.path,
                                            )
                                        PlaybackManager.setPlaylist(listOf(song), 0)
                                        onNavigateToPlayer()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 3. 存储介质选择弹窗 (内部存储 / U 盘)
        if (showDriveSelectDialog) {
            val drives = remember { LocalMusicManager.detectStorageDrives(context) }
            StorageDriveSelectDialog(
                drives = drives,
                currentPath = currentPath,
                onSelect = { drive ->
                    showDriveSelectDialog = false
                    pathHistory = listOf(drive.path)
                    currentPath = drive.path
                    viewMode = LocalMusicViewMode.Directory
                    loadDirectory(drive.path)
                },
                onDismiss = { showDriveSelectDialog = false },
            )
        }
    }
}

/**
 * 本地已扫描曲库视图
 */
@Composable
private fun LocalMusicLibraryView(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit,
    onClearLibrary: () -> Unit,
    onSwitchToDirectory: () -> Unit,
) {
    if (songs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "尚未扫描本地曲库",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "请点击上方【文件管理器】，进入 U 盘或本地目录后点击【扫描此目录入库】",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(20.dp))
                LocalMusicNavButton(
                    icon = Icons.Filled.Folder,
                    text = "前往文件管理器",
                    isAccent = true,
                    onClick = onSwitchToDirectory,
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 曲库操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalMusicNavButton(
                icon = Icons.Filled.PlayArrow,
                text = "播放全部",
                isAccent = true,
                onClick = onPlayAll,
            )
            LocalMusicNavButton(
                icon = Icons.Filled.DeleteOutline,
                text = "清空曲库",
                onClick = onClearLibrary,
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 表头
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "#", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
            Text(text = "歌曲", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.42f))
            Text(text = "歌手", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.28f))
            Text(text = "专辑", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.30f))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(songs, key = { _, s -> s.songMid }) { index, song ->
                LocalMusicLibrarySongRow(
                    index = index + 1,
                    song = song,
                    onClick = { onSongClick(song) },
                )
            }
        }
    }
}

@Composable
private fun LocalMusicLibrarySongRow(
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
                    shape = RoundedCornerShape(8.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(index),
            color = if (isFocused) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(44.dp),
        )

        // 封面与标题
        Row(
            modifier = Modifier.weight(0.42f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MelodistElevatedCover(
                coverUrl = song.coverUrl,
                modifier = Modifier.size(38.dp),
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = song.name,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = song.singer,
            color = if (isFocused) Color.Black else Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.28f),
        )

        Text(
            text = song.album,
            color = if (isFocused) Color.Black else Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.30f),
        )
    }
}

/**
 * 目录浏览器：返回上一级
 */
@Composable
private fun LocalMusicParentFolderRow(
    parentName: String,
    onClick: () -> Unit,
) {
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
                            color = if (isFocused) MelodistColors.FocusTeal else Color.White.copy(alpha = 0.10f),
                        ),
                    shape = RoundedCornerShape(8.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.White.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.DriveFileMove,
            contentDescription = null,
            tint = if (isFocused) Color.Black else MelodistColors.FocusTeal,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = "返回上一级: $parentName",
            color = if (isFocused) Color.Black else Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 目录浏览器：项目行 (文件夹 vs 音频文件)
 */
@Composable
private fun LocalMusicItemRow(
    item: LocalFileItem,
    onClick: () -> Unit,
) {
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
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                        ),
                    shape = RoundedCornerShape(8.dp),
                ).background(
                    color = if (isFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = if (item.isDirectory) Icons.Filled.Folder else Icons.Filled.MusicNote
            val iconTint =
                when {
                    isFocused -> Color.Black
                    item.isDirectory -> Color(0xFFFFCA28)
                    else -> MelodistColors.AccentGreen
                }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = item.name,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!item.isDirectory) {
            val sizeMb = "%.1f MB".format(item.size / (1024f * 1024f))
            Text(
                text = sizeMb,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = 13.sp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 顶部导航按钮
 */
@Composable
private fun LocalMusicNavButton(
    icon: ImageVector,
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
            Color.White.copy(alpha = 0.08f)
        }
    val baseBorderColor =
        if (isAccent) {
            MelodistColors.AccentGreen.copy(alpha = 0.50f)
        } else {
            Color.White.copy(alpha = 0.14f)
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
                .height(38.dp)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .focusable(interactionSource = interactionSource)
                .border(
                    border =
                        BorderStroke(
                            width = if (isFocused) 2.dp else 1.dp,
                            color = if (isFocused) MelodistColors.FocusTeal else baseBorderColor,
                        ),
                    shape = MelodistShapes.ButtonCorner,
                ).background(
                    color = if (isFocused) Color.White else baseBgColor,
                    shape = MelodistShapes.ButtonCorner,
                ).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (isFocused) Color.Black else baseContentColor,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            color = if (isFocused) Color.Black else baseContentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 存储介质与驱动器选择弹窗
 */
@Composable
private fun StorageDriveSelectDialog(
    drives: List<StorageDrive>,
    currentPath: String,
    onSelect: (StorageDrive) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(480.dp)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(16.dp))
                    .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "选项来源",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier =
                            Modifier
                                .size(20.dp)
                                .clickable { onDismiss() },
                    )
                }

                if (drives.isEmpty()) {
                    Text(
                        text = "未检测到可读的存储目录",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(drives, key = { _, d -> d.path }) { _, drive ->
                            val interactionSource = remember { MutableInteractionSource() }
                            val isFocused by interactionSource.collectIsFocusedAsState()
                            val isCurrent = currentPath.startsWith(drive.path)

                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .clickable(interactionSource = interactionSource, indication = null) {
                                            onSelect(drive)
                                        }.focusable(interactionSource = interactionSource)
                                        .border(
                                            border =
                                                BorderStroke(
                                                    width = if (isFocused) 2.5.dp else 1.dp,
                                                    color =
                                                        if (isFocused) {
                                                            MelodistColors.FocusTeal
                                                        } else if (isCurrent) {
                                                            MelodistColors.FocusTeal.copy(alpha = 0.4f)
                                                        } else {
                                                            Color.White.copy(alpha = 0.10f)
                                                        },
                                                ),
                                            shape = RoundedCornerShape(8.dp),
                                        ).background(
                                            color =
                                                if (isFocused) {
                                                    Color.White
                                                } else if (isCurrent) {
                                                    MelodistColors.FocusTeal.copy(alpha = 0.15f)
                                                } else {
                                                    Color.White.copy(alpha = 0.05f)
                                                },
                                            shape = RoundedCornerShape(8.dp),
                                        ).padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val icon = if (drive.isRemovable) Icons.Filled.Usb else Icons.Filled.Storage
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint =
                                            if (isFocused) {
                                                Color.Black
                                            } else if (drive.isRemovable) {
                                                MelodistColors.AccentGreen
                                            } else {
                                                Color.White.copy(alpha = 0.7f)
                                            },
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = drive.name,
                                            color = if (isFocused) Color.Black else Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = drive.path,
                                            color = if (isFocused) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.4f),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "当前所在",
                                        tint = if (isFocused) Color.Black else MelodistColors.FocusTeal,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
