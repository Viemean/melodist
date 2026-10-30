package org.melodist.mobile.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat

object LocalNetworkPermissionHelper {
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    private var hasShownToastInLifecycle = false

    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 37) {
            ContextCompat.checkSelfPermission(
                context,
                PERMISSION_ACCESS_LOCAL_NETWORK,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun showToastOnceIfDenied(context: Context) {
        if (Build.VERSION.SDK_INT >= 37 && !hasShownToastInLifecycle && !hasPermission(context)) {
            hasShownToastInLifecycle = true
            Toast.makeText(context, "未授予局域网权限，可能无法连接本地设备", Toast.LENGTH_SHORT).show()
        }
    }
}
