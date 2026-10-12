package io.rootexplorer.pro.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Html
// Aliased: `Image` is also the name of the composable that draws a decoded bitmap below.
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material.icons.filled.Javascript
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileTypeDetector
import io.rootexplorer.pro.feature.filebrowser.PaneState
import io.rootexplorer.pro.feature.filebrowser.SwipeGesture
import io.rootexplorer.pro.feature.filebrowser.classifySwipe
import io.rootexplorer.pro.feature.filebrowser.selectionRange
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.LocalImageLoader
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.util.Formatter

/** How far left a finger must travel before a drag counts as a selection sweep. */
private const val SWIPE_THRESHOLD_DP = 24f

/**
 * One file panel: a header strip, then the list.
 *
 * The header carries the panel's own path and counts, so in dual-pane mode the user can always
 * tell which panel is which without reading the top bar. [focused] draws the accent colour on that
 * header to mark the panel that actions apply to.
 *
 * [onActivate] is invoked before *every* interaction — a row tap, a double tap, a long press, the
 * header, retry, and the ".." row. That is what keeps the active pane in step with the pane the
 * user is actually touching. It used to be done by the call sites, and only for a single tap, so a
 * double tap or the pane's own header/retry/up controls left the active pane behind — and the next
 * toolbar or bottom-bar action then ran against the *other* pane.
 *
 * [onSweepStart] and [onSweep] implement the leftward sweep: dragging left over the list marks the
 * rows it passes, from the row it started on to the row it is over now.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListPane(
    pane: PaneState,
    focused: Boolean,
    showPathHeader: Boolean,
    /** Makes this pane the active one; called before any interaction it handles. */
    onActivate: () -> Unit,
    onEntryClick: (FileEntry) -> Unit,
    onEntryOpen: (FileEntry) -> Unit,
    onEntryLongClick: (FileEntry) -> Unit,
    onPathClick: () -> Unit,
    onRetry: () -> Unit,
    onGoUp: () -> Unit,
    /** A leftward sweep has begun in this pane. */
    onSweepStart: () -> Unit,
    /** The rows a sweep currently covers, already reduced to the range under the finger. */
    onSweep: (List<FileEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (showPathHeader) {
            PaneHeader(
                pane = pane,
                focused = focused,
                onPathClick = {
                    onActivate()
                    onPathClick()
                },
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            // The transition is keyed on the listing generation, not the path: the path changes
            // before the directory has been read, so animating on it would slide the old list out
            // and then slide the same old list back in. Keying on the generation means the
            // animation plays when the *new* rows are ready, and that a selection change or a
            // re-sort does not animate at all.
            AnimatedContent(
                targetState = pane,
                contentKey = { it.listingGeneration },
                transitionSpec = {
                    val incoming = targetState.path
                    val outgoing = initialState.path
                    val descending = incoming.startsWith(outgoing.trimEnd('/') + "/")
                    val step = if (descending) 1 else -1
                    val sameDirectory = incoming == outgoing
                    val enter = slideInHorizontally(animationSpec = tween(220)) { width ->
                        if (sameDirectory) 0 else step * width / 4
                    } + fadeIn(animationSpec = tween(220))
                    val exit = slideOutHorizontally(animationSpec = tween(220)) { width ->
                        if (sameDirectory) 0 else -step * width / 4
                    } + fadeOut(animationSpec = tween(160))
                    enter togetherWith exit
                },
                label = "paneContent",
            ) { frame ->
                when {
                    frame.error != null -> PaneErrorState(
                        message = frame.error.message,
                        onRetry = {
                            onActivate()
                            onRetry()
                        },
                    )

                    frame.isLoading && frame.entries.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }

                    frame.entries.isEmpty() -> EmptyPaneState(frame)

                    else -> SweepableList(
                        frame = frame,
                        onActivate = onActivate,
                        onEntryClick = onEntryClick,
                        onEntryOpen = onEntryOpen,
                        onEntryLongClick = onEntryLongClick,
                        onGoUp = onGoUp,
                        onSweepStart = onSweepStart,
                        onSweep = onSweep,
                    )
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

/**
 * The list itself, with the sweep gesture attached.
 *
 * The gesture lives on the list rather than on each row: one detector can see a drag that travels
 * across rows (which is the whole point of the sweep), it knows which row is under the finger
 * through [LazyListState.layoutInfo], and it costs nothing per row.
 *
 * A vertical drag is left alone so the list still scrolls with a slightly slanted finger; only a
 * mostly-horizontal leftward drag is taken over. See [classifySwipe].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SweepableList(
    frame: PaneState,
    onActivate: () -> Unit,
    onEntryClick: (FileEntry) -> Unit,
    onEntryOpen: (FileEntry) -> Unit,
    onEntryLongClick: (FileEntry) -> Unit,
    onGoUp: () -> Unit,
    onSweepStart: () -> Unit,
    onSweep: (List<FileEntry>) -> Unit,
) {
    val listState = rememberLazyListState()

    // The gesture coroutine must survive recomposition while a finger is down, so everything it
    // reads lives in State objects. Capturing the callbacks as pointerInput keys instead would
    // restart the detector on every recomposition and abandon the gesture mid-drag.
    val entriesState = rememberUpdatedState(frame.entries)
    val parentOffsetState = rememberUpdatedState(if (frame.canGoUp) 1 else 0)
    val onSweepStartState = rememberUpdatedState(onSweepStart)
    val onSweepState = rememberUpdatedState(onSweep)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val threshold = SWIPE_THRESHOLD_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The rows and the header offset are read once, when the finger lands: a
                    // listing must not change under a gesture anyway, and a stable snapshot keeps
                    // the index arithmetic honest.
                    val entries = entriesState.value
                    val parentOffset = parentOffsetState.value
                    val startRow = listState.rowIndexAt(down.position.y) ?: return@awaitEachGesture
                    val anchor = startRow - parentOffset
                    if (anchor !in entries.indices) return@awaitEachGesture

                    var sweeping = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!sweeping && classifySwipe(dx, dy, threshold) == SwipeGesture.SELECT) {
                            sweeping = true
                            onSweepStartState.value()
                        }
                        if (sweeping) {
                            // Consuming is what stops the list from scrolling at the same time.
                            change.consume()
                            val row = listState.rowIndexAt(change.position.y) ?: startRow
                            val current = (row - parentOffset).coerceIn(0, entries.lastIndex)
                            onSweepState.value(entries.slice(selectionRange(anchor, current)))
                        }
                    }
                }
            },
    ) {
        // The reference file manager leads every pane with a ".." entry, and it is the fastest way
        // to go up on a phone. The top bar's up button does the same thing, but reaching the top of
        // the screen one-handed is awkward.
        if (frame.canGoUp) {
            item(key = "\u002e\u002e", contentType = "parent") {
                ParentRow(
                    onClick = {
                        onActivate()
                        onGoUp()
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        items(
            items = frame.entries,
            key = { it.path },
            // Distinct content types keep the ".." row and the file rows in separate reuse pools.
            // With the default (null) type the list is free to hand a recycled slot of one shape to
            // the other, which costs a full re-layout.
            contentType = { "entry" },
        ) { entry ->
            FileRow(
                entry = entry,
                selected = entry.path in frame.selected,
                onClick = {
                    onActivate()
                    onEntryClick(entry)
                },
                onDoubleClick = {
                    onActivate()
                    onEntryOpen(entry)
                },
                onLongClick = {
                    onActivate()
                    onEntryLongClick(entry)
                },
                // `animateItem` is what makes a deletion read as a deletion: the removed row fades
                // and shrinks while the rows below move up into the gap instead of jumping.
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * The row index under a Y coordinate in the list's own viewport.
 *
 * Returns null in the space beyond the last row (or above the first), which is what tells the
 * sweep that the finger is no longer over a selectable row.
 */
private fun LazyListState.rowIndexAt(y: Float): Int? =
    layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y < it.offset + it.size }
        ?.index

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
 * Rendered as a plain row rather than a real [FileEntry] because there is no such entry on disk;
 * the pane's own path is the source of truth for where "up" goes.
 */
@Composable
private fun ParentRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = modifier
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
 * Layout follows the reference: the icon on the left, the name on the first line and the metadata
 * on a smaller second line. Tap opens, long-press enters selection, and a leftward sweep across
 * rows selects a range — that gesture belongs to the list, not to the row.
 *
 * Pressing dips the row slightly. It is a small transform (2.5%) on purpose: enough to feel like
 * the tap landed, small enough that a fast tap does not read as a wobble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FileManagerTheme.colors
    val isImage = !entry.isDirectory && !entry.isSymlink &&
        FileTypeDetector.detect(entry).category == FileTypeDetector.Category.IMAGE
    val icon = iconForEntry(entry)
    val background = if (selected) colors.selectedRow else Color.Transparent

    // An image row shows the picture itself. The decode happens off the main thread, sampled down
    // to a thumbnail and cached by path, so scrolling back over a directory costs nothing; with no
    // loader (a preview) or on a file that will not decode, the type icon stands in.
    val loader = LocalImageLoader.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, entry.path, loader) {
        value = if (loader != null && isImage) {
            loader.thumbnail(entry.path)?.asImageBitmap()
        } else {
            null
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "rowPressScale",
    )
    val pressDim by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "rowPressDim",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                alpha = pressDim
            }
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture = thumbnail
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint(entry),
                modifier = Modifier.size(24.dp),
            )
        }

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
                text = metadataLine(entry),
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
 * row pushes the timestamp out of view on a narrow screen. A `typeDescription` parameter used
 * to sit here purely so a call site could pass one; it was never read, so every row paid for a
 * description string that went nowhere.
 *
 * Built with a `StringBuilder` rather than a list plus `joinToString`, because this runs once
 * per visible row per recomposition.
 */
