package io.rootexplorer.pro.ui.components

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileFilter
import io.rootexplorer.pro.core.filesystem.FileSortField
import io.rootexplorer.pro.core.filesystem.SortDirection
import io.rootexplorer.pro.core.permissions.LinuxPermission
import io.rootexplorer.pro.feature.permissioneditor.OctalParse
import io.rootexplorer.pro.feature.permissioneditor.PermissionMatrix
import io.rootexplorer.pro.feature.permissioneditor.parseOctal
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The file action menu, shown after a long press.
 *
 * Actions that are not implemented are rendered disabled with the reason beside them, so the
 * user is never told something succeeded when nothing happened.
 */
@Composable
fun FileActionDialog(
    entry: FileEntry,
    selectionCount: Int,
    onDismiss: () -> Unit,
    onProperties: () -> Unit,
    onPermission: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onBookmark: () -> Unit,
    onSelectAll: () -> Unit,
    onInvertSelection: () -> Unit,
    onSetHome: () -> Unit,
    onHash: () -> Unit,
    onCompress: () -> Unit,
    onExtract: () -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val colors = FileManagerTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (entry.isDirectory) "文件夹" else Formatter.size(entry.sizeBytes))
                        append("  ·  ")
                        append(Formatter.listDate(entry.lastModifiedMillis))
                        if (selectionCount > 1) append("  ·  已选 $selectionCount 项")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ActionRow(Icons.Filled.Info, "属性", enabled = true, onClick = onProperties)
                ActionRow(Icons.Filled.Lock, "权限", enabled = true, onClick = onPermission)
                ActionRow(Icons.Filled.Edit, "重命名", enabled = true, note = null, onClick = onRename)
                ActionRow(Icons.Filled.ContentCopy, "复制路径", enabled = true, onClick = onCopyPath)
                ActionRow(Icons.Filled.Star, "添加书签", enabled = true, onClick = onBookmark)
                ActionRow(Icons.Filled.Home, "设为首页", enabled = true, onClick = onSetHome)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                ActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = "复制到另一栏",
                    enabled = true,
                    onClick = onCopy,
                )
                ActionRow(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    label = "移动到另一栏",
                    enabled = true,
                    onClick = onMove,
                )
                ActionRow(
                    icon = Icons.Filled.Delete,
                    label = "删除",
                    enabled = true,
                    danger = true,
                    onClick = onDelete,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                ActionRow(Icons.Filled.SelectAll, "全选", enabled = true, onClick = onSelectAll)
                ActionRow(Icons.Filled.SelectAll, "反选", enabled = true, onClick = onInvertSelection)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Implemented: verify the real contents.
                ActionRow(
                    icon = Icons.Filled.Fingerprint,
                    label = "校验值（MD5/SHA-1/SHA-256）",
                    enabled = !entry.isDirectory,
                    note = if (entry.isDirectory) "目录不支持" else null,
                    onClick = onHash,
                )

                // Implemented: archive. Extract only when the entry really is one.
                ActionRow(
                    icon = Icons.Filled.Archive,
                    label = "压缩",
                    enabled = true,
                    onClick = onCompress,
                )
                val archiveFormat = io.rootexplorer.pro.core.archive.ArchiveFormat.fromName(entry.name)
                ActionRow(
                    icon = Icons.Filled.Unarchive,
                    label = "解压",
                    enabled = archiveFormat?.canExtract == true,
                    note = when {
                        archiveFormat == null -> "不是压缩包"
                        !archiveFormat.canExtract -> "${archiveFormat.label} 暂不支持"
                        else -> archiveFormat.label
                    },
                    onClick = onExtract,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // Not implemented yet — disabled, with the stage that will deliver them.
                ActionRow(Icons.Filled.ContentCopy, "分享", enabled = false, note = "P3", onClick = {})
                ActionRow(Icons.Filled.Info, "打开方式", enabled = false, note = "P3", onClick = {})

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "灰显项尚未实现，点击不会执行任何操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    note: String? = null,
    danger: Boolean = false,
) {
    val colors = FileManagerTheme.colors
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        danger -> colors.danger
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The permission editor.
 *
 * The checkboxes and the octal text field edit the same [LinuxPermission] value, so the
 * string, the number and the boxes always agree. Applying is only enabled when root is
 * available, because without it `chmod` cannot run — the dialog says so rather than closing
 * with a success message.
 */
@Composable
fun PermissionEditorDialog(
    entry: FileEntry,
    hasRoot: Boolean,
    onDismiss: () -> Unit,
    onRequestRoot: () -> Unit,
    onApply: (LinuxPermission, recursive: Boolean) -> Unit,
    onEditOwner: () -> Unit,
    busy: Boolean = false,
) {
    var permission by remember(entry.path) {
        mutableStateOf(entry.permission ?: LinuxPermission.fromOctal("644")!!)
    }
    // Seeded and kept in sync with the four-digit form, which is what actually gets applied.
    // The three-digit `octalSimple` masks setuid/setgid/sticky, so seeding with it meant opening
    // this dialog on a 4755 file showed "755" and accepting that text silently cleared the bits.
    var octalText by remember(entry.path) { mutableStateOf(permission.octal) }
    var octalError by remember(entry.path) { mutableStateOf<String?>(null) }
    var recursive by remember(entry.path) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改权限", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = entry.path,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))

                PermissionMatrix(
                    permission = permission,
                    onPermissionChange = { updated ->
                        permission = updated
                        octalText = updated.octal
                        octalError = null
                    },
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = octalText,
                    onValueChange = { text ->
                        octalText = text
                        when (val parsed = parseOctal(text)) {
                            is OctalParse.Valid -> {
                                permission = parsed.permission
                                octalError = null
                            }
                            is OctalParse.Invalid -> octalError = parsed.reason
                        }
                    },
                    label = { Text("八进制权限") },
                    singleLine = true,
                    isError = octalError != null,
                    supportingText = {
                        Text(octalError ?: "例如 644、755、600、777")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (entry.isDirectory) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = recursive, onCheckedChange = { recursive = it })
                        Column {
                            Text("递归应用到目录内容", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "chmod -R 会遍历整棵目录树，对大量文件耗时较长。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onEditOwner, enabled = hasRoot) {
                    Text("修改所有者与用户组")
                }

                if (!hasRoot) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "当前没有 Root 权限，chmod 无法执行。请先授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = FileManagerTheme.colors.danger,
                    )
                }
            }
        },
        confirmButton = {
            if (hasRoot) {
                TextButton(
                    onClick = { onApply(permission, recursive) },
                    enabled = octalError == null && !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp))
                    } else {
                        Text("确定")
                    }
                }
            } else {
                TextButton(onClick = onRequestRoot) { Text("授权 Root") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * A single-field input dialog used for create, rename and path entry.
 *
 * [warning] is shown in the accent colour, used here to remind the user not to lose a file
 * extension when renaming — the brief asks for exactly that.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    warning: String? = null,
    monospace: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    val trimmed = text.trim()
    val invalid = trimmed.isEmpty() || trimmed.contains('/')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    textStyle = if (monospace) MonospaceTextStyle else MaterialTheme.typography.bodyLarge,
                    isError = trimmed.isNotEmpty() && trimmed.contains('/'),
                    supportingText = {
                        when {
                            trimmed.contains('/') -> Text("名称不能包含 /")
                            warning != null -> Text(warning)
                            else -> Text("")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = !invalid) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Delete confirmation.
 *
 * Offers the recycle bin first, as the brief requires, with permanent deletion as a clearly
 * labelled alternative. A non-empty directory cannot be permanently removed without agreeing
 * to a recursive delete, so that confirmation is a checkbox rather than a second dialog.
 */
@Composable
fun DeleteConfirmDialog(
    entry: FileEntry,
    /** True when the trash is on a different volume, where a move would be a slow copy. */
    trashCrossVolume: Boolean = false,
    onDismiss: () -> Unit,
    onTrash: () -> Unit,
    onPermanentDelete: (Boolean) -> Unit,
) {
    var recursive by remember { mutableStateOf(false) }
    var permanentArmed by remember { mutableStateOf(false) }
    val needsRecursive = entry.isDirectory

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    text = "目标：${entry.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = entry.path,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))

                Text(
                    text = if (trashCrossVolume) {
                        "移入回收站：当前没有 Root 权限，回收站位于应用私有目录，跨存储卷的项目无法移入。"
                    } else {
                        "移入回收站：之后可以在回收站中还原，或永久删除。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = permanentArmed,
                        onCheckedChange = { permanentArmed = it },
                    )
                    Text(
                        text = "永久删除（不可恢复）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FileManagerTheme.colors.danger,
                    )
                }

                if (needsRecursive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = recursive, onCheckedChange = { recursive = it })
                        Text(
                            text = "递归删除目录及其全部内容",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        text = "不勾选时只能删除空目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (permanentArmed) {
                TextButton(
                    onClick = { onPermanentDelete(recursive) },
                    enabled = !needsRecursive || recursive,
                ) {
                    Text("永久删除", color = FileManagerTheme.colors.danger)
                }
            } else {
                TextButton(onClick = onTrash) {
                    Text("移入回收站")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Extra confirmation for a change inside a sensitive location.
 *
 * The brief requires a clear risk warning before touching system directories, ownership and
 * permissions. This dialog states the path, why it is sensitive and what the app will *not* do
 * (remount a read-only partition), so accepting it is an informed decision.
 */
@Composable
fun SensitiveChangeDialog(
    warning: io.rootexplorer.pro.core.permissions.SensitivePath,
    targetPath: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FileManagerTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "敏感位置，请确认",
                style = MaterialTheme.typography.titleSmall,
                color = colors.danger,
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("目标", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = targetPath, style = MonospaceTextStyle)

                Spacer(Modifier.height(8.dp))
                Text("风险", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = warning.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.danger,
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildString {
                        append("该路径位于 ").append(warning.path).append("。")
                        append("如果所在分区是只读挂载，修改会失败——应用不会尝试重新挂载系统分区。")
                        append("错误的权限或所有者可能让设备无法启动、无法接收更新，或让某个系统服务失效。")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("我了解风险，仍然修改", color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Sort field and direction picker.
 */
@Composable
fun SortDialog(
    field: FileSortField,
    direction: SortDirection,
    onFieldSelected: (FileSortField) -> Unit,
    onToggleDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排序方式", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                FileSortField.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFieldSelected(option) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option == field,
                            onClick = { onFieldSelected(option) },
                        )
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleDirection() }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "方向：${direction.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "点击切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "文件夹始终排在文件之前；大小或时间未知的条目排在最后。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

/** Filter dialog: name substring, extension, kind, and the hidden-files switch. */
@Composable
fun FilterDialog(
    filter: FileFilter,
    showHidden: Boolean,
    onApply: (FileFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var nameQuery by remember { mutableStateOf(filter.nameQuery) }
    var extension by remember { mutableStateOf(filter.extension) }
    var kind by remember { mutableStateOf(filter.kind) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("过滤", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = nameQuery,
                    onValueChange = { nameQuery = it },
                    label = { Text("文件名包含") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = extension,
                    onValueChange = { extension = it },
                    label = { Text("扩展名（不含点）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("类型", style = MaterialTheme.typography.labelMedium)
                FileFilter.KindFilter.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { kind = option }
                            .padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == kind, onClick = { kind = option })
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (showHidden) {
                        "当前显示隐藏文件，可在顶栏更多菜单中关闭。"
                    } else {
                        "当前隐藏以 . 开头的文件，可在顶栏更多菜单中开启显示。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        FileFilter(
                            nameQuery = nameQuery,
                            extension = extension,
                            kind = kind,
                        ),
                    )
                },
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("清除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
