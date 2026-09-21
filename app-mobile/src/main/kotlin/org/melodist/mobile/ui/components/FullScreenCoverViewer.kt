package org.melodist.mobile.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.model.Song
import java.util.Locale

private const val TAG = "FullScreenCoverViewer"

/**
 * 歌曲专辑封面全屏查看器。
 * 支持双指缩放平移、点击关闭、长按呼出二次确认保存到相册。
 */
@Composable
fun FullScreenCoverViewer(
    song: Song,
    onDismissRequest: () -> Unit,
) {
    var extractedRawUrl by remember(song) { mutableStateOf<String?>(null) }

    LaunchedEffect(song) {
        withContext(Dispatchers.IO) {
            val matchingRaw =
                org.melodist.data.RawCoverHelper
                    .findMatchingRawCoverUrl(song.coverUrl)
            if (!matchingRaw.isNullOrBlank()) {
                extractedRawUrl = matchingRaw
                return@withContext
            }
            if (song.isLocal || !song.localFilePath.isNullOrBlank()) {
                val raw =
                    org.melodist.data.LocalMusicManager
                        .ensureRawCover(song)
                if (!raw.isNullOrBlank()) {
                    extractedRawUrl = raw
                }
            } else if (song.songMid.startsWith("webdav_")) {
                val raw =
                    org.melodist.data.WebDavManager
                        .ensureRawCover(song)
                if (!raw.isNullOrBlank()) {
                    extractedRawUrl = raw
                }
            }
        }
    }

    val candidates =
        remember(song, extractedRawUrl) {
            val list = mutableListOf<String>()
            if (!extractedRawUrl.isNullOrBlank()) {
                list.add(extractedRawUrl!!)
            }
            song.rawCoverCandidates.forEach { u ->
                if (!list.contains(u)) list.add(u)
            }
            if (song.coverUrl.isNotBlank() && !list.contains(song.coverUrl)) {
                list.add(song.coverUrl)
            }
            list
        }

    FullScreenImageViewer(
        imageUrl = candidates.firstOrNull().orEmpty(),
        candidates = candidates,
        title = "保存封面",
        subTitle = "${song.name} - ${song.singer}",
        filePrefix = "${song.name}_${song.singer}",
        saveTipText = "是否保存当前专辑封面图片到系统相册？",
        onDismissRequest = onDismissRequest,
    )
}

/**
 * 全屏大图查看器。
 * 支持双指缩放平移、点击关闭、显示分辨率与文件大小、长按保存到相册。
 */
