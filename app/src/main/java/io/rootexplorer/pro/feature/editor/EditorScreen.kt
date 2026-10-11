package io.rootexplorer.pro.feature.editor

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The text editor screen.
 *
 * Formatting is deliberately absent: a plain-text editor that silently reformats is a source of
 * damage rather than a feature. What the screen does provide is the information needed to edit
 * safely — the encoding, the line endings, whether the buffer differs from disk, and a refusal
 * with a reason when the file is not text.
 *
 * Line numbers are shown in the status bar rather than as a synchronised gutter. A real gutter
 * needs the editor's scroll offset and line wrapping widths, which the plain text field this
 * screen uses does not expose; a number that drifts out of sync would be worse than none.
 */
@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onTextChange: (String) -> Unit,
    onFindQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onReplaceAll: () -> Unit,
    onRevert: () -> Unit,
    onSave: () -> Unit,
    onConsumeMessage: () -> Unit,
    onConsumeError: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors
    var findOpen by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Leaving with unsaved changes must be a decision, not an accident.
    fun requestBack() {
        if (state.isDirty) confirmDiscard = true else onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(
            state = state,
            onBack = { requestBack() },
            onSave = onSave,
            onRevert = onRevert,
            onToggleFind = { findOpen = !findOpen },
        )

        if (findOpen) {
            FindReplaceBar(
                state = state,
                onFindQueryChange = onFindQueryChange,
                onReplaceQueryChange = onReplaceQueryChange,
                onCaseSensitiveChange = onCaseSensitiveChange,
                onReplaceAll = onReplaceAll,
                onClose = { findOpen = false },
            )
        }

        HorizontalDivider(color = colors.divider)

        Box(modifier = Modifier.weight(1f)) {
            when (val content = state.content) {
                is EditorContent.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                is EditorContent.Blocked -> BlockedNotice(
                    headline = content.headline,
                    detail = content.detail,
                    onBack = onBack,
                )

                is EditorContent.Failed -> BlockedNotice(
                    headline = "无法打开",
                    detail = content.reason,
                    onBack = onBack,
                    onRetry = onRetryLoad,
                )

                is EditorContent.Ready -> EditorBody(
                    text = state.text,
                    onTextChange = onTextChange,
                )
            }
        }

        EditorStatusBar(state = state)

        state.lastSaveMessage?.let { message ->
            EditorNotice(
                text = message,
                isError = false,
                onDismiss = onConsumeMessage,
            )
        }

        state.errorMessage?.let { message ->
            EditorNotice(
                text = message,
                isError = true,
                onDismiss = onConsumeError,
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("尚未保存", style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    text = "当前内容与磁盘上的文件不一致，直接返回会丢失修改。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { onSave(); confirmDiscard = false }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDiscard = false; onBack() }) {
                        Text("放弃修改", color = colors.danger)
                    }
                    TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") }
                }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    onToggleFind: () -> Unit,
) {
    val colors = FileManagerTheme.colors
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
                    text = buildString {
                        append(state.path.substringAfterLast('/'))
                        if (state.isDirty) append("  •")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onToolbar,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.path,
                    style = MetaTextStyle,
                    color = colors.toolbarSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleFind) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "查找替换",
                    tint = colors.onToolbar,
                )
            }
            IconButton(onClick = onRevert, enabled = state.isDirty) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "还原",
                    tint = if (state.isDirty) colors.onToolbar else colors.toolbarSecondary,
                )
            }
            IconButton(onClick = onSave, enabled = state.isDirty && !state.isSaving) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "保存",
                        tint = if (state.isDirty) colors.onToolbar else colors.toolbarSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindReplaceBar(
    state: EditorUiState,
    onFindQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onCaseSensitiveChange: (Boolean) -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.findQuery,
                    onValueChange = onFindQueryChange,
                    label = { Text("查找") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.findQuery.isEmpty()) "" else "${state.matchCount} 处",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.replaceQuery,
                    onValueChange = onReplaceQueryChange,
                    label = { Text("替换为") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onReplaceAll,
                    enabled = state.findQuery.isNotEmpty(),
                ) {
                    Text("全部替换")
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = state.caseSensitive,
                    onCheckedChange = onCaseSensitiveChange,
                )
                Text("区分大小写", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "替换文本按字面插入，$ 与反斜杠不会被解释",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The editing surface.
 *
 * `BasicTextField` rather than the Material wrapper: this is a code and text file editor, so a
 * flat monospace surface without placeholder or label chrome is the correct presentation, and
 * it keeps selection and IME behaviour entirely to the platform.
 */
@Composable
private fun EditorBody(
    text: String,
    onTextChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EditorStatusBar(state: EditorUiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            HorizontalDivider(color = FileManagerTheme.colors.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("行数：${state.lineCount}")
                        append("  字符：${state.text.length}")
                    },
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = buildString {
                        append(state.encoding)
                        append("  ·  ")
                        append(
                            when (state.lineEnding) {
                                io.rootexplorer.pro.core.text.LineEnding.LF -> "LF"
                                io.rootexplorer.pro.core.text.LineEnding.CRLF -> "CRLF"
                                io.rootexplorer.pro.core.text.LineEnding.CR -> "CR"
                            },
                        )
                        if (state.document?.hadBom == true) append("  ·  BOM")
                        if (state.document?.usedFallbackEncoding == true) append("  ·  回退编码")
                    },
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.document?.let { document ->
                if (document.usedFallbackEncoding) {
                    Text(
                        text = "该文件不是 UTF-8，已按 GB18030 解码；保存时会以同一编码写回。",
                        style = MetaTextStyle,
                        color = FileManagerTheme.colors.bannerText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(FileManagerTheme.colors.bannerBackground)
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorNotice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isError) colors.bannerBackground else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isError) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MetaTextStyle,
            color = if (isError) colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text("点击关闭", style = MetaTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shown instead of the editor when the file cannot be edited as text.
 *
 * The reason is stated in full and no editing affordance is offered, because an editor that
 * opens a binary file and then saves it is a data-loss tool.
 */
@Composable
private fun BlockedNotice(
    headline: String,
    detail: String,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val colors = FileManagerTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(24.dp)
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onRetry?.let { retry ->
                    TextButton(onClick = retry) { Text("重试") }
                }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}
