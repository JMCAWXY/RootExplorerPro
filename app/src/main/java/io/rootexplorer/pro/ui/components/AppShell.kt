package io.rootexplorer.pro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.rootexplorer.pro.BuildConfig
import io.rootexplorer.pro.R
import io.rootexplorer.pro.core.root.RootStatus
import io.rootexplorer.pro.feature.filebrowser.FileBrowserUiState
import io.rootexplorer.pro.feature.filebrowser.PaneId
import io.rootexplorer.pro.ui.theme.FileManagerTheme
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.util.Formatter

/** The content opacity Material uses for a disabled control. */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * The two-layer top bar.
 *
 * Layer one is the navigation row: drawer button, the current path, and the overflow menu.
 * Layer two is the statistics row, which the brief specifies must show the folder count, the
 * file count and the volume's used/total capacity. Every number here comes from the listing
 * or from StatFs — when capacity cannot be read the text says so instead of showing a zero.
 */
@Composable
fun TopBar(
    state: FileBrowserUiState,
    onMenuClick: () -> Unit,
    onPathClick: () -> Unit,
    onMoreClick: () -> Unit,
    onPaneFocus: (PaneId) -> Unit,
) {
    val colors = FileManagerTheme.colors
    Surface(color = colors.toolbar) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "打开侧边栏",
                        tint = colors.onToolbar,
                    )
                }

                // No left/right switcher here. The active pane follows the user: tapping a pane,
                // its path header or one of its rows makes it the pane that the toolbar actions
                // apply to, which is how the reference application behaves. An explicit switcher
                // was both redundant and confusing, because it looked like a dual-pane toggle.

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onPathClick),
                ) {
                    Text(
                        text = state.active.path,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onToolbar,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatsLine(state = state)
                }

                if (state.filter.isActive) {
                    Icon(
                        // An eye glyph reads as "show hidden files" everywhere else in this app;
                        // a filter glyph is what "a filter is narrowing this list" looks like.
                        imageVector = Icons.Filled.FilterAlt,
                        contentDescription = "过滤器已启用",
                        tint = colors.toolbarSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }

                IconButton(onClick = onMoreClick) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = colors.onToolbar,
                    )
                }
            }
        }
    }
}

/**
 * The statistics line under the path.
 *
 * Format follows the reference: `文件夹：N  文件：M  存储：used/total`. Capacity is shown
 * only when StatFs actually returned it.
 */
@Composable
private fun StatsLine(state: FileBrowserUiState) {
    val colors = FileManagerTheme.colors
    val pane = state.active
    val storageText = pane.storage?.let { stats ->
        "存储：${Formatter.capacity(stats.usedBytes, stats.totalBytes)}"
    } ?: "存储：不可用"

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "文件夹：${pane.folderCount}  文件：${pane.fileCount}",
            style = MetaTextStyle,
            color = colors.toolbarSecondary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = storageText,
            style = MetaTextStyle,
            color = colors.toolbarSecondary,
        )
        if (state.showHidden) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "显示隐藏",
                style = MetaTextStyle,
                color = colors.toolbarSecondary,
            )
        }
    }
}

