package org.melodist.tv.ui.settings

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodecList
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.melodist.tv.BuildConfig
import org.melodist.tv.ui.theme.LocalMonetSurface
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.toMonetContainer
import org.melodist.data.update.UpdateChecker
import org.melodist.data.update.UpdateResult

private data class CodecItem(
    val formatName: String,
    val mimeType: String,
    val codecName: String,
    val isHardware: Boolean,
    val maxChannels: Int,
    val sampleRateDesc: String,
)

private data class AudioProbeReport(
    val maxOutputChannels: Int,
    val supportedDirectFormats: List<String>,
    val systemDecoders: List<CodecItem>,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AboutPanel(menuRequester: FocusRequester) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var probeReport by remember { mutableStateOf<AudioProbeReport?>(null) }

    LaunchedEffect(Unit) {
        val report =
            withContext(Dispatchers.IO) {
                probeAudioCapabilities(context)
            }
        probeReport = report
    }

    val surfaceColor = LocalMonetSurface.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingPanelHeader(title = "关于 Melodist TV")

        // 软件版本与检查更新
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var updateFeedback by remember { mutableStateOf<String?>(null) }
        val coroutineScope = rememberCoroutineScope()

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(MelodistShapes.CardCorner)
                    .background(surfaceColor.toMonetContainer(0.06f))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), MelodistShapes.CardCorner)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Melodist TV",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = updateFeedback ?: "当前版本 v${BuildConfig.VERSION_NAME} (构建号 ${BuildConfig.VERSION_CODE})",
                    fontSize = 13.sp,
                    color = if (updateFeedback != null) MelodistColors.AccentGreen else MelodistColors.TextSecondary,
                )
            }

            Button(
                onClick = {
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        updateFeedback = "正在连接 GitHub 检查更新..."
                        coroutineScope.launch {
                            when (
                                val result =
                                    UpdateChecker.checkUpdate(
                                        currentVersion = BuildConfig.VERSION_NAME,
                                        targetKeyword = "tv",
                                    )
                            ) {
                                is UpdateResult.NewVersion -> {
                                    updateFeedback = "发现新版本 ${result.tagName}"
                                }
                                is UpdateResult.Latest -> {
                                    updateFeedback = "当前已是最新版本 (v${result.currentVersion})"
                                }
                                is UpdateResult.Error -> {
                                    updateFeedback = "检查更新失败: ${result.message}"
                                }
                            }
                            isCheckingUpdate = false
                        }
                    }
                },
                modifier = Modifier.focusProperties { left = menuRequester },
                shape =
                    ButtonDefaults.shape(
                        shape = MelodistShapes.ButtonCorner,
                        focusedShape = MelodistShapes.ButtonCorner,
                    ),
                colors =
                    ButtonDefaults.colors(
                        containerColor = surfaceColor.toMonetContainer(0.12f),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black,
                    ),
                border =
                    ButtonDefaults.border(
                        border =
                            Border(
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                shape = MelodistShapes.ButtonCorner,
                            ),
                        focusedBorder = Border.None,
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdateAlt,
                        contentDescription = "检查更新",
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isCheckingUpdate) "正在检查..." else "检查更新",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 1. 系统与运行环境 (平滑随焦点滑动，无多余嵌套方框)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SelectableParamRow(
                label = "应用版本",
                value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                menuRequester = menuRequester,
            )
            SelectableParamRow(
                label = "开源仓库",
                value = "github.com/Viemean/melodist",
                menuRequester = menuRequester,
            )
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"
            SelectableParamRow(label = "设备型号", value = "${Build.MANUFACTURER} ${Build.MODEL}", menuRequester = menuRequester)
            SelectableParamRow(label = "运行架构", value = primaryAbi, menuRequester = menuRequester)
            SelectableParamRow(
                label = "系统版本",
                value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                menuRequester = menuRequester,
            )
            SelectableParamRow(label = "编程语言", value = "Kotlin ${KotlinVersion.CURRENT}", menuRequester = menuRequester)
            SelectableParamRow(
                label = "构建工具",
                value = "Gradle ${BuildConfig.GRADLE_VERSION} · AGP ${BuildConfig.AGP_VERSION}",
                menuRequester = menuRequester,
            )
            SelectableParamRow(label = "音频引擎", value = "Media3 ExoPlayer ${MediaLibraryInfo.VERSION}", menuRequester = menuRequester)
            SelectableParamRow(label = "UI 框架", value = "Compose for TV ${BuildConfig.COMPOSE_TV_VERSION}", menuRequester = menuRequester)
        }

        // 音频直通能力
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val maxCh = probeReport?.maxOutputChannels ?: 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "外接输出与直通能力 (HDMI / eARC)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )

                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$maxCh 声道 (${if (maxCh >= 8) {
                            "7.1"
                        } else if (maxCh >= 6) {
                            "5.1"
                        } else {
                            "立体声"
                        }})",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 只亮支持的直通格式
            val supportedFormats = probeReport?.supportedDirectFormats ?: emptyList()
            if (supportedFormats.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    supportedFormats.forEach { format ->
                        SelectableBadgeItem(text = format, menuRequester = menuRequester)
                    }
                }
            } else {
                Text(
                    text = "标准立体声 PCM 输出已就绪",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }

        // 系统音频解码器
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "系统音频解码器 (MediaCodec)",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )

            val decoders = probeReport?.systemDecoders ?: emptyList()
            if (decoders.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    decoders.forEach { codec ->
                        CodecRowItem(codec = codec, menuRequester = menuRequester)
                    }
                }
            } else {
                Text(text = "正在读取解码器列表...", fontSize = 12.sp, color = MelodistColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun SelectableParamRow(
    label: String,
    value: String,
    menuRequester: FocusRequester,
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFocused) MelodistColors.FocusTeal.copy(alpha = 0.12f) else Color.Transparent)
                .border(
                    BorderStroke(
                        1.5.dp,
                        if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                    ),
                    RoundedCornerShape(8.dp),
                ).onFocusChanged { isFocused = it.isFocused }
                .focusProperties { left = menuRequester }
                .focusable()
                .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color.White,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

@Composable
private fun SelectableBadgeItem(
    text: String,
    menuRequester: FocusRequester,
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isFocused) MelodistColors.FocusTeal.copy(alpha = 0.25f) else Color(0xFF064E3B))
                .border(
                    BorderStroke(
                        1.5.dp,
                        if (isFocused) MelodistColors.FocusTeal else Color(0xFF059669),
                    ),
                    RoundedCornerShape(6.dp),
                ).onFocusChanged { isFocused = it.isFocused }
                .focusProperties { left = menuRequester }
                .focusable()
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.White else Color(0xFF6EE7B7),
        )
    }
}

