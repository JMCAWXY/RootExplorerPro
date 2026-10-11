package io.rootexplorer.pro.feature.permissioneditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.permissions.LinuxPermission
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle

/**
 * The three-by-three read/write/execute editor plus the special bits.
 *
 * The checkbox state *is* the single source of truth: the symbolic string and the octal
 * number shown underneath are derived from it on every recomposition, so what the user sees
 * and what will be applied cannot drift apart.
 *
 * The special bits (setuid/setgid/sticky) are shown separately and labelled as risky, because
 * setting setuid on an executable is a privilege-escalation decision, not a cosmetic one.
 */
@Composable
fun PermissionMatrix(
    permission: LinuxPermission,
    onPermissionChange: (LinuxPermission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors

    Column(modifier = modifier) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "对象",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp),
            )
            listOf("读", "写", "执行").forEach { label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        PermissionRow(
            label = "所有者",
            read = permission.ownerRead,
            write = permission.ownerWrite,
            execute = permission.ownerExecute,
            onReadChange = { onPermissionChange(permission.copy(ownerRead = it)) },
            onWriteChange = { onPermissionChange(permission.copy(ownerWrite = it)) },
            onExecuteChange = { onPermissionChange(permission.copy(ownerExecute = it)) },
        )
        PermissionRow(
            label = "用户组",
            read = permission.groupRead,
            write = permission.groupWrite,
            execute = permission.groupExecute,
            onReadChange = { onPermissionChange(permission.copy(groupRead = it)) },
            onWriteChange = { onPermissionChange(permission.copy(groupWrite = it)) },
            onExecuteChange = { onPermissionChange(permission.copy(groupExecute = it)) },
        )
        PermissionRow(
            label = "其他用户",
            read = permission.otherRead,
            write = permission.otherWrite,
            execute = permission.otherExecute,
            onReadChange = { onPermissionChange(permission.copy(otherRead = it)) },
            onWriteChange = { onPermissionChange(permission.copy(otherWrite = it)) },
            onExecuteChange = { onPermissionChange(permission.copy(otherExecute = it)) },
        )

        Spacer(Modifier.height(10.dp))

        // Live preview: the exact strings that will be applied.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = "权限：${permission.symbolic}",
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // The four-digit form is the one that gets applied, so it is the one shown.
                    // Previously this led with the masked three-digit value, which hid the very
                    // bits the section above calls risky — a user could read "755" while the
                    // checkboxes said setuid was on.
                    text = "八进制：${permission.octal}",
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "特殊权限位",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))

        SpecialBitRow(
            title = "SUID",
            description = "以文件所有者身份执行。对可执行文件设置会提升权限，风险很高。",
            checked = permission.setUid,
            onCheckedChange = { onPermissionChange(permission.copy(setUid = it)) },
        )
        SpecialBitRow(
            title = "SGID",
            description = "以文件所属组身份执行；对目录设置会让新文件继承该组。",
            checked = permission.setGid,
            onCheckedChange = { onPermissionChange(permission.copy(setGid = it)) },
        )
        SpecialBitRow(
            title = "Sticky",
            description = "目录中只有文件所有者能删除自己的文件（如 /tmp）。",
            checked = permission.sticky,
            onCheckedChange = { onPermissionChange(permission.copy(sticky = it)) },
        )

        if (permission.hasSpecialBits) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "注意：已启用特殊权限位（${permission.octalSimple} 之外的位宽），请在确认用途后再应用。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.danger,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    read: Boolean,
    write: Boolean,
    execute: Boolean,
    onReadChange: (Boolean) -> Unit,
    onWriteChange: (Boolean) -> Unit,
    onExecuteChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(88.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Checkbox(checked = read, onCheckedChange = onReadChange)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Checkbox(checked = write, onCheckedChange = onWriteChange)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Checkbox(checked = execute, onCheckedChange = onExecuteChange)
        }
    }
}

@Composable
private fun SpecialBitRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Parse an octal string typed by the user and report either the parsed value or a reason.
 *
 * Kept next to the editor so the same validation is used by the text field and by the
 * "apply" button.
 */
sealed interface OctalParse {
    data class Valid(val permission: LinuxPermission) : OctalParse
    data class Invalid(val reason: String) : OctalParse
}

/** Validate user-entered octal text. */
fun parseOctal(text: String): OctalParse {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return OctalParse.Invalid("请输入八进制权限，例如 644")
    if (trimmed.length > 4) return OctalParse.Invalid("最多 4 位（含特殊权限位）")
    if (trimmed.any { it !in '0'..'7' }) return OctalParse.Invalid("只能包含 0-7 的数字")
    val permission = LinuxPermission.fromOctal(trimmed)
        ?: return OctalParse.Invalid("无法解析：$trimmed")
    return OctalParse.Valid(permission)
}

/**
 * A remembered editor value so callers do not repeat the parse on every keystroke.
 *
 * Seeded with the four-digit form: seeding with the masked three-digit form meant editing an
 * existing setuid file (4755) started the field at "755", so accepting the pre-filled text
 * silently cleared the special bits.
 */
@Composable
fun rememberOctalInput(initial: LinuxPermission): Pair<String, (String) -> Unit> {
    var text by remember(initial.octal) { mutableStateOf(initial.octal) }
    return text to { text = it }
}
