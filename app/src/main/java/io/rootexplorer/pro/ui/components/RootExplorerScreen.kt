package io.rootexplorer.pro.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileFilter
import io.rootexplorer.pro.core.filesystem.FileSortField
import io.rootexplorer.pro.core.permissions.LinuxPermission
import io.rootexplorer.pro.feature.filebrowser.FileBrowserUiState
import io.rootexplorer.pro.feature.filebrowser.PaneId
import io.rootexplorer.pro.feature.permissioneditor.OwnerGroupEditorDialog
import io.rootexplorer.pro.feature.properties.PropertiesDialog
import io.rootexplorer.pro.ui.theme.FileManagerTheme

/**
 * The scripting dialogs the screen can show.
 *
 * They are held in the screen rather than the view model because they are pure UI state: a
 * rotation should not resurrect a half-open dialog. The view model owns the data.
 */
private sealed interface ScreenDialog {
    data object None : ScreenDialog
    data class Actions(val entry: FileEntry) : ScreenDialog
    data class Properties(val entry: FileEntry) : ScreenDialog
    data class PermissionEditor(val entry: FileEntry) : ScreenDialog
    data class OwnerGroup(val entry: FileEntry) : ScreenDialog

    /**
     * Confirmation for a change inside a sensitive location.
     *
     * Holds the action to run if the user accepts, so the warning cannot be bypassed by the
     * order in which dialogs are dismissed.
     */
    data class ConfirmSensitive(
        val entry: FileEntry,
        val warning: io.rootexplorer.pro.core.permissions.SensitivePath,
        val proceed: () -> Unit,
    ) : ScreenDialog
    data object CreateFolder : ScreenDialog
    data object CreateFile : ScreenDialog
    data class Rename(val entry: FileEntry) : ScreenDialog
    data class ConfirmDelete(val entry: FileEntry) : ScreenDialog
    data object SortMenu : ScreenDialog
    data object FilterMenu : ScreenDialog
    data object PathInput : ScreenDialog
    data object MoreMenu : ScreenDialog
    data class PathDetail(val path: String) : ScreenDialog
    data class FullPath(val path: String) : ScreenDialog
}

/**
 * The file browser screen: top bar, two panels, bottom action bar, drawer and dialogs.
 *
 * [onNavigate], [onRefresh] and the rest are the view model's actions passed in, so this
 * composable stays free of business logic and remains previewable.
 */
