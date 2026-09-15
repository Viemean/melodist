package org.melodist.mobile.ui.acr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 无 UI 的识曲控制器。挂载到 Composition 后，检查麦克风权限并自动触发识别。
 * 识别结果通过 ViewModel 的 uiState Flow 传递，调用方监听状态变化。
 */
@Composable
fun AcrRecognitionController(
    viewModel: MobileAcrViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.uiState.value is MobileAcrUiState.Listening) {
                viewModel.reset()
            }
        }
    }

    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                viewModel.startRecognition()
            } else {
                viewModel.setPermissionRequired()
                onDismiss()
            }
        }

    LaunchedEffect(Unit) {
        val hasPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            viewModel.startRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
