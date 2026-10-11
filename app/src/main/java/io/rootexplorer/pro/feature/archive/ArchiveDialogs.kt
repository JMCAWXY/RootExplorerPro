package io.rootexplorer.pro.feature.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.archive.ArchiveFormat
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter
import java.io.File
import java.util.zip.Deflater

/**
 * The compress dialog.
 *
 * Shows what will be archived, where the archive will be written, and the format. Only formats
 * the engine can actually create are offered, and the compression level is explained rather
 * than presented as a bare slider.
 */
@Composable
fun CompressDialog(
    sources: List<String>,
    defaultDirectory: String,
    state: ArchiveUiState,
    onCompress: (targetPath: String, format: ArchiveFormat, level: Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FileManagerTheme.colors

    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    val suggestedName = remember(sources, format) {
        val base = when {
            sources.size == 1 -> File(sources.first()).name
            else -> "archive"
        }
        "$base.${format.extension}"
    }
    var name by remember(suggestedName) { mutableStateOf(suggestedName) }
    var level by remember { mutableFloatStateOf(6f) }

    val targetPath = remember(defaultDirectory, name) {
        FsEngine.childOf(defaultDirectory, name)
    }
    val nameProblem = validateArchiveName(name)
    val running = state.isRunning

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("压缩", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (running) {
                    RunningSection(action = ArchiveAction.COMPRESS.label, state = state, onCancel = onCancel)
                    return@Column
                }

                Text(
                    text = "将压缩 ${sources.size} 个项目",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                for (source in sources.take(6)) {
                    Text(
                        text = File(source).name,
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (sources.size > 6) {
                    Text(
                        text = "…另有 ${sources.size - 6} 项",
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("格式", style = MaterialTheme.typography.labelMedium)
                ArchiveFormat.creatable.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { format = option }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == format, onClick = { format = option })
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (option == ArchiveFormat.GZIP) {
                                    "GZip 只能容纳单个文件，多选时只会压缩第一个"
                                } else {
                                    "通用格式，其他应用与系统均可打开"
                                },
                                style = MetaTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("压缩包名称") },
                    singleLine = true,
                    isError = nameProblem != null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    supportingText = { Text(nameProblem ?: targetPath) },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (format == ArchiveFormat.ZIP) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "压缩级别：${level.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = level,
                        onValueChange = { level = it },
                        valueRange = 0f..9f,
                        steps = 8,
                    )
                    Text(
                        text = when {
                            level <= 1f -> "最快，压缩率最低。适合已经压缩过的内容（视频、图片）。"
                            level >= 8f -> "最慢，压缩率最高。适合文本和代码。"
                            else -> "默认平衡设置。"
                        },
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (sources.size > 1 && format == ArchiveFormat.GZIP) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = colors.danger,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "已选择多个项目，但 GZip 只能保存一个文件。",
                            style = MetaTextStyle,
                            color = colors.danger,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(
                    onClick = {
                        onCompress(targetPath, format, level.toInt().coerceIn(0, 9).let { sliderToDeflater(it) })
                    },
                    enabled = nameProblem == null && sources.isNotEmpty(),
                ) {
                    Text("开始压缩")
                }
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * The extract dialog.
 *
 * Shows where the archive will be unpacked and what happens to files that already exist. The
 * conflict choice is explicit and its wording says what is destroyed, because extracting over
 * a directory is a common way to lose work.
 */
@Composable
fun ExtractDialog(
    archivePath: String,
    defaultDirectory: String,
    entryCount: Int?,
    state: ArchiveUiState,
    onExtract: (targetDirectory: String, policy: ConflictPolicy) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FileManagerTheme.colors
    val archiveName = File(archivePath).name
    val archiveBase = remember(archiveName) {
        ArchiveFormat.fromName(archiveName)?.let { format ->
            archiveName.removeSuffix(".${format.extension}")
        } ?: archiveName
    }

    var targetDirectory by remember(defaultDirectory, archiveBase) {
        mutableStateOf(FsEngine.childOf(defaultDirectory, archiveBase))
    }
    var policy by remember { mutableStateOf(ConflictPolicy.RENAME) }
    val running = state.isRunning

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("解压", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (running) {
                    RunningSection(action = ArchiveAction.EXTRACT.label, state = state, onCancel = onCancel)
                    return@Column
                }

                Text(archiveName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = archivePath,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entryCount?.let {
                    Text(
                        text = "包含 $it 个条目",
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = targetDirectory,
                    onValueChange = { targetDirectory = it },
                    label = { Text("解压到") },
                    singleLine = true,
                    textStyle = MonospaceTextStyle,
                    supportingText = { Text("目录不存在时会自动创建") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                Text("同名文件处理", style = MaterialTheme.typography.labelMedium)
                PolicyOption(
                    title = "保留两者",
                    description = "已存在的文件不动，新文件改名为“名称 (1)”。",
                    selected = policy == ConflictPolicy.RENAME,
                    onClick = { policy = ConflictPolicy.RENAME },
                )
                PolicyOption(
                    title = "跳过",
                    description = "已存在的文件保持原样，也不写入新内容。",
                    selected = policy == ConflictPolicy.SKIP,
                    onClick = { policy = ConflictPolicy.SKIP },
                )
                PolicyOption(
                    title = "覆盖",
                    description = "用压缩包中的内容替换已存在的文件，原内容会丢失。",
                    selected = policy == ConflictPolicy.OVERWRITE,
                    danger = true,
                    onClick = { policy = ConflictPolicy.OVERWRITE },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "安全说明：压缩包中指向目标目录之外的条目（例如 ../../）会被拒绝，" +
                        "不会写入到解压目录以外的位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.bannerText,
                )
            }
        },
        confirmButton = {
            if (!running) {
                TextButton(onClick = { onExtract(targetDirectory.trim(), policy) }) {
                    Text("开始解压")
                }
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun PolicyOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) FileManagerTheme.colors.danger else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The shared progress section, used while an action runs. */
@Composable
private fun RunningSection(action: String, state: ArchiveUiState, onCancel: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$action 进行中…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("停止") }
        }

        state.progress?.let { progress ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("${progress.entriesDone}/${progress.entriesTotal} 项")
                    if (progress.bytesTotal > 0) {
                        append("  ${Formatter.size(progress.bytesDone)} / ${Formatter.size(progress.bytesTotal)}")
                    } else {
                        append("  ${Formatter.size(progress.bytesDone)}")
                    }
                },
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progress.currentEntry.isNotBlank()) {
                Text(
                    text = progress.currentEntry,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            val fraction = state.fraction
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The result panel shown after an operation finishes. */
@Composable
fun ArchiveResultSection(
    summary: ArchiveResultSummary,
    onDismissResult: () -> Unit,
) {
    val colors = FileManagerTheme.colors
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            text = summary.headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (summary.isFailure) colors.danger else MaterialTheme.colorScheme.onSurface,
        )
        if (summary.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary.detail,
                style = MetaTextStyle,
                color = if (summary.isPartial) colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismissResult) { Text("关闭") }
        }
    }
}

/** Validate an archive name before offering to create it. */
fun validateArchiveName(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "名称不能为空"
        trimmed.contains('/') -> "名称不能包含 /"
        trimmed.contains('\u0000') -> "名称不能包含空字符"
        trimmed.contains('\\') -> "名称不能包含反斜杠"
        trimmed == "." || trimmed == ".." -> "名称无效"
        else -> null
    }
}

/** Map a 0..9 slider value onto a Deflater level. */
private fun sliderToDeflater(level: Int): Int = when {
    level <= 0 -> Deflater.NO_COMPRESSION
    level >= 9 -> Deflater.BEST_COMPRESSION
    else -> level
}
