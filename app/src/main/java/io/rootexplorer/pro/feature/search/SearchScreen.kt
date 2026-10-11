package io.rootexplorer.pro.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileFilter
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The search screen.
 *
 * The scope of the search is stated on screen at all times — which directory, and whether
 * subdirectories are included — because "why did it not find my file" is almost always one of
 * those two answers. Progress, the result count and any unreadable paths are shown as well, so
 * a partial answer is never mistaken for a complete one.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    startPath: String,
    hasRoot: Boolean,
    onBack: () -> Unit,
    onSearch: (SearchQuery) -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onOpenResult: (FileEntry) -> Unit,
    onRevealInPane: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors

    var nameQuery by remember { mutableStateOf("") }
    var extension by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    var includeHidden by remember { mutableStateOf(true) }
    var kind by remember { mutableStateOf(FileFilter.KindFilter.ALL) }
    var confirmUnbounded by remember { mutableStateOf(false) }
    var showUnreadable by remember { mutableStateOf(false) }

    fun submit() {
        val query = SearchQuery(
            nameQuery = nameQuery,
            rootPath = startPath,
            recursive = recursive,
            includeHidden = includeHidden,
            kind = kind,
            extension = extension,
        )
        // A query with no constraint at all would match every file in the tree; make the user
        // confirm that is what they meant rather than starting a walk over /data by accident.
        if (query.isUnbounded && query.recursive) {
            confirmUnbounded = true
        } else {
            onSearch(query)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Surface(color = colors.toolbar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.onToolbar,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "搜索",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onToolbar,
                    )
                    Text(
                        text = buildString {
                            append("范围：")
                            append(startPath)
                            append(if (recursive) "（含子目录）" else "（仅此目录）")
                            if (!hasRoot) append("  ·  无 Root，部分目录不可读")
                        },
                        style = MetaTextStyle,
                        color = colors.toolbarSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.hasResults || state.status !is SearchStatus.Idle) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "清除结果",
                            tint = colors.onToolbar,
                        )
                    }
                }
            }
        }

        // Query form
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = nameQuery,
                onValueChange = { nameQuery = it },
                label = { Text("文件名包含") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = extension,
                    onValueChange = { extension = it },
                    label = { Text("扩展名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = kind.label,
                    onValueChange = { },
                    label = { Text("类型") },
                    readOnly = true,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CheckRow("包含子目录", recursive) { recursive = it }
                CheckRow("包含隐藏文件", includeHidden) { includeHidden = it }
            }

            Spacer(Modifier.height(6.dp))

            // Category chips, reusing the file-list filter so the two behave identically.
            // The row scrolls horizontally because ten chips do not fit on a narrow phone.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FileFilter.KindFilter.entries.forEach { option ->
                    KindChip(
                        label = option.label,
                        selected = option == kind,
                        onClick = { kind = option },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isRunning) {
                    TextButton(onClick = onCancel) { Text("停止") }
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = describeRunning(state.status),
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Button(onClick = { submit() }) {
                        Text("搜索")
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = describeIdle(state.status),
                        style = MetaTextStyle,
                        color = when (state.status) {
                            is SearchStatus.Failed -> colors.danger
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (state.isTruncated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "结果已达上限（${SearchQuery.MAX_RESULTS} 项），列表不完整。请缩小范围或补充关键词。",
                    style = MetaTextStyle,
                    color = colors.danger,
                )
            }

            if (state.unreadable.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${state.unreadable.size} 个位置无法读取，点击查看",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showUnreadable = true },
                )
            }
        }

        HorizontalDivider(color = colors.divider)

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.isRunning && !state.hasResults -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在搜索…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.width(160.dp))
                }

                !state.hasResults -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (state.status) {
                            is SearchStatus.Idle -> "输入关键词后开始搜索"
                            is SearchStatus.Cancelled -> "已停止，没有匹配结果"
                            is SearchStatus.Failed -> "搜索失败"
                            else -> "没有匹配的文件"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = state.results, key = { it.path }) { entry ->
                        SearchResultRow(
                            entry = entry,
                            onClick = { onOpenResult(entry) },
                            onReveal = { onRevealInPane(entry) },
                        )
                    }
                }
            }
        }
    }

    if (confirmUnbounded) {
        AlertDialog(
            onDismissRequest = { confirmUnbounded = false },
            title = { Text("搜索范围过大", style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    text = "没有填写文件名、扩展名，也没有选择类型，递归搜索会遍历 " +
                        "$startPath 下的全部文件，可能耗时很久并产生大量结果。\n\n" +
                        "确定要继续吗？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnbounded = false
                    onSearch(
                        SearchQuery(
                            nameQuery = "",
                            rootPath = startPath,
                            recursive = true,
                            includeHidden = includeHidden,
                            kind = FileFilter.KindFilter.ALL,
                            extension = "",
                        ),
                    )
                }) {
                    Text("继续搜索", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnbounded = false }) { Text("取消") }
            },
        )
    }

    if (showUnreadable) {
        AlertDialog(
            onDismissRequest = { showUnreadable = false },
            title = { Text("无法读取的位置", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "这些路径在搜索时无法读取，因此结果可能不完整：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (path in state.unreadable) {
                        Text(
                            text = path,
                            style = MonospaceTextStyle,
                            color = colors.danger,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    if (!hasRoot) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "授予 Root 权限后可以读取这些系统目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUnreadable = false }) { Text("关闭") }
            },
        )
    }
}

private fun describeRunning(status: SearchStatus): String = when (status) {
    is SearchStatus.Running -> "已扫描 ${status.scanned} 项，匹配 ${status.matches} 项"
    else -> "搜索中…"
}

private fun describeIdle(status: SearchStatus): String = when (status) {
    is SearchStatus.Idle -> ""
    is SearchStatus.Finished -> buildString {
        append("找到 ${status.matches} 项，扫描 ${status.scanned} 项")
        if (status.truncated) append("（已截断）")
    }
    is SearchStatus.Cancelled -> "已停止：扫描 ${status.scanned} 项，保留 ${status.matches} 项结果"
    is SearchStatus.Failed -> status.reason
    is SearchStatus.Running -> describeRunning(status)
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * One result row.
 *
 * Shows the full path, because a search result is only useful if you can tell *where* it is —
 * two files can share a name in different directories.
 */
@Composable
private fun SearchResultRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onReveal: () -> Unit,
) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) {
                Icons.Filled.Folder
            } else {
                Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.isDirectory) colors.folderIcon else colors.fileIcon,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.path,
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            entry.sizeBytes?.let {
                Text(
                    text = Formatter.size(it),
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReveal) {
                Text("定位", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Warning banner shown when a search would be better with root. */
@Composable
fun SearchNoRootNotice(onRequestRoot: () -> Unit) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bannerBackground)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.bannerText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "没有 Root 权限，/data、/system 等目录不会被搜索到。",
            style = MetaTextStyle,
            color = colors.bannerText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestRoot) { Text("授权", style = MaterialTheme.typography.labelMedium) }
    }
}