@Composable
fun RootExplorerScreen(
    state: FileBrowserUiState,
    onNavigate: (PaneId, String) -> Unit,
    onOpenEntry: (PaneId, FileEntry) -> Unit,
    onToggleSelection: (PaneId, FileEntry) -> Unit,
    onClearSelection: (PaneId) -> Unit,
    onSelectAll: (PaneId) -> Unit,
    onInvertSelection: (PaneId) -> Unit,
    onSetActivePane: (PaneId) -> Unit,
    onSwapPanes: () -> Unit,
    onToggleDualPane: () -> Unit,
    onBack: (PaneId) -> Unit,
    onForward: (PaneId) -> Unit,
    onUp: (PaneId) -> Unit,
    onRefresh: (PaneId) -> Unit,
    onToggleHidden: () -> Unit,
    onRequestRoot: () -> Unit,
    onCycleTheme: () -> Unit,
    onSetSortField: (FileSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSetFilter: (FileFilter) -> Unit,
    onClearFilter: () -> Unit,
    onAddBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onSetHome: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry, Boolean) -> Unit,
    onCopySelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onLoadStat: suspend (String) -> io.rootexplorer.pro.core.filesystem.StatResult,
    taskState: io.rootexplorer.pro.service.taskmanager.TaskState,
    onCancelTask: () -> Unit,
    onDismissTask: () -> Unit,
    onAnswerConflict: (io.rootexplorer.pro.service.fileoperation.ConflictPolicy) -> Unit,
    onCancelConflict: () -> Unit,
    onTrashSelection: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenHash: (FileEntry) -> Unit,
    onOpenEditor: (FileEntry) -> Unit,
    onOpenCompress: (List<String>) -> Unit,
    onOpenExtract: (FileEntry) -> Unit,
    onOpenApps: () -> Unit,
    onOpenTerminal: () -> Unit,
    onChangePermissions: (FileEntry, LinuxPermission, Boolean) -> Unit,
    onChangeOwnership: (FileEntry, String, String?, Boolean) -> Unit,
    onValidateOwnerSpec: (String) -> String?,
    onResolveUid: suspend (String) -> Int?,
    onResolveGid: suspend (String) -> Int?,
    onSensitiveWarning: (String) -> io.rootexplorer.pro.core.permissions.SensitivePath?,
    rootBusy: Boolean,
    onPostMessage: (String) -> Unit,
    onConsumeMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<ScreenDialog>(ScreenDialog.None) }
    var drawerOpen by remember { mutableStateOf(false) }

    // The drawer's own state is the source of truth for the animation; `drawerOpen` mirrors
    // it so the hamburger button and a swipe-to-close stay in sync.
    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    androidx.compose.runtime.LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }
    androidx.compose.runtime.LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen != drawerOpen) drawerOpen = drawerState.isOpen
    }

    // Runtime storage permission for API 26..32. On API 30+ the real switch is the
    // all-files-access Settings screen, handled separately below.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val readGranted = granted.values.any { it }
        onPostMessage(
            if (readGranted) "已获得存储读取权限" else "存储读取权限被拒绝，仅能浏览应用私有目录",
        )
    }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerContent = {
            DrawerContent(
                state = state,
                onNavigate = { path ->
                    drawerOpen = false
                    onNavigate(state.activePane, path)
                },
                onAddBookmark = { path ->
                    drawerOpen = false
                    onAddBookmark(path)
                },
                onRequestRoot = {
                    drawerOpen = false
                    onRequestRoot()
                },
                onCycleTheme = onCycleTheme,
                onSwapPanes = onSwapPanes,
                onToggleDualPane = {
                    drawerOpen = false
                    onToggleDualPane()
                },
                onToggleHidden = onToggleHidden,
                onOpenTool = { label ->
                    drawerOpen = false
                    onPostMessage("$label：该工具将在后续阶段实现")
                },
                onOpenApps = {
                    drawerOpen = false
                    onOpenApps()
                },
                onOpenTerminal = {
                    drawerOpen = false
                    onOpenTerminal()
                },
                onOpenEditor = {
                    drawerOpen = false
                    // There is no file to edit without a selection, so the entry points at the
                    // gesture that does have one rather than opening an empty editor.
                    val selected = state.active.primarySelection
                    if (selected != null && !selected.isDirectory) {
                        onOpenEditor(selected)
                    } else {
                        onPostMessage("请先在文件列表长按一个文本文件，然后选择「编辑」。")
                    }
                },
                onOpenTrash = {
                    drawerOpen = false
                    onOpenTrash()
                },
            )
        },
        drawerState = drawerState,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                state = state,
                onMenuClick = { drawerOpen = true },
                onPathClick = { dialog = ScreenDialog.PathInput },
                onMoreClick = { dialog = ScreenDialog.MoreMenu },
                onPaneFocus = onSetActivePane,
            )

            if (state.accessWarning != null) {
                AccessBanner(
                    text = state.accessWarning,
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            openAllFilesAccessSettings(context)
                        } else {
                            permissionLauncher.launch(
                                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                            )
                        }
                    },
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (state.dualPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        FileListPane(
                            pane = state.left,
                            focused = state.activePane == PaneId.LEFT,
                            showPathHeader = true,
                            onEntryClick = { entry ->
                                onSetActivePane(PaneId.LEFT)
                                if (state.left.isSelectionMode) {
                                    onToggleSelection(PaneId.LEFT, entry)
                                } else {
                                    onOpenEntry(PaneId.LEFT, entry)
                                }
                            },
                            onEntryOpen = { entry -> onOpenEntry(PaneId.LEFT, entry) },
                            onEntryLongClick = { entry ->
                                onSetActivePane(PaneId.LEFT)
                                onToggleSelection(PaneId.LEFT, entry)
                                dialog = ScreenDialog.Actions(entry)
                            },
                            onPathClick = {
                                onSetActivePane(PaneId.LEFT)
                                dialog = ScreenDialog.PathInput
                            },
                            onRetry = { onRefresh(PaneId.LEFT) },
                            onGoUp = { onUp(PaneId.LEFT) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxSize()
                                .background(FileManagerTheme.colors.divider),
                        )
                        FileListPane(
                            pane = state.right,
                            focused = state.activePane == PaneId.RIGHT,
                            showPathHeader = true,
                            onEntryClick = { entry ->
                                onSetActivePane(PaneId.RIGHT)
                                if (state.right.isSelectionMode) {
                                    onToggleSelection(PaneId.RIGHT, entry)
                                } else {
                                    onOpenEntry(PaneId.RIGHT, entry)
                                }
                            },
                            onEntryOpen = { entry -> onOpenEntry(PaneId.RIGHT, entry) },
                            onEntryLongClick = { entry ->
                                onSetActivePane(PaneId.RIGHT)
                                onToggleSelection(PaneId.RIGHT, entry)
                                dialog = ScreenDialog.Actions(entry)
                            },
                            onPathClick = {
                                onSetActivePane(PaneId.RIGHT)
                                dialog = ScreenDialog.PathInput
                            },
                            onRetry = { onRefresh(PaneId.RIGHT) },
                            onGoUp = { onUp(PaneId.RIGHT) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                        )
                    }
                } else {
                    FileListPane(
                        pane = state.active,
                        focused = true,
                        showPathHeader = true,
                        onEntryClick = { entry ->
                            if (state.active.isSelectionMode) {
                                onToggleSelection(state.activePane, entry)
                            } else {
                                onOpenEntry(state.activePane, entry)
                            }
                        },
                        onEntryOpen = { entry -> onOpenEntry(state.activePane, entry) },
                        onEntryLongClick = { entry ->
                            onToggleSelection(state.activePane, entry)
                            dialog = ScreenDialog.Actions(entry)
                        },
                        onPathClick = { dialog = ScreenDialog.PathInput },
                        onRetry = { onRefresh(state.activePane) },
                        onGoUp = { onUp(state.activePane) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            TaskBar(
                task = taskState,
                onCancel = onCancelTask,
                onDismiss = onDismissTask,
                onClick = {
                    val path = (taskState as? io.rootexplorer.pro.service.taskmanager.TaskState.Running)
                        ?.currentItem
                    if (!path.isNullOrBlank()) dialog = ScreenDialog.PathDetail(path)
                },
            )

            BottomActionBar(
                state = state,
                onBack = { onBack(state.activePane) },
                onForward = { onForward(state.activePane) },
                onUp = { onUp(state.activePane) },
                onCreate = { dialog = ScreenDialog.CreateFolder },
                onSwapPanes = onSwapPanes,
                onToggleDualPane = onToggleDualPane,
                onCopy = onCopySelection,
                onMove = onMoveSelection,
                onDelete = {
                    state.active.primarySelection?.let { dialog = ScreenDialog.ConfirmDelete(it) }
                },
                onProperties = {
                    state.active.primarySelection?.let { dialog = ScreenDialog.Properties(it) }
                },
                onMore = { dialog = ScreenDialog.SortMenu },
                onClearSelection = { onClearSelection(state.activePane) },
                onTrash = onTrashSelection,
            )
        }
    }

    // ------------------------------------------------------------------ dialogs

    when (val current = dialog) {
        ScreenDialog.None -> Unit

        is ScreenDialog.Actions -> FileActionDialog(
            entry = current.entry,
            selectionCount = state.active.selected.size,
            onDismiss = { dialog = ScreenDialog.None },
            onProperties = { dialog = ScreenDialog.Properties(current.entry) },
            onPermission = { dialog = ScreenDialog.PermissionEditor(current.entry) },
            onRename = { dialog = ScreenDialog.Rename(current.entry) },
            onDelete = { dialog = ScreenDialog.ConfirmDelete(current.entry) },
            onCopyPath = {
                copyToClipboard(context, current.entry.path)
                onPostMessage("已复制路径：${current.entry.path}")
                dialog = ScreenDialog.None
            },
            onCopy = {
                onCopySelection()
                dialog = ScreenDialog.None
            },
            onMove = {
                onMoveSelection()
                dialog = ScreenDialog.None
            },
            onBookmark = {
                onAddBookmark(current.entry.path)
                dialog = ScreenDialog.None
            },
            onSelectAll = {
                onSelectAll(state.activePane)
                dialog = ScreenDialog.None
            },
            onInvertSelection = {
                onInvertSelection(state.activePane)
                dialog = ScreenDialog.None
            },
            onSetHome = {
                onSetHome(current.entry.path)
                dialog = ScreenDialog.None
            },
            onHash = {
                onOpenHash(current.entry)
                dialog = ScreenDialog.None
            },
            onCompress = {
                onOpenCompress(state.active.selectedEntries.ifEmpty { listOf(current.entry) }.map { it.path })
                dialog = ScreenDialog.None
            },
            onExtract = {
                onOpenExtract(current.entry)
                dialog = ScreenDialog.None
            },
            onUnavailable = { label ->
                onPostMessage("$label：将在后续阶段实现")
                dialog = ScreenDialog.None
            },
        )

        is ScreenDialog.Properties -> PropertiesDialog(
            entry = current.entry,
            loadStat = onLoadStat,
            onEditPermission = { dialog = ScreenDialog.PermissionEditor(it) },
            onOpenHash = { entry ->
                onOpenHash(entry)
                dialog = ScreenDialog.None
            },
            onOpenEditor = { entry ->
                onOpenEditor(entry)
                dialog = ScreenDialog.None
            },
            onCopyPath = { path ->
                copyToClipboard(context, path)
                onPostMessage("已复制路径：$path")
            },
            onDismiss = { dialog = ScreenDialog.None },
        )

        is ScreenDialog.PermissionEditor -> PermissionEditorDialog(
            entry = current.entry,
            hasRoot = state.rootStatus.isUsable,
            busy = rootBusy,
            onDismiss = { dialog = ScreenDialog.None },
            onRequestRoot = onRequestRoot,
            onApply = { permission, recursive ->
                // A change inside /system, /vendor, /data/adb … asks for an extra confirmation
                // with the consequence spelled out, rather than proceeding quietly.
                val warning = onSensitiveWarning(current.entry.path)
                if (warning != null) {
                    dialog = ScreenDialog.ConfirmSensitive(
                        entry = current.entry,
                        warning = warning,
                        proceed = { onChangePermissions(current.entry, permission, recursive) },
                    )
                } else {
                    onChangePermissions(current.entry, permission, recursive)
                    dialog = ScreenDialog.None
                }
            },
            onEditOwner = { dialog = ScreenDialog.OwnerGroup(current.entry) },
        )

        is ScreenDialog.OwnerGroup -> OwnerGroupEditorDialog(
            entry = current.entry,
            hasRoot = state.rootStatus.isUsable,
            initialOwner = current.entry.ownerName,
            initialGroup = current.entry.groupName,
            validateSpec = onValidateOwnerSpec,
            lookupUid = onResolveUid,
            lookupGid = onResolveGid,
            onRequestRoot = onRequestRoot,
            onApply = { owner, group, recursive ->
                val warning = onSensitiveWarning(current.entry.path)
                if (warning != null) {
                    dialog = ScreenDialog.ConfirmSensitive(
                        entry = current.entry,
                        warning = warning,
                        proceed = { onChangeOwnership(current.entry, owner, group, recursive) },
                    )
                } else {
                    onChangeOwnership(current.entry, owner, group, recursive)
                    dialog = ScreenDialog.None
                }
            },
            onDismiss = { dialog = ScreenDialog.None },
        )

        is ScreenDialog.ConfirmSensitive -> SensitiveChangeDialog(
            warning = current.warning,
            targetPath = current.entry.path,
            onConfirm = {
                current.proceed()
                dialog = ScreenDialog.None
            },
            onDismiss = { dialog = ScreenDialog.None },
        )

        ScreenDialog.CreateFolder -> TextInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { dialog = ScreenDialog.None },
            onConfirm = { name ->
                onCreateFolder(name)
                dialog = ScreenDialog.None
            },
        )

        ScreenDialog.CreateFile -> TextInputDialog(
            title = "新建文件",
            label = "文件名称",
            initial = "",
            confirmLabel = "创建",
            onDismiss = { dialog = ScreenDialog.None },
            onConfirm = { name ->
                onCreateFile(name)
                dialog = ScreenDialog.None
            },
        )

        is ScreenDialog.Rename -> TextInputDialog(
            title = "重命名",
            label = "新名称",
            initial = current.entry.name,
            confirmLabel = "重命名",
            warning = "为避免扩展名丢失，请注意保留 .${current.entry.extension}".takeIf {
                current.entry.extension.isNotEmpty()
            },
            onDismiss = { dialog = ScreenDialog.None },
            onConfirm = { name ->
                onRename(current.entry, name)
                dialog = ScreenDialog.None
            },
        )

        is ScreenDialog.ConfirmDelete -> DeleteConfirmDialog(
            entry = current.entry,
            onDismiss = { dialog = ScreenDialog.None },
            onTrash = {
                onTrashSelection()
                dialog = ScreenDialog.None
            },
            onPermanentDelete = { recursive ->
                onDelete(current.entry, recursive)
                dialog = ScreenDialog.None
            },
        )

        ScreenDialog.SortMenu -> SortDialog(
            field = state.sortSpec.field,
            direction = state.sortSpec.direction,
            onFieldSelected = onSetSortField,
            onToggleDirection = onToggleSortDirection,
            onDismiss = { dialog = ScreenDialog.None },
        )

        ScreenDialog.FilterMenu -> FilterDialog(
            filter = state.filter,
            showHidden = state.showHidden,
            onApply = { filter ->
                onSetFilter(filter)
                dialog = ScreenDialog.None
            },
            onClear = {
                onClearFilter()
                dialog = ScreenDialog.None
            },
            onDismiss = { dialog = ScreenDialog.None },
        )

        ScreenDialog.PathInput -> PathNavigatorDialog(
            currentPath = state.active.path,
            onNavigate = { path ->
                onNavigate(state.activePane, path)
                dialog = ScreenDialog.None
            },
            onCopyPath = { path ->
                copyToClipboard(context, path)
                onPostMessage("已复制路径：$path")
            },
            onDismiss = { dialog = ScreenDialog.None },
        )

        ScreenDialog.MoreMenu -> MoreMenuDialog(
            showHidden = state.showHidden,
            dualPane = state.dualPane,
            filterActive = state.filter.isActive,
            isBookmarked = state.bookmarks.contains(state.active.path),
            onRefresh = {
                onRefresh(state.activePane)
                dialog = ScreenDialog.None
            },
            onSearch = {
                onOpenSearch()
                dialog = ScreenDialog.None
            },
            onSelectAll = {
                onSelectAll(state.activePane)
                dialog = ScreenDialog.None
            },
            onFilter = { dialog = ScreenDialog.FilterMenu },
            onSort = { dialog = ScreenDialog.SortMenu },
            onToggleHidden = {
                onToggleHidden()
                dialog = ScreenDialog.None
            },
            onToggleBookmark = {
                if (state.bookmarks.contains(state.active.path)) {
                    onRemoveBookmark(state.active.path)
                } else {
                    onAddBookmark(state.active.path)
                }
                dialog = ScreenDialog.None
            },
            onSetHome = {
                onSetHome(state.active.path)
                dialog = ScreenDialog.None
            },
            onSwapPanes = {
                onSwapPanes()
                dialog = ScreenDialog.None
            },
            onToggleDualPane = {
                onToggleDualPane()
                dialog = ScreenDialog.None
            },
            onOpenTrash = {
                onOpenTrash()
                dialog = ScreenDialog.None
            },
            onOpenTerminal = {
                onPostMessage("终端模拟器将在 P3 阶段实现")
                dialog = ScreenDialog.None
            },
            onUnavailable = { label ->
                onPostMessage("$label：将在后续阶段实现")
                dialog = ScreenDialog.None
            },
            onDismiss = { dialog = ScreenDialog.None },
        )

        is ScreenDialog.PathDetail -> PathDetailDialog(
            path = current.path,
            onDismiss = { dialog = ScreenDialog.None },
        )

        is ScreenDialog.FullPath -> PathDetailDialog(
            path = current.path,
            onDismiss = { dialog = ScreenDialog.None },
        )
    }

    state.message?.let { message ->
        MessageDialog(message = message, onDismiss = onConsumeMessage)
    }

    // ------------------------------------------------------------------ system back
    //
    // Declared after the dialogs above so this handler runs first while one is open (Compose
    // gives the innermost, latest-declared handler priority), and it mirrors the reference file
    // manager: back unwinds one step of what you are doing instead of dropping out of the app.
    //
    // Order: close a dialog -> close the drawer -> leave selection mode -> go up one directory
    // -> disable, which hands the press back to the system so it exits as usual.
    androidx.activity.compose.BackHandler(
        enabled = dialog !is ScreenDialog.None || drawerOpen ||
            state.active.isSelectionMode || state.active.canGoUp,
    ) {
        when {
            dialog !is ScreenDialog.None -> dialog = ScreenDialog.None
            drawerOpen -> drawerOpen = false
            state.active.isSelectionMode -> onClearSelection(state.activePane)
            else -> onUp(state.activePane)
        }
    }

    // The task strip lives above the bottom bar and shows the one running file task.
    // It is rendered as part of this screen's layout, not as a dialog, so it never hides the
    // file list while a long copy runs.

    // Name-collision prompt: the engine is suspended until this is answered.
    (taskState as? io.rootexplorer.pro.service.taskmanager.TaskState.AskingConflict)?.let { asking ->
        ConflictDialog(
            conflict = asking.conflict,
            onPolicy = onAnswerConflict,
            onCancel = onCancelConflict,
        )
    }
}

/** Open the "all files access" screen, falling back to the app's details page. */
private fun openAllFilesAccessSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    if (!Environment.isExternalStorageManager()) {
        val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        // Some ROMs do not implement the per-app action; fall back rather than crash.
        runCatching { context.startActivity(appIntent) }
            .onFailure { runCatching { context.startActivity(generic) } }
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("path", text))
}