@Composable
private fun CodecRowItem(
    codec: CodecItem,
    menuRequester: FocusRequester,
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFocused) MelodistColors.FocusTeal.copy(alpha = 0.12f) else Color.Transparent)
                .border(
                    BorderStroke(
                        1.5.dp,
                        if (isFocused) MelodistColors.FocusTeal else Color.Transparent,
                    ),
                    RoundedCornerShape(8.dp),
                ).onFocusChanged { isFocused = it.isFocused }
                .focusProperties { left = menuRequester }
                .focusable()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 第一行：格式名称靠左，硬件加速/软件解码标签靠右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = codec.formatName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isFocused) Color.White else MelodistColors.TextPrimary,
            )
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (codec.isHardware) Color(0xFF047857) else Color(0xFF374151))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (codec.isHardware) "硬件加速" else "软件解码",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
        }

        // 第二行：底层解码器组件名靠左，声道与采样率规格靠右
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = codec.codecName,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
            Text(
                text = "最大 ${codec.maxChannels} 声道 · ${codec.sampleRateDesc}",
                fontSize = 11.sp,
                color = Color.White,
            )
        }
    }
}

private fun probeAudioCapabilities(context: Context): AudioProbeReport {
    val audioCaps = AudioCapabilities.getCapabilities(context)
    val maxChannels = audioCaps.maxChannelCount

    val candidateFormats =
        listOf(
            "PCM 24-bit" to AudioFormat.ENCODING_PCM_24BIT_PACKED,
            "PCM 32-bit Float" to AudioFormat.ENCODING_PCM_FLOAT,
            "Dolby Atmos" to AudioFormat.ENCODING_E_AC3_JOC,
            "Dolby Digital Plus" to AudioFormat.ENCODING_E_AC3,
            "Dolby Digital" to AudioFormat.ENCODING_AC3,
            "Dolby TrueHD" to AudioFormat.ENCODING_DOLBY_TRUEHD,
            "DTS" to AudioFormat.ENCODING_DTS,
            "DTS-HD" to AudioFormat.ENCODING_DTS_HD,
        )

    val supportedDirects =
        candidateFormats
            .filter { (_, encoding) -> audioCaps.supportsEncoding(encoding) }
            .map { (label, _) -> label }

    val codecList = mutableListOf<CodecItem>()
    try {
        val mcl = MediaCodecList(MediaCodecList.ALL_CODECS)
        val mimeFilters =
            listOf(
                "FLAC" to "audio/flac",
                "AAC" to "audio/mp4a-latm",
                "Opus" to "audio/opus",
                "Vorbis" to "audio/vorbis",
                "MP3" to "audio/mpeg",
                "Dolby Digital (AC-3)" to "audio/ac3",
                "Dolby Digital Plus (E-AC-3)" to "audio/eac3",
                "Dolby Atmos (E-AC-3 JOC)" to "audio/eac3-joc",
                "Dolby TrueHD" to "audio/true-hd",
                "DTS" to "audio/dts",
            )
        for ((name, mime) in mimeFilters) {
            val decoder =
                mcl.codecInfos.firstOrNull {
                    !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) }
                }
            if (decoder != null) {
                val caps = decoder.getCapabilitiesForType(mime)
                val aCaps = caps.audioCapabilities
                val maxCh = aCaps?.maxInputChannelCount ?: 2
                val isHw =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        decoder.isHardwareAccelerated
                    } else {
                        !decoder.name.startsWith("OMX.google.", ignoreCase = true) &&
                            !decoder.name.startsWith("c2.android.", ignoreCase = true)
                    }
                val sampleRateDesc =
                    aCaps?.supportedSampleRateRanges?.let { ranges ->
                        if (ranges.isNotEmpty()) "${ranges.first().lower / 1000}k~${ranges.last().upper / 1000}kHz" else "自适应"
                    } ?: "全频带"
                codecList.add(CodecItem(name, mime, decoder.name, isHw, maxCh, sampleRateDesc))
            }
        }
    } catch (_: Exception) {
    }

    return AudioProbeReport(
        maxOutputChannels = maxChannels,
        supportedDirectFormats = supportedDirects,
        systemDecoders = codecList,
    )
}
