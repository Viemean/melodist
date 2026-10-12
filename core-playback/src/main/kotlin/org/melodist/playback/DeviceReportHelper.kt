package org.melodist.playback

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.melodist.api.DeviceReportInfo
import java.security.MessageDigest

/**
 * 提取并缓存用于听歌时长流水上报的宿主设备物理指纹。
 */
internal object DeviceReportHelper {
    @Volatile
    private var cachedInfo: DeviceReportInfo? = null

    /**
     * 获取设备指纹信息。
     *
     * @param context 应用程序上下文
     * @return 包含型号、系统版本与设备标识的数据对象
     */
    fun getDeviceInfo(context: Context?): DeviceReportInfo {
        cachedInfo?.let { return it }

        val androidId =
            if (context != null) {
                try {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }?.takeIf { it.isNotBlank() } ?: "0123456789abcdef"

        val openUdid = computeSha1(androidId)
        val model = Build.MODEL.takeIf { !it.isNullOrBlank() } ?: "Melodist"
        val osVer = Build.VERSION.RELEASE.takeIf { !it.isNullOrBlank() } ?: "14"
        val sdkInt = Build.VERSION.SDK_INT

        val info =
            DeviceReportInfo(
                model = model,
                osVersion = osVer,
                sdkInt = sdkInt,
                androidId = androidId,
                openUdid = openUdid,
            )
        cachedInfo = info
        return info
    }

    private fun computeSha1(input: String): String =
        try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        }
}
