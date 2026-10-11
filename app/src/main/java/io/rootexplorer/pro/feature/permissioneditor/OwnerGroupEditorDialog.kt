package io.rootexplorer.pro.feature.permissioneditor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * Owner and group editor.
 *
 * Accepts either a name or a numeric id, because both are valid to `chown` and a device may
 * report either. Names are resolved through the device's own account database so the dialog can
 * show the resulting uid/gid — and so an unresolvable name is reported instead of being
 * silently treated as root.
 *
 * [lookupUid]/[lookupGid] are suspend because resolution goes through the root shell; they
 * return null when the device has no such account, which the dialog states plainly.
 */
@Composable
fun OwnerGroupEditorDialog(
    entry: FileEntry,
    hasRoot: Boolean,
    initialOwner: String?,
    initialGroup: String?,
    validateSpec: (String) -> String?,
    lookupUid: suspend (String) -> Int?,
    lookupGid: suspend (String) -> Int?,
    onRequestRoot: () -> Unit,
    onApply: (owner: String, group: String?, recursive: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var owner by remember(entry.path) { mutableStateOf(initialOwner.orEmpty()) }
    var group by remember(entry.path) { mutableStateOf(initialGroup.orEmpty()) }
    var recursive by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var resolvedUid by remember { mutableStateOf<Int?>(null) }
    var resolvedGid by remember { mutableStateOf<Int?>(null) }

    val ownerError = owner.takeIf { it.isNotBlank() }?.let(validateSpec)
    val groupError = group.takeIf { it.isNotBlank() }?.let(validateSpec)

    // Resolve as the user types, debounced by the composition itself: the effect re-runs only
    // when the text actually changes to a valid specifier.
    LaunchedEffect(owner, group, hasRoot) {
        if (!hasRoot) {
            resolvedUid = null
            resolvedGid = null
            return@LaunchedEffect
        }
        resolving = true
        resolvedUid = owner.takeIf { it.isNotBlank() && validateSpec(it) == null }?.let { lookupUid(it) }
        resolvedGid = group.takeIf { it.isNotBlank() && validateSpec(it) == null }?.let { lookupGid(it) }
        resolving = false
    }

    val canApply = hasRoot &&
        owner.isNotBlank() &&
        ownerError == null &&
        groupError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("所有者与用户组", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = entry.path,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "当前：${Formatter.name(entry.ownerName)}:${Formatter.name(entry.groupName)}" +
                        "  (uid ${Formatter.id(entry.uid)} / gid ${Formatter.id(entry.gid)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text("所有者（用户名或 UID）") },
                    singleLine = true,
                    isError = ownerError != null,
                    supportingText = {
                        Text(
                            ownerError
                                ?: resolvedUid?.let { "解析为 UID $it" }
                                ?: owner.takeIf { it.isNotBlank() }?.let { "无法解析该用户名（设备上不存在？）" }
                                ?: "例如 root 或 0",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("用户组（组名或 GID，可留空）") },
                    singleLine = true,
                    isError = groupError != null,
                    supportingText = {
                        Text(
                            groupError
                                ?: resolvedGid?.let { "解析为 GID $it" }
                                ?: group.takeIf { it.isNotBlank() }?.let { "无法解析该组名" }
                                ?: "留空表示只改所有者",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (resolving) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(14.dp).height(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在解析…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = recursive, onCheckedChange = { recursive = it })
                    Column {
                        Text("递归应用到子项", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "chown -R 会遍历整个目录树，对大量文件耗时较长。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!hasRoot) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "修改所有者需要 Root 权限，chown 无法执行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = FileManagerTheme.colors.danger,
                    )
                }
            }
        },
        confirmButton = {
            if (hasRoot) {
                TextButton(
                    onClick = { onApply(owner.trim(), group.trim().takeIf { it.isNotEmpty() }, recursive) },
                    enabled = canApply,
                ) {
                    Text("应用")
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
