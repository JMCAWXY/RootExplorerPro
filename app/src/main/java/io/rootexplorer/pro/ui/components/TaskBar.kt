package io.rootexplorer.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.service.fileoperation.Conflict
import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import io.rootexplorer.pro.service.taskmanager.TaskState
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The task strip.
 *
 * It shows the one running file task: what is happening, how far along it is, the throughput,
 * a cancel button, and — when it finishes — whether the result was complete, partial or a
 * failure. Partial results are never dressed up as success.
 *
 * Rendered above the bottom action bar so it is visible without covering the file list.
 */
@Composable
fun TaskBar(
    task: TaskState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (task is TaskState.Idle) return
    val colors = FileManagerTheme.colors

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            when (task) {
                is TaskState.Idle -> Unit

                is TaskState.Preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${task.kind.label}：正在准备…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                    // An indeterminate bar is correct here: the size is not known yet.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is TaskState.AskingConflict -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = colors.danger,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${task.kind.label}：发现同名项目，等待你的选择",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }

                is TaskState.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${task.kind.label} ${task.itemsDone}/${task.itemsTotal}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildString {
                                val fraction = task.fraction
                                if (fraction != null) {
                                    append("${(fraction * 100).toInt()}%  ")
                                }
                                append("${Formatter.size(task.bytesDone)}")
                                if (task.bytesTotal > 0) {
                                    append(" / ${Formatter.size(task.bytesTotal)}")
                                }
                                if (task.bytesPerSecond > 0) {
                                    append("  ${Formatter.size(task.bytesPerSecond)}/s")
                                }
                                if (task.failures > 0) {
                                    append("  失败 ${task.failures}")
                                }
                            },
                            style = MetaTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("取消") }
                    }

                    // The current item, shown in full on tap so a long path can be read.
                    Text(
                        text = task.currentItem,
                        style = MonospaceTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onClick),
                    )
                    Spacer(Modifier.height(4.dp))
                    val fraction = task.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is TaskState.Finished -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (task.isFailure) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = when {
                                task.isFailure -> colors.danger
                                task.isPartial -> colors.rootBadge
                                else -> colors.symlinkIcon
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = task.headline,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (task.detail.isNotBlank()) {
                        Text(
                            text = task.detail,
                            style = MetaTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Name-collision prompt.
 *
 * The brief requires that a conflict is surfaced rather than resolved by guesswork, and that a
 * bulk answer is possible. Each choice is described by what it actually does to the data —
 * "覆盖" destroys the existing file, and saying so plainly is the point.
 */
@Composable
fun ConflictDialog(
    conflict: Conflict,
    onPolicy: (ConflictPolicy) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = FileManagerTheme.colors

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("目标已存在同名项目", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("来源", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = conflict.sourcePath,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text("目标（已存在）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = conflict.existingPath,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append("已存在的是")
                        append(if (conflict.existingIsDirectory) "文件夹" else "文件")
                        if (!conflict.existingIsDirectory) {
                            append("，大小 ")
                            append(Formatter.size(conflict.existingSizeBytes))
                        }
                        append("，修改时间 ")
                        append(Formatter.listDate(conflict.existingModifiedMillis))
                        append("。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "请选择处理方式：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PolicyButton(
                    title = "保留两者",
                    description = "把来源复制为“名称 (1)”，不触碰已有文件。",
                    onClick = { onPolicy(ConflictPolicy.RENAME) },
                )
                PolicyButton(
                    title = "覆盖",
                    description = "用来源替换已有项目，已有内容会丢失。",
                    danger = true,
                    onClick = { onPolicy(ConflictPolicy.OVERWRITE) },
                )
                PolicyButton(
                    title = "跳过",
                    description = "不处理这一项，继续其余项目。",
                    onClick = { onPolicy(ConflictPolicy.SKIP) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消整个操作") }
        },
    )
}

@Composable
private fun PolicyButton(
    title: String,
    description: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val colors = FileManagerTheme.colors
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (danger) colors.danger else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = description,
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Full-screen dialog for a long path the user tapped in the task bar. */
@Composable
fun PathDetailDialog(path: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前项目", style = MaterialTheme.typography.titleSmall) },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text = path, style = MonospaceTextStyle)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
