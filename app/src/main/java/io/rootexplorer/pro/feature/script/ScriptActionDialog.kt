package io.rootexplorer.pro.feature.script

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.script.ScriptEnvironment
import io.rootexplorer.pro.core.script.ScriptOutcome
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import kotlinx.coroutines.launch

/**
 * What to do with a shell script: run it, edit it, or change how it would run.
 *
 * The two decisions are independent and are shown separately, because they are: *where* the script
 * runs (the system environment or the app's packaged one) has nothing to do with *as whom* it runs
 * (the app's own identity or root). Collapsing them into one list would imply a choice that does
 * not exist.
 *
 * 执行 runs the script and then shows what it printed and what it exited with, in the same dialog.
 * The warning is not decoration: it appears exactly while root execution is armed, which is the
 * state in which a script can do anything on the device.
 */
@Composable
fun ScriptActionDialog(
    entry: FileEntry,
    onRun: suspend (FileEntry, ScriptEnvironment, Boolean) -> ScriptOutcome,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FileManagerTheme.colors
    var environment by remember(entry.path) { mutableStateOf(ScriptEnvironment.SYSTEM) }
    // Armed by default, matching the reference dialog. This is a root file manager, and running a
    // script as root still takes a second, deliberate action (执行) after the warning is on screen.
    var useRoot by remember(entry.path) { mutableStateOf(true) }
    var showSettings by remember(entry.path) { mutableStateOf(false) }
    var running by remember(entry.path) { mutableStateOf(false) }
    var outcome by remember(entry.path) { mutableStateOf<ScriptOutcome?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(entry.name, style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("请选择要执行的操作", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.path,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (useRoot) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "您已勾选使用 ROOT 权限执行，请确保脚本中不含有危险代码！",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                    )
                }

                AnimatedVisibility(visible = showSettings) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        ScriptEnvironment.entries.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { environment = option },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = environment == option,
                                    onClick = { environment = option },
                                )
                                Column {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = option.description,
                                        style = MetaTextStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = colors.divider)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { useRoot = !useRoot }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = useRoot, onCheckedChange = { useRoot = it })
                            Text(
                                text = "使用 ROOT 权限执行",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                if (running) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在执行…", style = MetaTextStyle)
                    }
                }

                outcome?.let { result ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = colors.divider)
                    Spacer(Modifier.height(8.dp))
                    ScriptOutcomeView(result)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running,
                onClick = {
                    outcome = null
                    running = true
                    scope.launch {
                        outcome = onRun(entry, environment, useRoot)
                        running = false
                    }
                },
            ) {
                Text("执行")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showSettings = !showSettings }) { Text("设置") }
                TextButton(enabled = !running, onClick = onEdit) { Text("编辑") }
            }
        },
    )
}

/** What the script did, in the terms a user thinks in: who ran it, how it ended, what it said. */
@Composable
private fun ScriptOutcomeView(outcome: ScriptOutcome) {
    val colors = FileManagerTheme.colors
    when (outcome) {
        is ScriptOutcome.Failed -> Text(
            text = outcome.reason,
            style = MetaTextStyle,
            color = colors.danger,
        )

        is ScriptOutcome.Finished -> Column {
            Text(
                text = buildString {
                    append(if (outcome.usedRoot) "以 ROOT 权限执行" else "以应用自身身份执行")
                    append(" · 退出码 ")
                    append(outcome.exitCode?.toString() ?: "未知")
                    if (outcome.truncated) append("（输出已截断）")
                },
                style = MetaTextStyle,
                color = if (outcome.exitCode == 0) colors.symlinkIcon else colors.danger,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = outcome.output.ifBlank { "(没有输出)" },
                style = MonospaceTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
