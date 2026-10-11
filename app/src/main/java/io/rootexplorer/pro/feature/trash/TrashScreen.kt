package io.rootexplorer.pro.feature.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.service.trash.TrashEntry
import io.rootexplorer.pro.service.trash.TrashLocation
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The recycle bin screen.
 *
 * Everything here is real: the list is the bin's own index, restore puts a file back at its
 * recorded original path, and permanent delete removes the payload. The storage location is
 * shown because it matters — with root the bin sits on shared storage and trashing is instant;
 * without it the bin lives in the app's private directory.
 */
@Composable
fun TrashScreen(
    entries: List<TrashEntry>,
    location: TrashLocation,
    totalBytes: Long,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRestore: (TrashEntry) -> Unit,
    onDeletePermanently: (TrashEntry) -> Unit,
    onEmpty: (EmptyRule) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors
    var confirmDelete by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var emptyRule by remember { mutableStateOf(EmptyRule.ALL) }

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
                        text = "回收站",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onToolbar,
                    )
                    Text(
                        text = "${entries.size} 项  ·  ${Formatter.size(totalBytes)}",
                        style = MetaTextStyle,
                        color = colors.toolbarSecondary,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = "刷新",
                        tint = colors.onToolbar,
                    )
                }
            }
        }

        // Location note: where the bin physically is, and whether trashing is cheap.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(if (location.viaRoot) "存储位置（共享存储，移动为改名）" else "存储位置（应用私有目录）")
                    append("：")
                    append(location.directory)
                },
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HorizontalDivider(color = colors.divider)

        Box(modifier = Modifier.weight(1f)) {
            when {
                isLoading && entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "回收站是空的",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = entries, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            onRestore = { onRestore(entry) },
                            onDelete = { confirmDelete = entry },
                        )
                    }
                }
            }
        }

        // Actions
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
            Column {
                HorizontalDivider(color = colors.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (entries.isEmpty()) "没有可清理的项目" else "清理会永久删除，无法恢复",
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { confirmEmpty = true },
                        enabled = entries.isNotEmpty(),
                    ) {
                        Text("清空回收站")
                    }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("永久删除", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(
                        text = "将永久删除：${target.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = target.originalPath,
                        style = MonospaceTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "此操作无法撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePermanently(target)
                    confirmDelete = null
                }) {
                    Text("永久删除", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("清空回收站", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(
                        text = "选择清理范围。删除后无法恢复；未能删除的项目会保留在列表中。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    EmptyRule.entries.forEach { rule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { emptyRule = rule }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = emptyRule == rule, onClick = { emptyRule = rule })
                            Column {
                                Text(rule.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = rule.description,
                                    style = MetaTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onEmpty(emptyRule)
                    confirmEmpty = false
                }) {
                    Text("清空", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text("取消") }
            },
        )
    }
}

/** Cleanup rules the brief asks for: everything, or by age, or by size. */
enum class EmptyRule(val label: String, val description: String) {
    ALL("全部", "删除回收站中的所有项目"),
    OLDER_THAN_7_DAYS("仅 7 天前", "只删除删除时间早于 7 天的项目"),
    LARGER_THAN_10_MB("仅大于 10 MB", "只删除单个大于 10 MB 的项目"),
    ;

    val olderThanMillis: Long?
        get() = if (this == OLDER_THAN_7_DAYS) 7L * 24 * 60 * 60 * 1000 else null

    val largerThanBytes: Long?
        get() = if (this == LARGER_THAN_10_MB) 10L * 1024 * 1024 else null
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = FileManagerTheme.colors.folderIcon,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(Formatter.listDate(entry.deletedAtMillis))
                    append(" 删除")
                    entry.sizeBytes?.let {
                        append("  ·  ")
                        append(Formatter.size(it))
                    }
                    append("  ·  原位置 ")
                    append(entry.originalPath)
                },
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(
                imageVector = Icons.Filled.Restore,
                contentDescription = "还原",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.DeleteForever,
                contentDescription = "永久删除",
                tint = FileManagerTheme.colors.danger,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Warning row explaining that the bin only works well with root. */
@Composable
fun TrashNoRootNotice(onRequestRoot: () -> Unit) {
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
            text = "没有 Root 权限时，回收站位于应用私有目录，跨存储卷的项目无法移入。",
            style = MetaTextStyle,
            color = colors.bannerText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRequestRoot) { Text("授权", style = MaterialTheme.typography.labelMedium) }
    }
}
