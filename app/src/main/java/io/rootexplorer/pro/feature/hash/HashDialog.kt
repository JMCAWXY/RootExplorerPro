package io.rootexplorer.pro.feature.hash

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.crypto.HashAlgorithm
import io.rootexplorer.pro.core.crypto.HashVerification
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.ui.theme.MonospaceTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * The checksum dialog.
 *
 * It reads the real file contents through [HashViewModel], shows progress for large files with
 * a working stop button, and lets the user compare the result against a checksum they paste in.
 * MD5 and SHA-1 are labelled as unsuitable for security so the tool does not imply more than it
 * provides.
 */
@Composable
fun HashDialog(
    entryPath: String,
    entryName: String,
    fileSizeBytes: Long,
    state: HashUiState,
    onStart: (String, Long) -> Unit,
    onCancel: () -> Unit,
    onPasteChange: (String) -> Unit,
    onVerifyAgainst: (HashAlgorithm) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = FileManagerTheme.colors
    var copiedAlgorithm by remember { mutableStateOf<HashAlgorithm?>(null) }

    // Compute as soon as the dialog opens: the user asked for a checksum, not for a button.
    LaunchedEffect(entryPath) {
        onStart(entryPath, fileSizeBytes)
    }

    AlertDialog(
        onDismissRequest = {
            onCancel()
            onDismiss()
        },
        title = {
            Column {
                Text("校验值", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = entryName,
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = entryPath,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "文件大小：${Formatter.size(fileSizeBytes)}",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.isRunning) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildString {
                                append("正在读取文件…")
                                state.progress?.let { progress ->
                                    append("  ${Formatter.size(progress.bytesRead)}")
                                    if (progress.totalBytes > 0) {
                                        append(" / ${Formatter.size(progress.totalBytes)}")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text("停止") }
                    }
                    Spacer(Modifier.height(6.dp))
                    val fraction = state.progress?.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))

                for (slot in state.slots) {
                    HashSlotRow(
                        slot = slot,
                        onCopy = {
                            val digest = slot.digest ?: return@HashSlotRow
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("checksum", digest),
                            )
                            copiedAlgorithm = slot.algorithm
                        },
                        onVerify = { onVerifyAgainst(slot.algorithm) },
                        justCopied = copiedAlgorithm == slot.algorithm,
                    )
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.pastedChecksum,
                    onValueChange = onPasteChange,
                    label = { Text("粘贴校验值进行对比") },
                    singleLine = false,
                    textStyle = MonospaceTextStyle,
                    supportingText = {
                        Text(
                            text = when (val verification = state.verification) {
                                null -> "长度决定算法：32 位为 MD5、40 位为 SHA-1、64 位为 SHA-256"
                                is HashVerification.Match -> verification.message
                                is HashVerification.Mismatch -> verification.message
                                is HashVerification.InvalidInput -> verification.reason
                            },
                            color = when (state.verification) {
                                is HashVerification.Match -> colors.symlinkIcon
                                is HashVerification.Mismatch -> colors.danger
                                is HashVerification.InvalidInput -> colors.danger
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.verification is HashVerification.Mismatch) {
                    val mismatch = state.verification
                    Spacer(Modifier.height(6.dp))
                    Text("期望：${mismatch.expected}", style = MonospaceTextStyle, color = colors.danger)
                    Text("实际：${mismatch.actual}", style = MonospaceTextStyle, color = colors.danger)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "说明：MD5 与 SHA-1 可用于核对下载文件是否完整，但已被证明可构造碰撞，" +
                        "不适合用于安全校验。需要安全用途请使用 SHA-256。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) { Text("关闭") }
        },
    )
}

@Composable
private fun HashSlotRow(
    slot: HashSlot,
    onCopy: () -> Unit,
    onVerify: () -> Unit,
    justCopied: Boolean,
) {
    val colors = FileManagerTheme.colors

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slot.algorithm.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(76.dp),
            )
            if (slot.algorithm.isCryptographicallyBroken) {
                Surface(
                    color = colors.bannerBackground,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = "非安全",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.bannerText,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (slot.isDone) {
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制 ${slot.algorithm.label}",
                        tint = if (justCopied) colors.symlinkIcon else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(onClick = onVerify) {
                    Text("对比", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        when {
            slot.digest != null -> {
                val digest = slot.digest
                Text(
                    text = if (justCopied) "$digest  （已复制）" else digest,
                    style = MonospaceTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            slot.error != null -> {
                val error = slot.error
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = colors.danger,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = error,
                        style = MetaTextStyle,
                        color = colors.danger,
                    )
                }
            }

            else -> Text(
                text = "计算中…",
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A small confirmation row used when a copy succeeds. */
@Composable
fun HashCopiedBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = FileManagerTheme.colors.symlinkIcon,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("已复制", style = MetaTextStyle, color = FileManagerTheme.colors.symlinkIcon)
    }
}
