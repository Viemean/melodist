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
    enabled: Boolean = true,
    menuRequester: FocusRequester? = null,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val surfaceColor = LocalMonetSurface.current

    Button(
        onClick = onToggle,
        enabled = enabled,
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
                disabledContainerColor = surfaceColor.toMonetContainer(0.04f),
                disabledContentColor = Color.White.copy(alpha = 0.35f),
            ),
        border =
            ButtonDefaults.border(
                border =
                    Border(
                        border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.10f else 0.04f)),
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
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                        lineHeight = 18.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.width(20.dp))

            val pillBgColor =
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    isFocused && checked -> Color(0xFF0D9488)
                    isFocused && !checked -> Color(0xFF2C313A)
                    !isFocused && checked -> MelodistColors.AccentGreen
                    else -> Color.White.copy(alpha = 0.12f)
                }
            val pillTextColor =
                when {
                    !enabled -> Color.White.copy(alpha = 0.35f)
                    isFocused && checked -> Color.White
                    isFocused && !checked -> Color.White
                    !isFocused && checked -> Color.Black
                    else -> Color.White.copy(alpha = 0.75f)
                }
            val pillBorder =
                when {
                    !enabled -> BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
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
                    text =
                        if (!enabled) {
                            "不可用"
                        } else if (checked) {
                            "开启"
                        } else {
                            "关闭"
                        },
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