private fun metadataLine(entry: FileEntry): String = buildString {
    if (entry.isSymlink && !entry.linkTarget.isNullOrBlank()) {
        append("→ ").append(entry.linkTarget).append("  ")
    }
    append(Formatter.listDate(entry.lastModifiedMillis))
    entry.sizeBytes?.let { append("  ").append(Formatter.size(it)) }
}

@Composable
private fun iconTint(entry: FileEntry) = when {
    entry.isSymlink -> FileManagerTheme.colors.symlinkIcon
    entry.isDirectory -> FileManagerTheme.colors.folderIcon
    entry.isHidden -> FileManagerTheme.colors.hiddenIcon
    else -> FileManagerTheme.colors.fileIcon
}

/**
 * The glyph for one row.
 *
 * The exact extension is consulted first and the coarse category only as a fallback. Category
 * alone turned out to be too blunt to be useful on a phone screen: a folder of PDFs, Word documents
 * and spreadsheets all drew the same page icon, so the list said nothing about what anything was
 * until the name was read. The extension is the cheapest thing that actually tells them apart, and
 * the entry already carries it.
 */
private fun iconForEntry(entry: FileEntry): ImageVector = when {
    entry.isDirectory -> Icons.Filled.Folder
    entry.isSymlink -> Icons.Filled.Link
    else -> ICONS_BY_EXTENSION[entry.extension]
        ?: iconForCategory(FileTypeDetector.detect(entry).category)
}

