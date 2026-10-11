package io.rootexplorer.pro.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileTypeDetector
import io.rootexplorer.pro.feature.filebrowser.PaneState
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.util.Formatter

/**
 * One file panel: a header strip, then the list.
 *
 * The header carries the panel's own path and counts, so in dual-pane mode the user can
 * always tell which panel is which without reading the top bar. [focused] draws the accent
 * colour on that header to mark the panel that actions apply to.
 */
@Composable
fun FileListPane(
    pane: PaneState,
    focused: Boolean,
    showPathHeader: Boolean,
    onEntryClick: (FileEntry) -> Unit,
    onEntryOpen: (FileEntry) -> Unit,
    onEntryLongClick: (FileEntry) -> Unit,
    onPathClick: () -> Unit,
    onRetry: () -> Unit,
    onGoUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (showPathHeader) {
            PaneHeader(pane = pane, focused = focused, onPathClick = onPathClick)
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                pane.error != null -> PaneErrorState(
                    message = pane.error.message,
                    onRetry = onRetry,
                )

                pane.isLoading && pane.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                pane.entries.isEmpty() -> EmptyPaneState(pane)

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // The reference file manager leads every pane with a ".." entry, and it is
                    // the fastest way to go up on a phone. The top bar's up button does the same
                    // thing, but reaching the top of the screen one-handed is awkward.
                    if (pane.canGoUp) {
                        item(key = "\u002e\u002e") {
                            ParentRow(onClick = onGoUp)
                        }
                    }
                    items(items = pane.entries, key = { it.path }) { entry ->
                        FileRow(
                            entry = entry,
                            selected = entry.path in pane.selected,
                            onClick = { onEntryClick(entry) },
                            onDoubleClick = { onEntryOpen(entry) },
                            onLongClick = { onEntryLongClick(entry) },
                        )
                    }
                }
            }
        }

        if (pane.warnings.isNotEmpty()) {
            Text(
                text = "有 ${pane.warnings.size} 个条目信息不完整",
                style = MetaTextStyle,
                color = colors.bannerText,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bannerBackground)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PaneHeader(
    pane: PaneState,
    focused: Boolean,
    onPathClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onPathClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = pane.path,
                style = MetaTextStyle,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = true),
            )
            if (pane.viaRoot) {
                Spacer(Modifier.width(6.dp))
                RootBadge()
            }
            // The item counts live in the top bar, which already describes the focused pane.
            // Repeating them here produced a header with two lines of the same numbers, which is
            // what the first real-device screenshot showed. Only the filter notice is kept,
            // because it is the one fact the top bar does not carry.
            if (pane.filteredOutCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "已过滤 ${pane.filteredOutCount}",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The leading ".." row.
 *
 * Rendered as a plain row rather than a real [FileEntry] because there is no such entry on
 * disk; the pane's own path is the source of truth for where "up" goes.
 */
@Composable
private fun ParentRow(onClick: () -> Unit) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = colors.folderIcon,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = "..",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A small chip marking a listing that came from the root shell. */
@Composable
fun RootBadge(modifier: Modifier = Modifier) {
    val colors = FileManagerTheme.colors
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(colors.rootBadge)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "ROOT",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyPaneState(pane: PaneState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (pane.filteredOutCount > 0) {
                    "当前过滤条件下没有匹配项"
                } else {
                    "此目录为空"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pane.filteredOutCount > 0) {
                Text(
                    text = "目录中实际有 ${pane.totalOnDisk} 个条目，被过滤隐藏",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PaneErrorState(message: String, onRetry: () -> Unit) {
    val colors = FileManagerTheme.colors
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "点击重试",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * A single file row.
 *
 * Layout follows the reference: the icon on the left, the name on the first line and the
 * metadata on a smaller second line. Tap opens, long-press enters selection, which is the
 * interaction the reference screenshots imply.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = FileManagerTheme.colors
    val typeInfo = FileTypeDetector.detect(entry)
    val background = if (selected) colors.selectedRow else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconFor(entry, typeInfo.category),
            contentDescription = null,
            tint = iconTint(entry),
            modifier = Modifier.size(24.dp),
        )

        Spacer(Modifier.width(9.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (entry.isHidden) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isSymlink) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = "符号链接",
                        tint = colors.symlinkIcon,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Text(
                text = metadataLine(entry, typeInfo.description),
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The secondary line.
 *
 * Follows the reference layout: the timestamp, then the size for files. The type description is
 * deliberately not repeated here — the row's icon already conveys it, and printing it in every
 * row pushes the timestamp out of view on a narrow screen.
 */
private fun metadataLine(entry: FileEntry, typeDescription: String): String {
    // typeDescription is accepted but intentionally unused: the icon carries the type. It stays
    // in the signature so a future "show type" setting does not have to re-thread the call site.
    @Suppress("UNUSED_EXPRESSION")
    typeDescription

    val parts = mutableListOf<String>()
    if (entry.isSymlink && !entry.linkTarget.isNullOrBlank()) {
        parts += "→ ${entry.linkTarget}"
    }
    parts += Formatter.listDate(entry.lastModifiedMillis)
    entry.sizeBytes?.let { parts += Formatter.size(it) }
    return parts.joinToString("  ")
}

@Composable
private fun iconTint(entry: FileEntry) = when {
    entry.isSymlink -> FileManagerTheme.colors.symlinkIcon
    entry.isDirectory -> FileManagerTheme.colors.folderIcon
    entry.isHidden -> FileManagerTheme.colors.hiddenIcon
    else -> FileManagerTheme.colors.fileIcon
}

private fun iconFor(entry: FileEntry, category: FileTypeDetector.Category): ImageVector = when {
    entry.isDirectory -> Icons.Filled.Folder
    entry.isSymlink -> Icons.Filled.Link
    else -> when (category) {
        FileTypeDetector.Category.IMAGE -> Icons.Filled.Image
        FileTypeDetector.Category.VIDEO -> Icons.Filled.Movie
        FileTypeDetector.Category.AUDIO -> Icons.Filled.MusicNote
        FileTypeDetector.Category.DOCUMENT -> Icons.Filled.Description
        FileTypeDetector.Category.ARCHIVE -> Icons.Filled.Archive
        FileTypeDetector.Category.APK -> Icons.Filled.Android
        FileTypeDetector.Category.CODE -> Icons.Filled.Code
        FileTypeDetector.Category.FONT -> Icons.Filled.TextFields
        FileTypeDetector.Category.DATABASE -> Icons.Filled.Storage
        FileTypeDetector.Category.FOLDER -> Icons.Filled.Folder
        FileTypeDetector.Category.UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