@Composable
fun FullScreenImageViewer(
    imageUrl: String,
    candidates: List<String> = remember(imageUrl) { if (imageUrl.isBlank()) emptyList() else listOf(imageUrl) },
    title: String = "保存图片",
    subTitle: String = "",
    filePrefix: String = "image",
    saveTipText: String = "是否保存当前图片到系统相册？",
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    var showSaveConfirmDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    @Suppress("DEPRECATION")
    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 4f)
            if (scale > 1f) {
                offset += panChange
            } else {
                offset = Offset.Zero
            }
        }

    val actualCandidates =
        remember(imageUrl, candidates) {
            if (candidates.isNotEmpty()) candidates else listOf(imageUrl)
        }
    var candidateIndex by remember(actualCandidates) { mutableIntStateOf(0) }
    val currentUrl = actualCandidates.getOrNull(candidateIndex) ?: imageUrl

    var imageWidth by remember(currentUrl) { mutableIntStateOf(0) }
    var imageHeight by remember(currentUrl) { mutableIntStateOf(0) }
    var fileSizeBytes by remember(currentUrl) { mutableStateOf<Long?>(null) }

    LaunchedEffect(currentUrl) {
        fileSizeBytes =
            withContext(Dispatchers.IO) {
                if (currentUrl.isBlank()) return@withContext null
                if (currentUrl.startsWith("/") || currentUrl.startsWith("file://")) {
                    try {
                        val path = currentUrl.removePrefix("file://")
                        val file = java.io.File(path)
                        if (file.exists() && file.isFile) return@withContext file.length()
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Failed to read local cover file size: $currentUrl", e)
                    }
                }
                getDiskCachedCoverSize(context, currentUrl)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            actualCandidates.forEach { url ->
                org.melodist.playback.CoverMemoryManager
                    .evictCoverFromMemory(context, url)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onDismissRequest() },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            // 封面图片主图（原始直角图片，无圆角与比例裁剪）
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }.transformable(state = transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onDismissRequest() },
                                onDoubleTap = {
                                    if (scale > 1.2f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showSaveConfirmDialog = true
                                },
                            )
                        },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(context)
                            .data(currentUrl)
                            .size(Size.ORIGINAL)
                            .precision(Precision.EXACT)
                            .crossfade(true)
                            .build(),
                    contentDescription = subTitle.ifBlank { title },
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    onSuccess = { state ->
                        val img = state.result.image
                        imageWidth = img.width
                        imageHeight = img.height
                        if (fileSizeBytes == null || fileSizeBytes == 0L) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val cachedSize = getDiskCachedCoverSize(context, currentUrl)
                                if (cachedSize != null && cachedSize > 0) {
                                    fileSizeBytes = cachedSize
                                }
                            }
                        }
                    },
                    onError = {
                        if (candidateIndex + 1 < actualCandidates.size) {
                            candidateIndex++
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 顶部操作栏
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter),
            ) {
                IconButton(
                    onClick = onDismissRequest,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                    )
                }
            }

            // 底部规格信息与长按保存提示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 28.dp),
            ) {
                if (imageWidth > 0 && imageHeight > 0) {
                    val sizeStr = fileSizeBytes?.let { formatFileSize(it) }.orEmpty()
                    val infoText =
                        if (sizeStr.isNotBlank()) {
                            "$imageWidth × $imageHeight  •  $sizeStr"
                        } else {
                            "$imageWidth × $imageHeight"
                        }
                    Text(
                        text = infoText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "长按图片可保存到相册",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }

            // 保存中指示器
            AnimatedVisibility(
                visible = isSaving,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(88.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }

    // 二次确认保存对话框
    if (showSaveConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isSaving) {
                    showSaveConfirmDialog = false
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    Text(
                        text = saveTipText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (subTitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = subTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveConfirmDialog = false
                        isSaving = true
                        coroutineScope.launch {
                            val success =
                                withContext(Dispatchers.IO) {
                                    saveImageToGallery(context, currentUrl, filePrefix)
                                }
                            isSaving = false
                            if (success) {
                                Toast.makeText(context, "图片已保存至系统相册", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "保存图片失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSaveConfirmDialog = false },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 将图片保存到系统相册。
 */
private suspend fun saveImageToGallery(
    context: Context,
    imageUrl: String,
    filePrefix: String = "image",
): Boolean {
    return try {
        val loader = SingletonImageLoader.get(context)
        val request =
            ImageRequest
                .Builder(context)
                .data(imageUrl)
                .size(Size.ORIGINAL)
                .precision(Precision.EXACT)
                .build()
        val result = loader.execute(request)
        if (result !is SuccessResult) {
            return false
        }

        val rawName =
            filePrefix
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .trim()
        val fileName = "${rawName.ifBlank { "image" }}_${System.currentTimeMillis()}.jpg"

        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Melodist")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

        val written =
            resolver.openOutputStream(uri)?.use { stream ->
                val snapshot = loader.diskCache?.openSnapshot(imageUrl)
                if (snapshot != null) {
                    snapshot.use { snap ->
                        java.io.FileInputStream(snap.data.toFile()).use { input ->
                            input.copyTo(stream)
                        }
                    }
                    true
                } else {
                    val bitmap = result.image.toBitmap()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
            } ?: false

        if (!written) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    } catch (e: Throwable) {
        android.util.Log.e(TAG, "Failed to save image to gallery: $filePrefix", e)
        false
    }
}

private fun getDiskCachedCoverSize(
    context: Context,
    coverUrl: String,
): Long? {
    if (coverUrl.isBlank()) return null
    return try {
        val diskCache = SingletonImageLoader.get(context).diskCache
        val snapshot = diskCache?.openSnapshot(coverUrl)
        snapshot?.use { snap ->
            val file = snap.data.toFile()
            if (file.exists() && file.isFile) {
                file.length()
            } else {
                null
            }
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "Failed to read disk cache file size for cover: $coverUrl", e)
        null
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    return when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
