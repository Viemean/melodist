package org.melodist.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/**
 * 听歌时长上报所依赖的物理设备指纹数据。
 *
 * @property model 物理设备型号
 * @property osVersion 操作系统主版本号
 * @property sdkInt 系统 SDK API Level
 * @property androidId 系统唯一设备标识
 * @property openUdid 基于系统标识派生的设备指纹
 */
data class DeviceReportInfo(
    val model: String,
    val osVersion: String,
    val sdkInt: Int,
    val androidId: String,
    val openUdid: String,
)

internal const val PLAY_STREAM_REPORT_URL = "https://stat6.y.qq.com/android/fcgi-bin/imusic_tj"
private const val TIMEKEY_SALT = "gk2\$Lh-&l4#!4iow"
private val SID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.systemDefault())

/**
 * 上报单曲播放时长流水。
 *
 * @param songId 歌曲数字标识
 * @param durationSec 单曲总时长（秒）
 * @param playTimeSec 本次有效播放时长（秒）
 * @param deviceInfo 设备指纹信息
 * @param optime 上报基准时间戳（秒），默认当前系统时间
 * @return 是否上报成功
 */
suspend fun MusicApiService.reportPlayStream(
    songId: Long,
    durationSec: Long,
    playTimeSec: Long,
    deviceInfo: DeviceReportInfo,
    optime: Long = System.currentTimeMillis() / 1000,
): Boolean =
    withContext(Dispatchers.IO) {
        if (!UserSession.isLoggedIn) return@withContext false
        if (songId <= 0L || playTimeSec < 5L) return@withContext false

        val effectivePlaySec = if (durationSec > 0L) minOf(playTimeSec, durationSec) else playTimeSec
        if (effectivePlaySec < 5L) return@withContext false

        val effectiveDuration = if (durationSec > 0L) maxOf(durationSec, effectivePlaySec) else effectivePlaySec
        val uin = getEffectiveUin()
        val authst = getEffectiveAuthst()
        if (uin.isBlank()) return@withContext false

        val xml =
            buildPlayStreamXml(
                songId = songId,
                durationSec = effectiveDuration,
                playTimeSec = effectivePlaySec,
                deviceInfo = deviceInfo,
                uin = uin,
                authst = authst,
                optime = optime,
            )

        try {
            val compressedBytes = compressGzip(xml.toByteArray(Charsets.UTF_8))
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val requestBody = compressedBytes.toRequestBody(mediaType)

            val request =
                Request
                    .Builder()
                    .url(PLAY_STREAM_REPORT_URL)
                    .post(requestBody)
                    .header("User-Agent", "QQMusic 12030508(android ${deviceInfo.osVersion})")
                    .header("Host", "stat6.y.qq.com")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Content-Encoding", "gzip")
                    .header("Connection", "Keep-Alive")
                    .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ApiLogger.w("MusicApiPlayStream", "reportPlayStream failed: songId=$songId, playTime=$playTimeSec", e)
            false
        }
    }

