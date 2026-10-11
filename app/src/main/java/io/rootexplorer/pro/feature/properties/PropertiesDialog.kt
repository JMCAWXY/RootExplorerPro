package io.rootexplorer.pro.feature.properties

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileTypeDetector
import io.rootexplorer.pro.core.filesystem.StatResult
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The properties dialog.
 *
 * The rows it can fill depend on what the device actually reports. When a field cannot be
 * read the row says 未知 — it is never filled with a plausible default, because a permission
 * dialog that shows the wrong mode is worse than one that admits it does not know.
 *
 * [loadStat] is a suspend call so the dialog can fetch exact attributes (including the
 * numeric owner and the octal mode that only a `stat` call provides) without blocking the
 * frame. [onEditPermission] opens the permission editor for the same path.
 */
@Composable
fun PropertiesDialog(
    entry: FileEntry,
    loadStat: suspend (String) -> StatResult,
    onEditPermission: (FileEntry) -> Unit,
    onCopyPath: (String) -> Unit,
    onOpenHash: (FileEntry) -> Unit,
    onOpenEditor: ((FileEntry) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var stat by remember(entry.path) { mutableStateOf<StatResult?>(null) }

    LaunchedEffect(entry.path) {
        stat = loadStat(entry.path)
    }

    val resolved: FileEntry = when (val s = stat) {
        is StatResult.Success -> s.stat.entry
        else -> entry
    }
    val details = (stat as? StatResult.Success)?.stat
    val error = (stat as? StatResult.Failure)?.error

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "属性",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (stat == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在读取属性…", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                PropertyRow("名称", resolved.name)
                PropertyRow("目录", parentOf(resolved.path))
                PropertyRow("完整路径", resolved.path, monospace = true)
                PropertyRow(
                    "类型",
                    buildString {
                        append(FileTypeDetector.detect(resolved).description)
                        append("（").append(kindLabel(resolved)).append("）")
                    },
                )
                PropertyRow("大小", formatSize(resolved))
                PropertyRow("修改时间", Formatter.fullDate(resolved.lastModifiedMillis))
                PropertyRow("访问时间", Formatter.fullDate(details?.lastAccessMillis))
                PropertyRow("创建时间", Formatter.fullDate(details?.creationMillis))

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                PropertyRow(
                    "权限",
                    Formatter.permission(
                        resolved.permission?.display(resolved.kind.typeCharacter),
                        // The four-digit form: the three-digit one masks setuid/setgid/sticky, so
                        // a 4755 file was reported here as "755" — a wrong statement about a
                        // security-relevant attribute.
                        resolved.permission?.octal,
                    ),
                    monospace = true,
                )
                PropertyRow("所有者", Formatter.name(details?.ownerName ?: resolved.ownerName))
                PropertyRow("用户组", Formatter.name(details?.groupName ?: resolved.groupName))
                PropertyRow("UID", Formatter.id(resolved.uid))
                PropertyRow("GID", Formatter.id(resolved.gid))

                if (resolved.isSymlink) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    PropertyRow("符号链接目标", resolved.linkTarget ?: "未知", monospace = true)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                PropertyRow(
                    "状态",
                    buildString {
                        append(if (resolved.isDirectory) "目录" else "文件")
                        if (resolved.isHidden) append("，隐藏")
                        if (resolved.isSymlink) append("，符号链接")
                        if (details?.partial == true) append("（部分属性无法读取）")
                    },
                )

                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "读取完整属性失败：${error.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FileManagerTheme.colors.danger,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (resolved.isDirectory) {
                        "校验（MD5 / SHA-1 / SHA-256）只适用于文件，不适用于目录。"
                    } else {
                        "点击下方「校验」可读取真实文件内容计算 MD5 / SHA-1 / SHA-256。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { onEditPermission(resolved) },
                ) {
                    Text("权限")
                }
                TextButton(
                    onClick = { onOpenHash(resolved) },
                    enabled = !resolved.isDirectory,
                ) {
                    Text("校验")
                }
                onOpenEditor?.let { open ->
                    TextButton(
                        onClick = { open(resolved) },
                        enabled = !resolved.isDirectory,
                    ) {
                        Text("编辑")
                    }
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onCopyPath(resolved.path) }) {
                    Text("复制路径")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

/** One label/value pair. Long values wrap instead of being clipped. */
@Composable
private fun PropertyRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = if (monospace) MonospaceTextStyle else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun parentOf(path: String): String {
    val index = path.lastIndexOf('/')
    return if (index <= 0) "/" else path.substring(0, index)
}

private fun kindLabel(entry: FileEntry): String = when (entry.kind) {
    io.rootexplorer.pro.core.filesystem.FileKind.DIRECTORY -> "目录"
    io.rootexplorer.pro.core.filesystem.FileKind.REGULAR -> "普通文件"
    io.rootexplorer.pro.core.filesystem.FileKind.SYMLINK -> "符号链接"
    io.rootexplorer.pro.core.filesystem.FileKind.BLOCK_DEVICE -> "块设备"
    io.rootexplorer.pro.core.filesystem.FileKind.CHARACTER_DEVICE -> "字符设备"
    io.rootexplorer.pro.core.filesystem.FileKind.FIFO -> "命名管道"
    io.rootexplorer.pro.core.filesystem.FileKind.SOCKET -> "套接字"
    io.rootexplorer.pro.core.filesystem.FileKind.OTHER -> "其他"
}

private fun formatSize(entry: FileEntry): String =
    if (entry.isDirectory) "—（目录）" else Formatter.size(entry.sizeBytes)