/**
 * Extension to icon, grouped by what a format *is* rather than by which app made it.
 *
 * Formats a user thinks of together share a glyph — `.zip`, `.rar` and `.7z` are all archives — and
 * formats they think of as different get different ones, so a PDF, a spreadsheet and a plain text
 * file no longer look alike. Only the common formats are listed; anything else falls back to its
 * category, which is still correct, just coarser.
 */
private val ICONS_BY_EXTENSION: Map<String, ImageVector> = buildMap {
    fun group(icon: ImageVector, vararg extensions: String) {
        extensions.forEach { put(it, icon) }
    }

    group(Icons.Filled.PictureAsPdf, "pdf", "ps", "eps")
    group(
        Icons.Filled.Description,
        "doc", "docx", "dot", "dotx", "rtf", "odt", "pages", "djvu", "chm",
    )
    group(Icons.Filled.TableChart, "xls", "xlsx", "xlsm", "ods", "numbers", "csv", "tsv")
    group(Icons.Filled.Slideshow, "ppt", "pptx", "odp", "key")
    group(
        Icons.AutoMirrored.Filled.TextSnippet,
        "txt", "text", "log", "ini", "conf", "cfg", "config",
        "prop", "properties", "env", "tex",
    )
    group(Icons.AutoMirrored.Filled.Article, "md", "markdown", "rst")
    group(Icons.Filled.Book, "epub", "mobi", "azw", "azw3", "fb2")
    group(Icons.Filled.Subtitles, "srt", "vtt", "ass", "ssa")
    group(Icons.Filled.DataObject, "json", "json5", "xml", "yaml", "yml", "toml")
    group(Icons.Filled.Storage, "db", "sqlite", "sqlite3", "db3", "mdb", "realm", "sql")
    group(Icons.Filled.Html, "html", "htm", "xhtml", "vue", "css", "scss", "sass", "less")
    group(Icons.Filled.Javascript, "js", "mjs", "cjs", "jsx", "ts", "tsx")
    group(Icons.Filled.Terminal, "sh", "bash", "zsh", "ksh", "bat", "cmd", "ps1")
    group(
        Icons.Filled.Code,
        "kt", "kts", "java", "class", "cs", "go", "rs", "rb", "php", "pl", "lua", "swift",
        "scala", "dart", "py", "pyw", "c", "cc", "cpp", "cxx", "h", "hpp", "m", "mm", "asm", "s",
        "gradle", "pro", "cmake", "mk", "diff", "patch",
    )
    group(
        Icons.Filled.FolderZip,
        "zip", "zipx", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2", "xz", "txz",
        "zst", "lz4", "lzma", "lz", "z", "jar", "war", "apks", "xapk", "apkm",
        "iso", "img", "deb", "rpm",
    )
    group(
        Icons.Filled.AudioFile,
        "mp3", "flac", "wav", "aac", "ogg", "oga", "m4a", "m4b",
        "opus", "amr", "wma", "aiff", "ape", "mid", "midi",
    )
    group(
        Icons.Filled.VideoFile,
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "3g2", "m4v",
        "ts", "m2ts", "mpg", "mpeg", "rmvb", "rm", "vob", "asf", "ogv",
    )
    group(
        Icons.Filled.ImageIcon,
        "jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "bmp", "webp", "heic", "heif",
        "avif", "svg", "tif", "tiff", "ico", "jp2", "psd", "xcf", "dng", "raw", "cr2", "nef", "arw",
    )
    group(Icons.Filled.FontDownload, "ttf", "ttc", "otf", "woff", "woff2", "eot", "fnt")
    group(Icons.Filled.Android, "apk", "aab", "dex", "odex", "vdex", "art", "oat")
    group(Icons.Filled.Memory, "so")
}

/**
 * The icon for a detected [category] — the fallback for a format with no entry of its own.
 *
 * Directories and symbolic links never reach here; they are resolved from the entry kind alone.
 */
private fun iconForCategory(category: FileTypeDetector.Category): ImageVector = when (category) {
    FileTypeDetector.Category.IMAGE -> Icons.Filled.ImageIcon
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
