package org.melodist.mobile.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.melodist.data.UserMusicPreferenceManager
import org.melodist.model.MusicPreferenceItem
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPreferenceBottomSheet(
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val currentPreference by UserMusicPreferenceManager.preferenceFlow.collectAsState()
    val isLoading by UserMusicPreferenceManager.isLoadingFlow.collectAsState()
    val isSaving by UserMusicPreferenceManager.isSavingFlow.collectAsState()

    // 局部编辑状态表：ProfileKey -> 当前分值
    val editingScores = remember { mutableStateMapOf<String, Int>() }
    var lastAdjustKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val pref = UserMusicPreferenceManager.refresh(force = false)
        pref?.scores?.forEach { item ->
            editingScores[item.profileKey] = item.currentScore
        }
    }

    LaunchedEffect(currentPreference) {
        currentPreference?.scores?.forEach { item ->
            if (!editingScores.containsKey(item.profileKey)) {
                editingScores[item.profileKey] = item.currentScore
            }
        }
    }

    val blockScrollOverflowConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y < 0f) {
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity {
                if (available.y < 0f) {
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().nestedScroll(blockScrollOverflowConnection),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // 顶部小把手
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )

            // 标题与生效状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "我的音乐偏好",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (currentPreference?.isExpired == true) "调节状态已过期" else "调节状态生效中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isLoading || isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            // 上次调节时间提示栏
            val updateTimeSec = currentPreference?.updateTime ?: 0L
            val dateLabel =
                remember(updateTimeSec) {
                    if (updateTimeSec <= 0L) {
                        "默认设置"
                    } else {
                        val dt =
                            LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(updateTimeSec),
                                ZoneId.systemDefault(),
                            )
                        dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    }
                }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "上次调节：$dateLabel (30天有效)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (currentPreference?.isExpired == true) "已过期" else "生效中",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (currentPreference?.isExpired == true) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
            }

            // 中间可滚动选项列表
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. 歌曲熟悉度
                item(key = "familiarity_section") {
                    val currentFamiliarity = editingScores[MusicPreferenceItem.KEY_FAMILIARITY] ?: 50
                    PreferenceOptionGroup(
                        title = "歌曲熟悉度",
                        options =
                            listOf(
                                PreferenceOptionItem("更探索", 25, "🧐"),
                                PreferenceOptionItem("保持现状", 50, "🙂"),
                                PreferenceOptionItem("更熟悉", 75, "🥰"),
                            ),
                        selectedScore = currentFamiliarity,
                        onSelectScore = { score ->
                            editingScores[MusicPreferenceItem.KEY_FAMILIARITY] = score
                            lastAdjustKey = MusicPreferenceItem.KEY_FAMILIARITY
                        },
                    )
                }

                // 2. 音乐情绪
                item(key = "emotion_section") {
                    val currentEmotion = editingScores[MusicPreferenceItem.KEY_EMOTION] ?: 50
                    PreferenceOptionGroup(
                        title = "音乐情绪",
                        options =
                            listOf(
                                PreferenceOptionItem("更伤感", 25, "😭"),
                                PreferenceOptionItem("保持现状", 50, "🙂"),
                                PreferenceOptionItem("更欢快", 75, "🥳"),
                            ),
                        selectedScore = currentEmotion,
                        onSelectScore = { score ->
                            editingScores[MusicPreferenceItem.KEY_EMOTION] = score
                            lastAdjustKey = MusicPreferenceItem.KEY_EMOTION
                        },
                    )
                }

                // 3. 歌曲风格
                item(key = "style_section_title") {
                    Text(
                        text = "歌曲风格",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                val defaultStyleKeys =
                    listOf(
                        MusicPreferenceItem.KEY_COVERS to "翻唱&现场",
                        MusicPreferenceItem.KEY_ACOUSTIC to "纯音乐",
                        MusicPreferenceItem.KEY_CHINESE to "国语",
                        MusicPreferenceItem.KEY_ENGLISH to "英语",
                        MusicPreferenceItem.KEY_JAPANESE to "日语",
                        MusicPreferenceItem.KEY_KOREAN to "韩语",
                        MusicPreferenceItem.KEY_CANTONESE to "粤语",
                        MusicPreferenceItem.KEY_AI_SONG to "AI歌曲",
                    )

                items(defaultStyleKeys.size, key = { defaultStyleKeys[it].first }) { idx ->
                    val (key, defaultName) = defaultStyleKeys[idx]
                    val serverItem = currentPreference?.scores?.firstOrNull { it.profileKey == key }
                    val label = serverItem?.profileName?.ifBlank { defaultName } ?: defaultName
                    val score = editingScores[key] ?: (serverItem?.currentScore ?: 50)

                    PreferenceSliderRow(
                        name = label,
                        score = score,
                        onScoreChanged = { newScore ->
                            editingScores[key] = newScore
                            lastAdjustKey = key
                        },
                    )
                }
            }

            // 底部操作按钮栏
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val itemsToSave =
                                currentPreference?.scores?.map { item ->
                                    val newScore = editingScores[item.profileKey] ?: item.currentScore
                                    item.copy(currentScore = newScore)
                                } ?: emptyList()

                            val success =
                                UserMusicPreferenceManager.savePreferences(
                                    items = itemsToSave,
                                    activeKey = lastAdjustKey,
                                )
                            if (success) {
                                Toast.makeText(context, "偏好已保存，调节状态持续生效30天", Toast.LENGTH_SHORT).show()
                                sheetState.hide()
                                onDismissRequest()
                            } else {
                                Toast.makeText(context, "保存失败，请检查登录状态或稍后重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isSaving,
                ) {
                    Text(
                        text = if (isSaving) "保存中..." else "保存",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val success = UserMusicPreferenceManager.resetPreferences()
                            if (success) {
                                currentPreference?.scores?.forEach { item ->
                                    editingScores[item.profileKey] = 50
                                }
                                Toast.makeText(context, "已重置为默认偏好", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "重置失败，请稍后重试", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    enabled = !isSaving,
                ) {
                    Text(
                        text = "重置",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

private data class PreferenceOptionItem(
    val label: String,
    val score: Int,
    val emoji: String,
)

@Composable
private fun PreferenceOptionGroup(
    title: String,
    options: List<PreferenceOptionItem>,
    selectedScore: Int,
    onSelectScore: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                val isSelected = selectedScore == opt.score
                val containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    }
                val contentColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = containerColor,
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clickable { onSelectScore(opt.score) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = opt.emoji,
                            fontSize = 22.sp,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = opt.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = contentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceSliderRow(
    name: String,
    score: Int,
    onScoreChanged: (Int) -> Unit,
) {
    val stateLabel =
        when {
            score < 40 -> "减少推荐"
            score > 60 -> "增加推荐"
            else -> "默认比例"
        }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (score != 50) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            Slider(
                value = score.toFloat(),
                onValueChange = { onScoreChanged(it.toInt()) },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
        }
    }
}
