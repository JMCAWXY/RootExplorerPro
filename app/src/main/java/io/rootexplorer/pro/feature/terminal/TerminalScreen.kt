package io.rootexplorer.pro.feature.terminal

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rootexplorer.pro.core.shell.ShellKind
import io.rootexplorer.pro.core.shell.ShellSession
import io.rootexplorer.pro.core.shell.TerminalLine
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle

/**
 * The terminal emulator screen.
 *
 * It runs a real shell: output is the process's own stdout, and the exit status is the shell's
 * `$?`. A non-zero status is reported rather than left silent, so a failed command never looks
 * like a successful one that happened to print nothing.
 *
 * The shell in use is stated in the header at all times. Entering a root shell is never automatic:
 * it is a choice on this screen, and it is the only place the root prompt is triggered from here.
 */
@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onBack: () -> Unit,
    onSelectShell: (ShellKind) -> Unit,
    onInputChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors
    val listState = rememberLazyListState()
    var confirmRoot by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }

    // Follow the tail as output arrives, which is what a terminal should do.
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) {
            listState.animateScrollToItem(state.transcript.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
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
                        text = "终端 · ${state.kind.label}",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onToolbar,
                    )
                    Text(
                        text = buildString {
                            state.workingDirectory?.let { append(it).append("  ·  ") }
                            append(
                                if (state.kind == ShellKind.ROOT) {
                                    "命令以 root 身份执行"
                                } else {
                                    "命令以应用权限执行（uid 非 0）"
                                },
                            )
                        },
                        style = MetaTextStyle,
                        color = colors.toolbarSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { historyOpen = true }, enabled = state.history.isNotEmpty()) {
                    Text(
                        text = "历史",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.history.isNotEmpty()) colors.onToolbar else colors.toolbarSecondary,
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "清屏",
                        tint = colors.onToolbar,
                    )
                }
            }
        }

        // Shell selector. Both kinds are stated with what they mean, because "Root" on its own
        // does not tell the user that the first command will trigger an authorization prompt.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ShellKind.entries.forEach { kind ->
                val selected = kind == state.kind
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.clickable(enabled = !state.isRunning) {
                        if (kind == ShellKind.ROOT) confirmRoot = true else onSelectShell(kind)
                    },
                ) {
                    Text(
                        text = kind.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.isStarting) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("正在启动…", style = MetaTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Transcript
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF101214)),
        ) {
            if (state.transcript.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "输入命令后按回车执行\n例如：pwd、ls -la、id",
                        style = MetaTextStyle,
                        color = Color(0xFF6E7686),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    items(state.transcript) { line ->
                        Text(
                            text = line.text,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                color = when (line.kind) {
                                    TerminalLine.Kind.COMMAND -> Color(0xFF6FA8FF)
                                    TerminalLine.Kind.NOTICE -> Color(0xFFF0D9A0)
                                    TerminalLine.Kind.OUTPUT -> Color(0xFFD8DEE9)
                                },
                                fontWeight = if (line.kind == TerminalLine.Kind.COMMAND) {
                                    FontWeight.Medium
                                } else {
                                    FontWeight.Normal
                                },
                            ),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        // Suggestions: read-only commands, so a tap cannot change anything by accident.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ShellSession.suggestions.forEach { suggestion ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.clickable(enabled = !state.isRunning) {
                        onInputChange(suggestion)
                    },
                ) {
                    Text(
                        text = suggestion,
                        style = MetaTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }

        if (state.error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bannerBackground)
                    .clickable(onClick = onDismissError)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.error,
                    style = MetaTextStyle,
                    color = colors.danger,
                    modifier = Modifier.weight(1f),
                )
                Text("点击关闭", style = MetaTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider(color = colors.divider)

        // Input row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text("命令") },
                singleLine = true,
                enabled = !state.isRunning,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onRun() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (state.isRunning) {
                TextButton(onClick = onStop) { Text("停止") }
            } else {
                IconButton(onClick = onRun, enabled = state.input.isNotBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "执行",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (confirmRoot) {
        AlertDialog(
            onDismissRequest = { confirmRoot = false },
            title = { Text("进入 Root Shell", style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    text = "Root Shell 中的所有命令都以 root 身份执行，可以修改任何文件。" +
                        "系统会弹出授权请求（KernelSU / Magisk），需要你手动允许。\n\n" +
                        "普通 Shell 不需要授权，只能访问应用本身有权访问的目录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRoot = false
                    onSelectShell(ShellKind.ROOT)
                }) {
                    Text("请求授权并进入")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRoot = false }) { Text("取消") }
            },
        )
    }

    if (historyOpen) {
        AlertDialog(
            onDismissRequest = { historyOpen = false },
            title = { Text("命令历史", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(modifier = Modifier.heightIn(max = 380.dp)) {
                    if (state.history.isEmpty()) {
                        Text("还没有执行过命令", style = MetaTextStyle)
                    } else {
                        LazyColumn {
                            items(state.history) { command ->
                                Text(
                                    text = command,
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onInputChange(command)
                                            historyOpen = false
                                        }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyOpen = false }) { Text("关闭") }
            },
        )
    }
}
