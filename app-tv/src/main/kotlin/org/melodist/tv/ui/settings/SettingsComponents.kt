package org.melodist.tv.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.tv.ui.theme.LocalMonetSurface
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes
import org.melodist.tv.ui.theme.toMonetContainer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CategoryMenuItem(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    surfaceColor: Color = LocalMonetSurface.current,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = if (isSelected) surfaceColor.toMonetContainer(0.08f) else Color.Transparent,
                focusedContainerColor = Color.White,
                contentColor = if (isSelected) MelodistColors.AccentGreen else Color.White,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border =
                            if (isSelected) {
                                BorderStroke(1.dp, MelodistColors.AccentGreen.copy(alpha = 0.5f))
                            } else {
                                BorderStroke(1.dp, Color.Transparent)
                            },
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale =
            ButtonDefaults.scale(
                focusedScale = 1.03f,
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = LocalContentColor.current.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaceholderPanel(
    title: String,
    description: String,
    menuRequester: FocusRequester? = null,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (menuRequester != null) Modifier.focusProperties { left = menuRequester } else Modifier),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = description,
            fontSize = 15.sp,
            color = Color.White,
            lineHeight = 24.sp,
        )
        Spacer(modifier = Modifier.height(28.dp))
        val surfaceColor = LocalMonetSurface.current
        Button(
            onClick = {},
            modifier = Modifier,
            shape =
                ButtonDefaults.shape(
                    shape = MelodistShapes.ButtonCorner,
                    focusedShape = MelodistShapes.ButtonCorner,
                ),
            colors =
                ButtonDefaults.colors(
                    containerColor = surfaceColor.toMonetContainer(0.08f),
                    focusedContainerColor = Color.White,
                    contentColor = MelodistColors.TextPrimary,
                    focusedContentColor = Color.Black,
                ),
            border =
                ButtonDefaults.border(
                    border =
                        Border(
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                    focusedBorder =
                        Border(
                            border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                            shape = MelodistShapes.ButtonCorner,
                        ),
                ),
            scale = ButtonDefaults.scale(focusedScale = 1.06f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(text = "配置项就绪", fontSize = 14.sp)
        }
    }
}

@Composable
fun SettingPanelHeader(
    title: String,
    description: String = "",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        if (description.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                fontSize = 13.sp,
                color = Color.White,
                lineHeight = 20.sp,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingSwitchCard(
    title: String,
    subtitle: String = "",
    checked: Boolean,
    menuRequester: FocusRequester? = null,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val surfaceColor = LocalMonetSurface.current

    Button(
        onClick = onToggle,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (menuRequester != null) Modifier.focusProperties { left = menuRequester } else Modifier),
        interactionSource = interactionSource,
        shape =
            ButtonDefaults.shape(
                shape = MelodistShapes.ButtonCorner,
                focusedShape = MelodistShapes.ButtonCorner,
            ),
        colors =
            ButtonDefaults.colors(
                containerColor = surfaceColor.toMonetContainer(0.08f),
                focusedContainerColor = Color.White,
                contentColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                        shape = MelodistShapes.ButtonCorner,
                    ),
                focusedBorder =
                    Border(
                        border = BorderStroke(2.5.dp, MelodistColors.FocusTeal),
                        shape = MelodistShapes.ButtonCorner,
                    ),
            ),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (subtitle.isBlank()) 14.dp else 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Color.White,
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(20.dp))

            // 状态胶囊标签：在获焦(白底)与未获焦(暗底)下均保持明确的对比度与开闭提示
            val pillBgColor =
                when {
                    isFocused && checked -> Color(0xFF0D9488) // 获焦开启：翡翠青绿底
                    isFocused && !checked -> Color(0xFF2C313A) // 获焦关闭：深石板黑底
                    !isFocused && checked -> MelodistColors.AccentGreen // 未获焦开启：明绿底
                    else -> Color.White.copy(alpha = 0.12f) // 未获焦关闭：暗底
                }
            val pillTextColor =
                when {
                    isFocused && checked -> Color.White
                    isFocused && !checked -> Color.White
                    !isFocused && checked -> Color.Black
                    else -> Color.White.copy(alpha = 0.75f)
                }
            val pillBorder =
                when {
                    isFocused && checked -> BorderStroke(1.dp, Color(0xFF0F766E))
                    isFocused && !checked -> BorderStroke(1.dp, Color(0xFF1F2937))
                    !isFocused && checked -> BorderStroke(1.dp, MelodistColors.AccentGreen)
                    else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                }

            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(pillBgColor)
                        .border(pillBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (checked) "开启" else "关闭",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = pillTextColor,
                )
            }
        }
    }
}

@Composable
fun CacheDetailItem(
    label: String,
    value: String,
) {
    Column {
        Text(text = label, fontSize = 13.sp, color = Color.White)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
