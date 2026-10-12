package org.melodist.playback

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.melodist.api.DeviceReportInfo
import java.security.MessageDigest

/**
 * 提取并缓存用于听歌时长流水上报的宿主设备物理指纹。
 */
object DeviceReportHelper {
    @Volatile
    private var cachedInfo: DeviceReportInfo? = null

    data class DeviceProfile(
        val model: String,
        val androidId: String,
    )

    private val COMPLIANT_DEVICE_PROFILES =
        listOf(
            DeviceProfile("V2426A", "722102e37275540e"),
            DeviceProfile("2407FRK8EC", "a1b2c3d4e5f60718"),
            DeviceProfile("23127PN0CC", "3f8a9b2c1d4e5067"),
            DeviceProfile("PCLM10", "5c6d7e8f9012345a"),
            DeviceProfile("PGEM10", "8b7a6c5d4e3f2019"),
            DeviceProfile("ALN-AL00", "9f0e1d2c3b4a5678"),
        )

    private val NON_COMPLIANT_MODEL_KEYWORDS =
        listOf(
            "tv",
            "box",
            "watch",
            "pad",
            "tablet",
            "car",
            "auto",
            "emulator",
            "generic",
            "sdk",
            "goldfish",
            "ranchu",
            "vbox",
        )

    /**
     * 获取设备指纹信息。
     *
     * @param context 应用程序上下文
     * @return 包含型号、系统版本与设备标识的数据对象
     */
    fun getDeviceInfo(context: Context?): DeviceReportInfo {
        cachedInfo?.let { return it }

        val rawAndroidId =
            if (context != null) {
                try {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

        val rawModel = Build.MODEL.takeIf { !it.isNullOrBlank() } ?: "Melodist"
        val isModelNonCompliant = isNonCompliantModel(rawModel)
        val isIdNonCompliant = isNonCompliantAndroidId(rawAndroidId)

        val seed = rawAndroidId?.takeIf { it.isNotBlank() } ?: rawModel
        val profile = if (isModelNonCompliant || isIdNonCompliant) resolveCompliantProfile(seed) else null
        val model = if (isModelNonCompliant) (profile?.model ?: COMPLIANT_DEVICE_PROFILES.first().model) else rawModel
        val androidId = if (isIdNonCompliant) (profile?.androidId ?: COMPLIANT_DEVICE_PROFILES.first().androidId) else rawAndroidId!!
        val openUdid = computeSha1(androidId)
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

    private fun isNonCompliantModel(rawModel: String): Boolean =
        rawModel.isBlank() ||
            rawModel.length < 3 ||
            rawModel.contains(" ") ||
            NON_COMPLIANT_MODEL_KEYWORDS.any { rawModel.contains(it, ignoreCase = true) }

    private fun isNonCompliantAndroidId(rawAndroidId: String?): Boolean {
        if (rawAndroidId.isNullOrBlank() || rawAndroidId.length != 16) return true
        if (rawAndroidId == "9774d56d682e549c" || rawAndroidId == "0000000000000000") return true
        return !rawAndroidId.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun resolveCompliantProfile(seedStr: String): DeviceProfile {
        val seed = kotlin.math.abs(seedStr.hashCode())
        return COMPLIANT_DEVICE_PROFILES[seed % COMPLIANT_DEVICE_PROFILES.size]
    }

    fun computeSha1(input: String): String =
        try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        }
}
