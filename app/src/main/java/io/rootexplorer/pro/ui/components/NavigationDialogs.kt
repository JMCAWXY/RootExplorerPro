package io.rootexplorer.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle

/**
 * The path navigator.
 *
 * The brief asks the path bar to support jumping by path node, copying the path and typing a
 * path. All three are here: the breadcrumb row jumps to any ancestor, the field accepts a
 * typed path (with validation before navigating), and a copy button puts the current path on
 * the clipboard.
 *
 * Breadcrumb segments are built from the real path, so a file name containing a slash-like
 * character cannot forge a segment.
 */
@Composable
fun PathNavigatorDialog(
    currentPath: String,
    onNavigate: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(currentPath) { mutableStateOf(currentPath) }
    val normalised = FsEngine.normalise(text)
    val problem = validatePath(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("路径", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                // Breadcrumb: / > data > adb
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Crumb(
                        label = "/",
                        onClick = { onNavigate("/") },
                    )
                    segmentsOf(currentPath).forEach { (label, path) ->
                        Text(
                            text = "›",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        Crumb(label = label, onClick = { onNavigate(path) })
                    }
                }

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("输入路径") },
                    singleLine = true,
                    isError = problem != null,
                    textStyle = MonospaceTextStyle,
                    supportingText = {
                        Text(problem ?: normalised)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onCopyPath(currentPath) }) {
                        Text("复制当前路径")
                    }
                    TextButton(onClick = { text = currentPath }) {
                        Text("重置")
                    }
                }

                Text(
                    text = "提示：路径区分大小写。以 / 开头表示绝对路径；包含空格和中文的路径可以直接粘贴。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onNavigate(normalised) },
                enabled = problem == null && normalised.isNotBlank(),
            ) {
                Text("跳转")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * Reject input that can never be a valid path, before the user navigates into a failure.
 *
 * A NUL byte is the only character Linux forbids in a path, so that is the only hard rule;
 * everything else is accepted because file names really can contain almost anything.
 */
private fun validatePath(input: String): String? {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() -> "请输入路径"
        trimmed.contains('\u0000') -> "路径不能包含空字符"
        else -> null
    }
}

/** "a/b/c" becomes [("a", "/a"), ("b", "/a/b"), ("c", "/a/b/c")]. */
private fun segmentsOf(path: String): List<Pair<String, String>> {
    val normalised = FsEngine.normalise(path)
    if (normalised == "/") return emptyList()
    val parts = normalised.trimStart('/').split('/').filter { it.isNotEmpty() }
    val result = mutableListOf<Pair<String, String>>()
    var accumulated = ""
    for (part in parts) {
        accumulated = "$accumulated/$part"
        result += part to accumulated
    }
    return result
}

@Composable
private fun Crumb(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * The overflow menu.
 *
 * Every entry here is wired to something real; the ones that are not implemented say so and
 * are disabled, matching the P0 policy that no control should look functional while doing
 * nothing.
 */
@Composable
fun MoreMenuDialog(
    showHidden: Boolean,
    dualPane: Boolean,
    filterActive: Boolean,
    isBookmarked: Boolean,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    onSelectAll: () -> Unit,
    onFilter: () -> Unit,
    onSort: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleBookmark: () -> Unit,
    onSetHome: () -> Unit,
    onSwapPanes: () -> Unit,
    onToggleDualPane: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTerminal: () -> Unit,
    onUnavailable: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更多操作", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                MenuRow(Icons.Filled.Refresh, "刷新", enabled = true, onClick = onRefresh)
                MenuRow(Icons.Filled.Search, "搜索", enabled = true, onClick = onSearch)
                MenuRow(Icons.Filled.SelectAll, "全选", enabled = true, onClick = onSelectAll)
                MenuRow(
                    Icons.Filled.FilterList,
                    if (filterActive) "过滤（已启用）" else "过滤",
                    enabled = true,
                    onClick = onFilter,
                )
                MenuRow(Icons.AutoMirrored.Filled.Sort, "排序方式", enabled = true, onClick = onSort)
                MenuRow(
                    Icons.Filled.Visibility,
                    if (showHidden) "隐藏隐藏文件" else "显示隐藏文件",
                    enabled = true,
                    onClick = onToggleHidden,
                )
                MenuRow(
                    Icons.Filled.Star,
                    if (isBookmarked) "移除书签" else "添加书签",
                    enabled = true,
                    onClick = onToggleBookmark,
                )
                MenuRow(Icons.Filled.Home, "设为首页", enabled = true, onClick = onSetHome)
                MenuRow(Icons.Filled.SwapHoriz, "交换窗口", enabled = true, onClick = onSwapPanes)
                MenuRow(
                    Icons.Filled.ViewColumn,
                    if (dualPane) "切换为单栏" else "切换为双栏",
                    enabled = true,
                    onClick = onToggleDualPane,
                )
                MenuRow(Icons.Filled.Delete, "回收站", enabled = true, onClick = onOpenTrash)

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                MenuRow(Icons.Filled.Terminal, "打开终端", enabled = false, note = "P3", onClick = onOpenTerminal)
                MenuRow(Icons.Filled.ContentCopy, "设置", enabled = false, note = "P4", onClick = {})
                MenuRow(Icons.Filled.ContentCopy, "退出", enabled = false, note = "P4", onClick = {})

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "灰显项尚未实现，点击不会执行任何操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh) { Text("刷新") }
        },
    )
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    note: String? = null,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