internal fun buildPlayStreamXml(
    songId: Long,
    durationSec: Long,
    playTimeSec: Long,
    deviceInfo: DeviceReportInfo,
    uin: String,
    authst: String,
    optime: Long = System.currentTimeMillis() / 1000,
): String {
    val timeKey = computeTimeKey(optime, playTimeSec, uin)
    val playTimeMs = playTimeSec * 1000L
    val loginType = if (authst.startsWith("W_X")) 1 else 2

    val uidDigits = deriveNumericUid(deviceInfo.openUdid)
    val sid = SID_FORMATTER.format(Instant.ofEpochSecond(optime)) + uidDigits
    val traceid = "11_${deriveTraceNumber(deviceInfo.openUdid, optime)}_$optime"
    val vkey = deriveHex32(deviceInfo.openUdid, optime)
    val tid = deriveNumeric19(deviceInfo.openUdid)
    val taid = deriveHex88(deviceInfo.openUdid)
    val v4ip = "192.168.1.100"

    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<root>" +
        "<OpenUDID>${deviceInfo.openUdid}</OpenUDID>" +
        "<udid>${deviceInfo.openUdid}</udid>" +
        "<ct>11</ct><cv>12030508</cv><v>12030508</v>" +
        "<chid>74648</chid><os_ver>${deviceInfo.osVersion}</os_ver>" +
        "<aid>${deviceInfo.androidId}</aid><phonetype>${deviceInfo.model}</phonetype>" +
        "<devicelevel>${deviceInfo.sdkInt}</devicelevel><newdevicelevel>${deviceInfo.sdkInt}</newdevicelevel>" +
        "<deviceScore>800.0</deviceScore>" +
        "<QIMEI36></QIMEI36>" +
        "<taid>$taid</taid>" +
        "<tmeAppID>qqmusic</tmeAppID><tid>$tid</tid>" +
        "<modeSwitch>6</modeSwitch><teenMode>0</teenMode>" +
        "<uid>$uidDigits</uid><sid>$sid</sid>" +
        "<OpenUDID2>${deviceInfo.openUdid}</OpenUDID2>" +
        "<ui_mode>1</ui_mode><nettype>1030</nettype>" +
        "<tmeLoginType>$loginType</tmeLoginType><tmeLoginMethod>2</tmeLoginMethod>" +
        "<fPersonality>0</fPersonality><wid>$uidDigits</wid>" +
        "<v4ip>$v4ip</v4ip>" +
        "<qq>$uin</qq>" +
        "<authst>$authst</authst>" +
        "<psrf_qqopenid></psrf_qqopenid>" +
        "<psrf_access_token_expiresAt>0</psrf_access_token_expiresAt>" +
        "<psrf_qqaccess_token></psrf_qqaccess_token>" +
        "<hotfix>100000000</hotfix>" +
        "<traceid>$traceid</traceid>" +
        "<cid>228</cid>" +
        "<item cmd=\"1\" optime=\"$optime\" nettype=\"1030\" QQ=\"$uin\" uid=\"$uidDigits\" " +
        "os=\"${deviceInfo.osVersion}\" model=\"${deviceInfo.model}\" version=\"12.3.5.8\" " +
        "songtype=\"1\" playtype=\"4\" from=\"1,132,151,\" dts=\"0\" openstore=\"0\" " +
        "crytype=\"5\" paytype=\"3\" desktoplyric=\"0\" playdevice=\"0\" playlist_mode=\"0\" " +
        "outdev=\"0\" url=\"26\" playmode=\"1\" repeat_times=\"0\" cdn=\"\" cdnip=\"\" " +
        "hasFirstBuffer=\"1\" hijackflag=\"0\" filetype=\"4\" err=\"0\" size=\"0\" " +
        "time=\"$playTimeSec\" retry=\"0\" issoftdecode=\"1\" component_type=\"2\" " +
        "bandwidth_policy=\"0\" secondCacheCount=\"0\" wait_time=\"0\" player_retry=\"0\" " +
        "audiotime=\"$durationSec\" timekey=\"$timeKey\" vkey=\"$vkey\" " +
        "play_duration_mi=\"$playTimeMs\" play_speed=\"1.0\" vip_level=\"65552\" " +
        "audio_effect=\"0:0\" songid=\"$songId\" singerid=\"0\" fversion=\"0\"/>" +
        "</root>"
}

internal fun computeTimeKey(
    optime: Long,
    timeSec: Long,
    uin: String,
): String {
    val raw = "${optime}${timeSec}${uin}$TIMEKEY_SALT"
    return md5Hex(raw).uppercase()
}

internal fun md5Hex(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun compressGzip(data: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { gzip ->
        gzip.write(data)
    }
    return bos.toByteArray()
}

private fun deriveNumericUid(openUdid: String): String {
    val hash = abs(openUdid.hashCode().toLong())
    return hash.toString().padStart(10, '0').take(10)
}

private fun deriveTraceNumber(
    openUdid: String,
    optime: Long,
): String {
    val combined = "$openUdid:$optime"
    val hash = abs(combined.hashCode().toLong())
    return hash.toString().padStart(11, '0').take(11)
}

private fun deriveHex32(
    openUdid: String,
    optime: Long,
): String = md5Hex("$openUdid:$optime")

private fun deriveNumeric19(openUdid: String): String {
    val h1 = abs(openUdid.hashCode().toLong()).toString().padStart(10, '0').take(10)
    val h2 = abs(openUdid.reversed().hashCode().toLong()).toString().padStart(9, '0').take(9)
    return "$h1$h2"
}

private fun deriveHex88(openUdid: String): String {
    val part1 = md5Hex("${openUdid}_p1")
    val part2 = md5Hex("${openUdid}_p2")
    val part3 = md5Hex("${openUdid}_p3").take(24)
    return "$part1$part2$part3"
}
