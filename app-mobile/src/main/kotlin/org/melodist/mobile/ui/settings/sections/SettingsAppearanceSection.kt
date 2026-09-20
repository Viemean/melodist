package org.melodist.mobile.ui.settings.sections

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.melodist.data.AppColorTheme
import org.melodist.data.AppSettings
import org.melodist.data.AppSettingsManager
import org.melodist.data.ThemeMode
import org.melodist.mobile.ui.components.SettingsDivider
import org.melodist.mobile.ui.components.SettingsGroupCard
import org.melodist.mobile.ui.components.SettingsGroupTitle
import org.melodist.mobile.ui.components.SettingsSwitchRow
import org.melodist.mobile.ui.theme.parseHexColor

@Composable
fun SettingsAppearanceSection(
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isDynamicColorActive = isDynamicColorSupported && settings.dynamicColor
    var showCustomColorDialog by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SettingsGroupTitle(title = "外观与显示")
        SettingsGroupCard {
            // 1. 深浅色模式三段选择器
            ThemeModeSelector(
                selectedMode = settings.themeMode,
                onModeSelected = { AppSettingsManager.setThemeMode(it) },
                modifier = Modifier.padding(16.dp),
            )

            SettingsDivider()

            // 2. 动态取色开关 (Material You)
            SettingsSwitchRow(
                icon = Icons.Rounded.ColorLens,
                title = "动态取色 (Material You)",
                subtitle =
                    if (isDynamicColorSupported) {
                        "根据系统壁纸自动提取应用主题色"
                    } else {
                        "系统版本过低（需 Android 12+）"
                    },
                checked = isDynamicColorActive,
                onCheckedChange = { AppSettingsManager.setDynamicColor(it) },
                enabled = isDynamicColorSupported,
            )

            SettingsDivider()

            // 3. 预设调色板与自定义颜色选择器
            PresetPaletteSelector(
                currentColorTheme = settings.colorTheme,
                customColorHex = settings.customColorHex,
                isDynamicActive = isDynamicColorActive,
                onThemeSelected = { selectedTheme ->
                    if (settings.dynamicColor) {
                        AppSettingsManager.setDynamicColor(false)
                    }
                    if (selectedTheme == AppColorTheme.Custom) {
                        showCustomColorDialog = true
                    } else {
                        AppSettingsManager.setColorTheme(selectedTheme)
                    }
                },
                onOpenCustomDialog = { showCustomColorDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )

            SettingsDivider()

            // 4. AMOLED 纯黑背景
            SettingsSwitchRow(
                icon = Icons.Rounded.Contrast,
                title = "AMOLED 纯黑背景",
                subtitle = "深色模式下将背景设置为纯黑，适合 OLED 屏幕",
                checked = settings.amoledDark,
                onCheckedChange = { AppSettingsManager.setAmoledDark(it) },
            )
        }
    }

    if (showCustomColorDialog) {
        CustomColorHexDialog(
            initialHex = settings.customColorHex,
            onDismiss = { showCustomColorDialog = false },
            onApply = { hex ->
                if (settings.dynamicColor) {
                    AppSettingsManager.setDynamicColor(false)
                }
                AppSettingsManager.setCustomColorHex(hex)
                showCustomColorDialog = false
            },
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes =
        listOf(
            Triple(ThemeMode.System, "跟随系统", Icons.Rounded.BrightnessAuto),
            Triple(ThemeMode.Light, "浅色", Icons.Rounded.LightMode),
            Triple(ThemeMode.Dark, "深色", Icons.Rounded.DarkMode),
        )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            modes.forEach { (mode, label, icon) ->
                val isSelected = mode == selectedMode
                val targetContainerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    }
                val targetContentColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                val containerColor by animateColorAsState(targetValue = targetContainerColor, label = "modeBg")
                val contentColor by animateColorAsState(targetValue = targetContentColor, label = "modeFg")

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(containerColor)
                            .clickable { onModeSelected(mode) }
                            .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
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
private fun PresetPaletteSelector(
    currentColorTheme: AppColorTheme,
    customColorHex: String,
    isDynamicActive: Boolean,
    onThemeSelected: (AppColorTheme) -> Unit,
    onOpenCustomDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "主题配色方案",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isDynamicActive) {
                Text(
                    text = "壁纸动态取色生效中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (currentColorTheme == AppColorTheme.Custom) {
                Text(
                    text = "自定义: $customColorHex",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenCustomDialog() },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppColorTheme.entries.forEach { theme ->
                val isSelected = !isDynamicActive && theme == currentColorTheme
                val themeColor =
                    if (theme == AppColorTheme.Custom) {
                        parseHexColor(customColorHex)
                    } else {
                        Color(theme.seedColor)
                    }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onThemeSelected(theme) }
                            .padding(4.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 3.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).padding(if (isSelected) 4.dp else 0.dp)
                                .clip(CircleShape)
                                .background(themeColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        } else if (theme == AppColorTheme.Custom) {
                            Icon(
                                imageVector = Icons.Rounded.Colorize,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = theme.label.substringBefore(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomColorHexDialog(
    initialHex: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var hexInput by remember { mutableStateOf(initialHex) }
    val parsedColor =
        remember(hexInput) {
            val clean = hexInput.trim().removePrefix("#")
            val colorLong =
                when (clean.length) {
                    6 -> runCatching { 0xFF000000 or clean.toLong(16) }.getOrNull()
                    8 -> runCatching { clean.toLong(16) }.getOrNull()
                    else -> null
                }
            colorLong?.let { Color(it) }
        }
    val isValid = parsedColor != null

    val examplePresets =
        listOf(
            Triple("#1E88E5", "科技蓝", Color(0xFF1E88E5)),
            Triple("#FF6F00", "活力橙", Color(0xFFFF6F00)),
            Triple("#00897B", "松石青", Color(0xFF00897B)),
            Triple("#7C4DFF", "电光紫", Color(0xFF7C4DFF)),
            Triple("#D81B60", "玫红", Color(0xFFD81B60)),
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "自定义十六进制主题色",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "输入 16 进制颜色代码（支持 6 位或 8 位 HEX），例如 #1E88E5：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("颜色代码 (HEX)") },
                    placeholder = { Text("#1E88E5") },
                    singleLine = true,
                    isError = !isValid && hexInput.isNotEmpty(),
                    supportingText = {
                        if (!isValid && hexInput.isNotEmpty()) {
                            Text(
                                text = "请输入有效代码，如 #1E88E5 或 FF1E88E5",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    leadingIcon = {
                        Box(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(parsedColor ?: MaterialTheme.colorScheme.surfaceVariant)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        )
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "推荐示例（点击直接填入）：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    examplePresets.forEach { (hex, name, color) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .clickable { hexInput = hex },
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(color),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isValid) {
                        onApply(hexInput)
                    }
                },
                enabled = isValid,
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