/** The banner that explains missing storage access and offers the fix. */
@Composable
fun AccessBanner(text: String, onGrant: () -> Unit) {
    val colors = FileManagerTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.bannerBackground)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MetaTextStyle,
            color = colors.bannerText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onGrant) {
            Text("去授权", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The bottom action bar.
 *
 * In normal mode it offers navigation and creation; once something is selected it switches to
 * the file-operation set, which is the behaviour the reference screenshots show.
 */
@Composable
fun BottomActionBar(
    state: FileBrowserUiState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onCreate: () -> Unit,
    onSwapPanes: () -> Unit,
    onToggleDualPane: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    /** Batch permission change for the whole selection. */
    onPermission: () -> Unit,
    onProperties: () -> Unit,
    onMore: () -> Unit,
    onClearSelection: () -> Unit,
    onTrash: () -> Unit,
) {
    val colors = FileManagerTheme.colors
    val pane = state.active

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Column {
            HorizontalDivider(color = colors.divider)
            if (pane.isSelectionMode) {
                // A sweep can mark a dozen rows in one motion, so the count is the feedback that
                // says how many the pending action would touch.
                Text(
                    text = "已选 ${pane.selected.size} 项",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (pane.isSelectionMode) {
                    BarAction(Icons.Filled.Add, "复制", enabled = true, onClick = onCopy)
                    BarAction(Icons.AutoMirrored.Filled.ArrowForward, "移动", enabled = true, onClick = onMove)
                    BarAction(Icons.Filled.DeleteForever, "删除", enabled = true, onClick = onDelete)
                    BarAction(Icons.Filled.Lock, "权限", enabled = true, onClick = onPermission)
                    BarAction(Icons.Filled.Delete, "回收站", enabled = true, onClick = onTrash)
                    BarAction(Icons.Filled.Info, "属性", enabled = true, onClick = onProperties)
                    BarAction(Icons.Filled.Close, "取消", enabled = true, onClick = onClearSelection)
                } else {
                    BarAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "后退",
                        enabled = pane.canGoBack,
                        onClick = onBack,
                    )
                    BarAction(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        label = "前进",
                        enabled = pane.canGoForward,
                        onClick = onForward,
                    )
                    BarAction(Icons.Filled.Add, "新建", enabled = true, onClick = onCreate)
                    // "交换窗口" moved to the drawer: in the bottom bar it read as a pane-focus
                    // toggle, which is not what it does.
                    BarAction(
                        icon = Icons.Filled.ArrowUpward,
                        label = "上一级",
                        enabled = pane.canGoUp,
                        onClick = onUp,
                    )
                    BarAction(Icons.Filled.MoreVert, "更多", enabled = true, onClick = onMore)
                }
            }
        }
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Disabled actions keep the normal content colour at Material's 38% instead of switching to
    // the divider colour. That divider tone is within ~1.1:1 of the bar's own surface in both
    // themes, so an unavailable action became indistinguishable from a missing one — the bottom
    // bar looked like it had three buttons when it has five.
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            // Material's minimum touch target is 48dp; the icon plus its caption came to about
            // 40dp, which is tight for a one-handed tap on a phone.
            .heightIn(min = 48.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * The side drawer: root status, storage entries, bookmarks and the tool list.
 *
 * Tools that are not implemented are shown as disabled rows that say so when tapped. The
 * brief calls this out specifically — an entry that looks finished but does nothing is worse
 * than one labelled as pending.
 */
@Composable
fun DrawerContent(
    state: FileBrowserUiState,
    onNavigate: (String) -> Unit,
    onAddBookmark: (String) -> Unit,
    onRequestRoot: () -> Unit,
    onCycleTheme: () -> Unit,
    onSwapPanes: () -> Unit,
    onToggleDualPane: () -> Unit,
    onToggleHidden: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenTool: (String) -> Unit,
) {
    val colors = FileManagerTheme.colors
    // The About page is opened from the drawer's footer.
    var aboutOpen by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header
            Surface(color = colors.toolbar) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = null,
                            tint = colors.onToolbar,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onToolbar,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.rootStatus.shortLabel,
                                    style = MetaTextStyle,
                                    color = colors.toolbarSecondary,
                                )
                                Spacer(Modifier.width(6.dp))
                                if (!state.rootStatus.isUsable) {
                                    Text(
                                        text = "点击授权",
                                        style = MetaTextStyle,
                                        color = colors.rootBadge,
                                        modifier = Modifier.clickable { onRequestRoot() },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onCycleTheme) {
                            Icon(
                                imageVector = Icons.Filled.Brightness6,
                                contentDescription = "切换主题（当前 ${state.themeMode.label}）",
                                tint = colors.onToolbar,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.rootStatus.detailText,
                        style = MetaTextStyle,
                        color = colors.toolbarSecondary,
                    )
                }
            }

            SectionLabel("本地区域")
            DrawerRow(Icons.Filled.Folder, "根目录 /", onClick = { onNavigate("/") })
            DrawerRow(Icons.Filled.Home, "内部存储", onClick = { onNavigate("/storage/emulated/0") })

            SectionLabel("快捷入口")
            io.rootexplorer.pro.core.filesystem.WellKnownPaths.shortcuts.forEach { shortcut ->
                DrawerRow(
                    icon = Icons.Filled.Folder,
                    label = shortcut.label,
                    trailing = if (shortcut.needsRoot && !state.rootStatus.isUsable) "需 Root" else null,
                    onClick = { onNavigate(shortcut.path) },
                )
            }

            if (state.bookmarks.isNotEmpty()) {
                SectionLabel("书签")
                state.bookmarks.forEach { bookmark ->
                    DrawerRow(
                        icon = Icons.Filled.Star,
                        label = bookmark,
                        onClick = { onNavigate(bookmark) },
                    )
                }
            }

            SectionLabel("当前目录")
            DrawerRow(
                icon = Icons.Filled.Star,
                label = "添加为书签",
                onClick = { onAddBookmark(state.active.path) },
            )
            DrawerRow(
                icon = if (state.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                label = if (state.showHidden) "隐藏隐藏文件" else "显示隐藏文件",
                onClick = onToggleHidden,
            )
            DrawerRow(Icons.Filled.SwapHoriz, "交换左右窗口", onClick = onSwapPanes)
            DrawerRow(
                icon = Icons.Filled.SelectAll,
                label = if (state.dualPane) "切换为单栏" else "切换为双栏",
                onClick = onToggleDualPane,
            )

            SectionLabel("工具")
            DrawerRow(Icons.Filled.Delete, "回收站", onClick = onOpenTrash)
            DrawerRow(Icons.Filled.Android, "已安装应用", onClick = onOpenApps)
            DrawerRow(Icons.Filled.Terminal, "终端模拟器", onClick = onOpenTerminal)
            DrawerRow(
                icon = Icons.Filled.Edit,
                label = "文本编辑器",
                onClick = onOpenEditor,
                disabledNote = "在文件列表长按文本文件后选择「编辑」",
            )
            DrawerRow(Icons.Filled.Info, "插件管理", onClick = { onOpenTool("插件管理") }, enabled = false, disabledNote = "未实现")
            DrawerRow(Icons.Filled.MoreVert, "远程管理", onClick = { onOpenTool("远程管理") }, enabled = false, disabledNote = "未实现")
            DrawerRow(Icons.Filled.MoreVert, "MCP 服务", onClick = { onOpenTool("MCP 服务") }, enabled = false, disabledNote = "未实现")
            DrawerRow(Icons.Filled.MoreVert, "Activity 记录", onClick = { onOpenTool("Activity 记录") }, enabled = false, disabledNote = "未实现")

            Spacer(Modifier.height(16.dp))
            Text(
                // Read from the build, never hand-written: the previous hard-coded label had
                // already gone stale and advertised a version this build is not.
                text = "版本 ${BuildConfig.VERSION_NAME} · 关于",
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { aboutOpen = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (aboutOpen) {
        AboutDialog(onDismiss = { aboutOpen = false })
    }
}

/**
 * The About page.
 *
 * The name is read from `app_name` rather than written out again, so the drawer, this page, the
 * launcher label and the recents card can never disagree about what this app is called.
 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                Text(
                    text = "版本 ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "给已 root 的安卓手机用的双栏文件管理器：真实文件系统读写、Root 权限与属主编辑、" +
                        "回收站、递归搜索、压缩包与哈希工具、文本编辑器、终端模拟器。",
                    style = MetaTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun DrawerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: String? = null,
    disabledNote: String? = null,
) {
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            // Material's minimum touch target; the row's own content is only about 38dp tall.
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
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
        val note = trailing ?: disabledNote
        if (note != null) {
            Text(
                text = note,
                style = MetaTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A message dialog used for operation results. */
@Composable
fun MessageDialog(message: String, onDismiss: () -> Unit) {
    if (message.isBlank()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
}
