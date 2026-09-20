package org.melodist.mobile.ui.components.songlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.melodist.mobile.ui.components.SongListDeleteType

/**
 * 歌曲列表悬浮操作与过滤组件
 */
@Composable
fun SongListFloatingActions(
    visible: Boolean,
    bottomPadding: Dp,
    // 过滤参数
    isFilterExpanded: Boolean,
    filterQuery: String,
    filterFocusRequester: FocusRequester,
    onFilterQueryChange: (String) -> Unit,
    onExpandFilter: () -> Unit,
    onCollapseFilter: () -> Unit,
    onClearFilter: () -> Unit,
    // 多选参数
    isMultiSelectMode: Boolean,
    isAllSelected: Boolean,
    enableDownload: Boolean,
    deleteType: SongListDeleteType,
    onToggleSelectAll: () -> Unit,
    onBatchDownloadClick: () -> Unit,
    onBatchDeleteClick: () -> Unit,
    onBatchAddClick: () -> Unit,
    onExitMultiSelect: () -> Unit,
    // 常规导航
    onScrollToTop: () -> Unit,
    onLocateCurrentPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.8f),
        exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
        modifier =
            modifier
                .imePadding()
                .padding(end = 16.dp, bottom = bottomPadding),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 1. 最上方：展开式胶囊过滤搜索条 / 过滤按钮
            if (!isMultiSelectMode || isFilterExpanded || filterQuery.isNotEmpty()) {
                if (isFilterExpanded) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 4.dp,
                        tonalElevation = 4.dp,
                        modifier =
                            Modifier
                                .height(40.dp)
                                .widthIn(min = 180.dp, max = 240.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            BasicTextField(
                                value = filterQuery,
                                onValueChange = onFilterQueryChange,
                                singleLine = true,
                                textStyle =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { onCollapseFilter() }),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (filterQuery.isEmpty()) {
                                            Text(
                                                text = "过滤曲目...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                                modifier = Modifier.weight(1f).focusRequester(filterFocusRequester),
                            )
                            if (filterQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = onClearFilter,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "清空过滤",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = onCollapseFilter,
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "收起过滤",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                } else {
                    SmallFloatingActionButton(
                        onClick = onExpandFilter,
                        containerColor =
                            if (filterQuery.isNotEmpty()) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        contentColor =
                            if (filterQuery.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = "过滤歌曲",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (isMultiSelectMode) {
                // 全选 / 取消全选按钮
                SmallFloatingActionButton(
                    onClick = onToggleSelectAll,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation =
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 3.dp,
                            pressedElevation = 6.dp,
                        ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (isAllSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                        contentDescription = if (isAllSelected) "取消全选" else "全选",
                        modifier = Modifier.size(20.dp),
                    )
                }

                // 下载按钮（在本地音乐、WebDAV、下载管理中隐藏）
                if (enableDownload) {
                    SmallFloatingActionButton(
                        onClick = onBatchDownloadClick,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "批量下载",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // 删除按钮
                if (deleteType != SongListDeleteType.None) {
                    SmallFloatingActionButton(
                        onClick = onBatchDeleteClick,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        elevation =
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 3.dp,
                                pressedElevation = 6.dp,
                            ),
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "批量删除",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // 添加按钮（二级菜单）
                SmallFloatingActionButton(
                    onClick = onBatchAddClick,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation =
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 3.dp,
                            pressedElevation = 6.dp,
                        ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                        contentDescription = "批量添加",
                        modifier = Modifier.size(20.dp),
                    )
                }

                // 退出多选按钮
                SmallFloatingActionButton(
                    onClick = onExitMultiSelect,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    elevation =
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 3.dp,
                            pressedElevation = 6.dp,
                        ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "退出多选",
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // 非多选模式：快速返回顶部
                SmallFloatingActionButton(
                    onClick = onScrollToTop,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation =
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 3.dp,
                            pressedElevation = 6.dp,
                        ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = "返回顶部",
                        modifier = Modifier.size(20.dp),
                    )
                }

                // 非多选模式：定位到当前播放音乐
                SmallFloatingActionButton(
                    onClick = onLocateCurrentPlaying,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.primary,
                    elevation =
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 3.dp,
                            pressedElevation = 6.dp,
                        ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = "定位当前在播",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
