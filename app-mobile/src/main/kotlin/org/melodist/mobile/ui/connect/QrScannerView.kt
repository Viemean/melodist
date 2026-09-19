package org.melodist.mobile.ui.connect

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.melodist.core.connect.util.QrCodeUtils
import java.util.concurrent.Executors

@Composable
fun QrScannerDialog(
    onDismissRequest: () -> Unit,
    onQrDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // 从相册选图识别
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    val text = QrCodeUtils.decodeQrFromBitmap(bitmap)
                    if (!text.isNullOrBlank()) {
                        onQrDecoded(text)
                        onDismissRequest()
                    } else {
                        android.widget.Toast.makeText(context, "未能在图片中识别出有效二维码", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "解析图片失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (hasCameraPermission) {
                CameraPreviewView(
                    modifier = Modifier.fillMaxSize(),
                    onQrScanned = { result ->
                        onQrDecoded(result)
                        onDismissRequest()
                    },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text(
                            text = "需要相机权限以扫描 TV 端二维码",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { permissionLauncher.launch(android.Manifest.permission.CAMERA) }) {
                            Text("授予权限")
                        }
                    }
                }
            }

            // 扫描框取景装饰层
            ScannerOverlay(
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部关闭与操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
                }

                Text(
                    text = "对准 TV 屏幕二维码",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )

                Spacer(modifier = Modifier.size(42.dp))
            }

            // 底部提示与相册入口
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "将电视屏幕上的二维码置于框内即可自动识别",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = { pickImageLauncher.launch("image/*") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("从相册选择照片")
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewView(
    modifier: Modifier = Modifier,
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isScanned by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val cameraExecutor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val reader = MultiFormatReader()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    if (isScanned) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val text = decodeImageProxy(imageProxy, reader)
                    if (!text.isNullOrBlank() && !isScanned) {
                        isScanned = true
                        ContextCompat.getMainExecutor(ctx).execute {
                            onQrScanned(text)
                        }
                    }
                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

private fun decodeImageProxy(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)

    val width = image.width
    val height = image.height

    return try {
        val source = PlanarYUVLuminanceSource(
            data,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decodeWithState(binaryBitmap)
        result.text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}

@Composable
private fun ScannerOverlay(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_line")
    val lineOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "line_offset",
    )

    Canvas(modifier = modifier) {
        val scanBoxSize = size.minDimension * 0.7f
        val left = (size.width - scanBoxSize) / 2f
        val top = (size.height - scanBoxSize) / 2f

        // 绘制扫描框四周的半透明遮罩
        drawRect(
            color = Color.Black.copy(alpha = 0.55f),
            size = size,
        )

        // 中间透明镂空
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(scanBoxSize, scanBoxSize),
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            blendMode = BlendMode.Clear,
        )

        // 扫描框边框
        drawRoundRect(
            color = Color.White.copy(alpha = 0.6f),
            topLeft = Offset(left, top),
            size = Size(scanBoxSize, scanBoxSize),
            cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )

        // 四角加粗点缀
        val cornerLen = 24.dp.toPx()
        val cornerWidth = 4.dp.toPx()
        val accentColor = Color(0xFF00E5FF)

        // 左上角
        drawLine(accentColor, Offset(left, top), Offset(left + cornerLen, top), cornerWidth)
        drawLine(accentColor, Offset(left, top), Offset(left, top + cornerLen), cornerWidth)

        // 右上角
        drawLine(accentColor, Offset(left + scanBoxSize, top), Offset(left + scanBoxSize - cornerLen, top), cornerWidth)
        drawLine(accentColor, Offset(left + scanBoxSize, top), Offset(left + scanBoxSize, top + cornerLen), cornerWidth)

        // 左下角
        drawLine(accentColor, Offset(left, top + scanBoxSize), Offset(left + cornerLen, top + scanBoxSize), cornerWidth)
        drawLine(accentColor, Offset(left, top + scanBoxSize), Offset(left, top + scanBoxSize - cornerLen), cornerWidth)

        // 右下角
        drawLine(accentColor, Offset(left + scanBoxSize, top + scanBoxSize), Offset(left + scanBoxSize - cornerLen, top + scanBoxSize), cornerWidth)
        drawLine(accentColor, Offset(left + scanBoxSize, top + scanBoxSize), Offset(left + scanBoxSize, top + scanBoxSize - cornerLen), cornerWidth)

        // 动态扫描线
        val currentLineY = top + (scanBoxSize * lineOffset)
        drawLine(
            color = accentColor.copy(alpha = 0.85f),
            start = Offset(left + 8.dp.toPx(), currentLineY),
            end = Offset(left + scanBoxSize - 8.dp.toPx(), currentLineY),
            strokeWidth = 3.dp.toPx(),
        )
    }
}
