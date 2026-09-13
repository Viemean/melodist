package org.melodist.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.melodist.tv.ui.theme.MelodistColors
import org.melodist.tv.ui.theme.MelodistShapes

private val NavItems =
    listOf(
        "主页",
        "WebDAV",
        "本地音乐",
        "远程控制",
        "听歌识曲",
        "曲库搜索",
        "设置",
    )

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TopNavBar(
    selectedIndex: Int = 0,
    downFocusRequester: FocusRequester? = null,
    currentTabRequester: FocusRequester? = null,
    onItemSelected: (Int) -> Unit = {},
) {
    val tabRequesters = remember { List(NavItems.size) { FocusRequester() } }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItems.forEachIndexed { index, title ->
            val isSelected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }

            val itemModifier =
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .focusRequester(tabRequesters[index])
                    .then(
                        if (isSelected && currentTabRequester != null) {
                            Modifier.focusRequester(currentTabRequester)
                        } else {
                            Modifier
                        },
                    ).focusProperties {
                        if (downFocusRequester != null) {
                            down = downFocusRequester
                        }
                        if (index > 0) {
                            left = tabRequesters[index - 1]
                        }
                        if (index < NavItems.size - 1) {
                            right = tabRequesters[index + 1]
                        }
                    }

            Button(
                onClick = { onItemSelected(index) },
                modifier = itemModifier,
                shape =
                    ButtonDefaults.shape(
                        shape = MelodistShapes.PillCorner,
                        focusedShape = MelodistShapes.PillCorner,
                    ),
                colors =
                    ButtonDefaults.colors(
                        containerColor = if (isSelected) Color.White.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.14f),
                        focusedContainerColor = Color.White,
                        contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.88f),
                        focusedContentColor = Color.Black,
                    ),
                border =
                    ButtonDefaults.border(
                        border =
                            Border(
                                border =
                                    if (isSelected) {
                                        BorderStroke(1.dp, Color.White.copy(alpha = 0.38f))
                                    } else {
                                        BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
                                    },
                                shape = MelodistShapes.PillCorner,
                            ),
                        focusedBorder =
                            Border(
                                border = BorderStroke(2.dp, MelodistColors.FocusTeal),
                                shape = MelodistShapes.PillCorner,
                            ),
                    ),
                scale =
                    ButtonDefaults.scale(
                        focusedScale = 1.05f,
                    ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
