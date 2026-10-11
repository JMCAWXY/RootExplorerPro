package io.rootexplorer.pro.feature.filebrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileFilter
import io.rootexplorer.pro.core.filesystem.FileSortField
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.filesystem.ListingResult
import io.rootexplorer.pro.core.filesystem.SortDirection
import io.rootexplorer.pro.core.filesystem.SortSpec
import io.rootexplorer.pro.core.filesystem.StorageResult
import io.rootexplorer.pro.core.filesystem.SystemFileSystem
import io.rootexplorer.pro.core.permissions.LinuxPermission
import io.rootexplorer.pro.core.permissions.PermissionChanger
import io.rootexplorer.pro.core.permissions.SensitivePath
import io.rootexplorer.pro.core.root.RootStatus
import io.rootexplorer.pro.data.repository.AppSettings
import io.rootexplorer.pro.data.repository.FileRepository
import io.rootexplorer.pro.data.repository.SettingsRepository
import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import io.rootexplorer.pro.service.fileoperation.FileOperationEngine
import io.rootexplorer.pro.service.fileoperation.FileOperationsEngine
import io.rootexplorer.pro.service.fileoperation.OperationResult
import io.rootexplorer.pro.service.taskmanager.TaskManager
import io.rootexplorer.pro.service.taskmanager.TaskState
import io.rootexplorer.pro.service.trash.TrashEntry
import io.rootexplorer.pro.service.trash.TrashLocation
import io.rootexplorer.pro.service.trash.TrashRepository
import io.rootexplorer.pro.service.trash.TrashResult
import io.rootexplorer.pro.ui.theme.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The file browser's view model: owns both panels, the shared sort/filter/view settings, and
 * every navigation action.
 *
 * Rules it enforces:
 * * All filesystem work happens off the main thread (the engines already switch to
 *   `Dispatchers.IO`); the view model only ever touches immutable state.
 * * A failed listing produces an error state, never an empty list — the brief calls this out
 *   explicitly, and it is the single most common way a file manager lies to its user.
 * * A task that is superseded is cancelled, so a slow listing cannot overwrite a newer one.
 */
class FileBrowserViewModel(
    private val fileRepository: FileRepository,
    private val settingsRepository: SettingsRepository,
    private val fs: SystemFileSystem,
    private val storageVolumes: io.rootexplorer.pro.core.filesystem.StorageVolumes,
    private val fileOperations: io.rootexplorer.pro.service.fileoperation.FileOperationsEngine,
    private val taskManager: io.rootexplorer.pro.service.taskmanager.TaskManager,
    private val trashRepository: io.rootexplorer.pro.service.trash.TrashRepository,
    private val permissionChanger: io.rootexplorer.pro.core.permissions.PermissionChanger,
) : ViewModel() {

    private val _state = MutableStateFlow(initialUiState(settingsRepository.load()))
    val state: StateFlow<FileBrowserUiState> = _state.asStateFlow()

    /** In-flight directory loads, one per pane, so a new navigation cancels the old one. */
    private val loadJobs = mutableMapOf<PaneId, Job>()

    /** Watches for the running transfer to finish so the panes can be re-read once. */
    private var taskWatcher: Job? = null

    init {
        val settings = settingsRepository.load()
        val intendedHome = FsEngine.normalise(settings.homePath)
        val startPath = fileRepository.resolveStartPath(intendedHome)
        val onFallback = startPath != intendedHome
        _state.update {
            it.copy(
                accessWarning = fileRepository.accessStateText(startPath),
                intendedHome = intendedHome,
                onFallbackHome = onFallback,
                left = it.left.copy(path = startPath),
                right = it.right.copy(path = startPath),
            )
        }
        navigate(PaneId.LEFT, startPath, addToHistory = false)
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Load [path] into [pane].
     *
     * [addToHistory] is false when re-entering a history entry or performing the initial
     * load, which keeps back/forward from growing on every step.
     */
    fun navigate(pane: PaneId, path: String, addToHistory: Boolean = true) {
        val target = FsEngine.normalise(path)
        val current = _state.value.pane(pane)

        val history = if (addToHistory && current.historyIndex >= 0) {
            // Drop any forward entries: navigating somewhere new truncates the redo path.
            current.history.take(current.historyIndex + 1) + target
        } else if (current.history.isEmpty()) {
            listOf(target)
        } else {
            current.history
        }
        val index = if (addToHistory && current.historyIndex >= 0) history.size - 1 else {
            history.indexOf(target).takeIf { it >= 0 } ?: (history.size - 1)
        }

        updatePane(pane) {
            it.copy(
                path = target,
                history = history,
                historyIndex = index,
                isLoading = true,
                error = null,
                selected = emptySet(),
                isSelectionMode = false,
            )
        }

        loadDirectory(pane, target)
    }

    /** Re-read the current directory of [pane]. */
    fun refresh(pane: PaneId = _state.value.activePane) {
        val current = _state.value.pane(pane)
        loadDirectory(pane, current.path)
    }

    fun goBack(pane: PaneId = _state.value.activePane) {
        val current = _state.value.pane(pane)
        if (!current.canGoBack) return
        val index = current.historyIndex - 1
        val target = current.history[index]
        updatePane(pane) { it.copy(historyIndex = index, isLoading = true, error = null) }
        loadDirectory(pane, target)
    }

    fun goForward(pane: PaneId = _state.value.activePane) {
        val current = _state.value.pane(pane)
        if (!current.canGoForward) return
        val index = current.historyIndex + 1
        val target = current.history[index]
        updatePane(pane) { it.copy(historyIndex = index, isLoading = true, error = null) }
        loadDirectory(pane, target)
    }

    fun goUp(pane: PaneId = _state.value.activePane) {
        val current = _state.value.pane(pane)
        val parent = FsEngine.parentOf(current.path) ?: return
        navigate(pane, parent)
    }

    /** Open an entry: descend into directories (following links only when they point there). */
    fun open(pane: PaneId, entry: FileEntry) {
        val isDirectory = entry.isDirectory ||
            (entry.isSymlink && entry.linkTargetIsDirectory == true)
        if (isDirectory) {
            navigate(pane, entry.path)
        } else {
            // Opening a file by type is P3 (open-with / Intent). Until then we must say so
            // rather than silently doing nothing.
            postMessage("暂不支持打开文件：${entry.name}（打开方式将在 P3 实现）")
        }
    }

    /**
     * Re-read after the user returns from an external screen that may have changed things.
     *
     * Most importantly this is where a storage-permission grant is noticed: the user leaves
     * for the "all files access" Settings screen, comes back, and the banner must disappear
     * and the real shared storage must load without a manual refresh.
     */
    fun onResume() {
        val wasOnFallback = _state.value.onFallbackHome
        val hasAccess = fileRepository.hasSharedStorageAccess()
        val intendedHome = _state.value.intendedHome

        _state.update {
            it.copy(accessWarning = fileRepository.accessStateText(it.active.path))
        }

        // The user went to Settings and granted access: move the panes to the directory they
        // originally asked for, instead of leaving them in the app-private fallback.
        if (wasOnFallback && hasAccess) {
            _state.update { it.copy(onFallbackHome = false) }
            navigate(PaneId.LEFT, intendedHome)
            navigate(PaneId.RIGHT, intendedHome)
            postMessage("已获得所有文件访问权限，已切换到 $intendedHome。")
            return
        }

        // Otherwise just re-read: another app may have touched the filesystem meanwhile.
        refresh(PaneId.LEFT)
        refresh(PaneId.RIGHT)
    }

    // ------------------------------------------------------------------ selection

    fun setActivePane(pane: PaneId) {
        _state.update { it.copy(activePane = pane) }
    }

    /** Swap the two panels' contents, which the brief lists as "交换窗口". */
    fun swapPanes() {
        _state.update { current ->
            val left = current.left
            val right = current.right
            current.copy(
                left = right.copy(id = PaneId.LEFT, selected = emptySet(), isSelectionMode = false),
                right = left.copy(id = PaneId.RIGHT, selected = emptySet(), isSelectionMode = false),
            )
        }
        // The panels changed place, so re-read both: the paths now belong to the other panel
        // and their cached entries were produced for a different path.
        refresh(PaneId.LEFT)
        refresh(PaneId.RIGHT)
    }

    fun toggleSelection(pane: PaneId, entry: FileEntry) {
        updatePane(pane) { current ->
            val selected = if (entry.path in current.selected) {
                current.selected - entry.path
            } else {
                current.selected + entry.path
            }
            current.copy(selected = selected, isSelectionMode = selected.isNotEmpty())
        }
    }

    fun clearSelection(pane: PaneId = _state.value.activePane) {
        updatePane(pane) { it.copy(selected = emptySet(), isSelectionMode = false) }
    }

    fun selectAll(pane: PaneId = _state.value.activePane) {
        updatePane(pane) { current ->
            val all = current.entries.map { it.path }.toSet()
            current.copy(selected = all, isSelectionMode = all.isNotEmpty())
        }
    }

    fun invertSelection(pane: PaneId = _state.value.activePane) {
        updatePane(pane) { current ->
            val all = current.entries.map { it.path }.toSet()
            val inverted = all - current.selected
            current.copy(selected = inverted, isSelectionMode = inverted.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ view options

    fun setSortField(field: FileSortField) {
        val spec = _state.value.sortSpec.copy(field = field)
        applySortSpec(spec)
    }

    fun toggleSortDirection() {
        val spec = _state.value.sortSpec.copy(direction = _state.value.sortSpec.direction.toggled())
        applySortSpec(spec)
    }

    private fun applySortSpec(spec: SortSpec) {
        settingsRepository.saveSort(spec.field, spec.direction)
        _state.update { current ->
            current.copy(
                sortSpec = spec,
                left = current.left.resorted(current.filter, spec, current.showHidden),
                right = current.right.resorted(current.filter, spec, current.showHidden),
            )
        }
    }

    fun setFilter(filter: FileFilter) {
        _state.update { current ->
            current.copy(
                filter = filter,
                left = current.left.resorted(filter, current.sortSpec, current.showHidden),
                right = current.right.resorted(filter, current.sortSpec, current.showHidden),
            )
        }
    }

    fun clearFilter() = setFilter(FileFilter())

    fun setShowHidden(show: Boolean) {
        settingsRepository.saveShowHidden(show)
        _state.update { current ->
            current.copy(
                showHidden = show,
                left = current.left.resorted(current.filter, current.sortSpec, show),
                right = current.right.resorted(current.filter, current.sortSpec, show),
            )
        }
    }

    fun toggleShowHidden() = setShowHidden(!_state.value.showHidden)

    fun setDualPane(dual: Boolean) {
        _state.update { it.copy(dualPane = dual) }
    }

    fun toggleDualPane() = setDualPane(!_state.value.dualPane)

    fun setThemeMode(mode: ThemeMode) {
        settingsRepository.saveTheme(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun cycleThemeMode() = setThemeMode(_state.value.themeMode.next())

    // ------------------------------------------------------------------ file operations

    /** Rename the currently focused entry (or a specific [entry]). */
    fun rename(entry: FileEntry, newName: String) {
        viewModelScope.launch {
            val result = fileOperations.rename(entry.path, newName)
            finishOperation(result, refreshPane = paneOf(entry.path))
        }
    }

    /** Delete [entry]. [recursive] must only be true after the user confirmed a non-empty tree. */
    fun delete(entry: FileEntry, recursive: Boolean = false) {
        viewModelScope.launch {
            val result = fileOperations.delete(entry.path, recursive)
            finishOperation(result, refreshPane = paneOf(entry.path))
        }
    }

    /** Create a directory in the focused panel's current directory. */
    fun createDirectory(name: String) {
        val pane = _state.value.activePane
        val parent = _state.value.pane(pane).path
        viewModelScope.launch {
            val result = fileOperations.createDirectory(parent, name)
            finishOperation(result, refreshPane = pane)
        }
    }

    /** Create an empty file in the focused panel's current directory. */
    fun createFile(name: String) {
        val pane = _state.value.activePane
        val parent = _state.value.pane(pane).path
        viewModelScope.launch {
            val result = fileOperations.createFile(parent, name)
            finishOperation(result, refreshPane = pane)
        }
    }

    /**
     * Copy the focused panel's selection into the other panel's directory.
     *
     * The destination is the *other* panel's current directory, which is what makes dual-pane
     * copy work the way the reference app does: pick files on the left, navigate the right
     * panel to the target, press copy.
     */
    fun copySelectionToOtherPane(policy: io.rootexplorer.pro.service.fileoperation.ConflictPolicy = ConflictPolicy.ASK) {
        startTransfer(move = false, policy = policy)
    }

    /** Move the focused panel's selection into the other panel's directory. */
    fun moveSelectionToOtherPane(policy: io.rootexplorer.pro.service.fileoperation.ConflictPolicy = ConflictPolicy.ASK) {
        startTransfer(move = true, policy = policy)
    }

    /**
     * Kick off a cross-pane transfer.
     *
     * Refuses with an explanation rather than doing something surprising when there is nothing
     * selected, when the destination is the same directory, or when a task is already running.
     */
    private fun startTransfer(move: Boolean, policy: ConflictPolicy) {
        val current = _state.value
        val source = current.active
        val destination = current.inactive.path

        val sources = source.selectedEntries.map { it.path }
        val verb = if (move) "移动" else "复制"

        if (sources.isEmpty()) {
            postMessage("请先选择要${verb}的项目")
            return
        }
        if (FsEngine.normalise(source.path) == FsEngine.normalise(destination)) {
            postMessage("源目录与目标目录相同，未执行${verb}")
            return
        }
        if (taskManager.isBusy) {
            postMessage("已有文件任务正在执行，请等待完成或先取消")
            return
        }

        val started = taskManager.startTransfer(
            sources = sources,
            destination = destination,
            move = move,
            policy = policy,
        )
        if (!started) {
            postMessage("无法启动${verb}任务")
            return
        }

        // The list only reflects reality after the task finishes, so the refresh happens in
        // the task-completion handler below rather than here.
        watchTaskCompletion(move)
    }

    /**
     * Re-read the panes once a transfer finishes.
     *
     * Collecting in the view model scope means the refresh still happens if the user navigated
     * away, and stops when the view model is cleared.
     */
    private fun watchTaskCompletion(move: Boolean) {
        if (taskWatcher != null) return
        taskWatcher = viewModelScope.launch {
            taskManager.state.collect { task ->
                if (task is TaskState.Finished) {
                    clearSelection(PaneId.LEFT)
                    clearSelection(PaneId.RIGHT)
                    refresh(PaneId.LEFT)
                    refresh(PaneId.RIGHT)
                    postMessage(task.headline)
                    taskWatcher?.cancel()
                    taskWatcher = null
                }
            }
        }
    }

    /** Move the focused panel's selection to the recycle bin. */
    fun trashSelection() {
        val source = _state.value.active
        val items = source.selectedEntries.map { it.path to it.sizeBytes }

        if (items.isEmpty()) {
            postMessage("请先选择要移入回收站的项目")
            return
        }
        if (taskManager.isBusy) {
            postMessage("已有文件任务正在执行，请等待完成或先取消")
            return
        }
        if (taskManager.startTrash(items, moveToTrash = true)) {
            watchTaskCompletion(move = false)
        } else {
            postMessage("无法启动移入回收站任务")
        }
    }

    /** Every entry in the recycle bin. */
    suspend fun trashEntries(): List<io.rootexplorer.pro.service.trash.TrashEntry> =
        trashRepository.entries()

    /** Where the bin lives, so the UI can show it and explain the volume. */
    fun trashLocation(): io.rootexplorer.pro.service.trash.TrashLocation = trashRepository.location()

    /** Bin size and entry count. */
    suspend fun trashUsage(): Pair<Long, Int> = trashRepository.usage()

    /** Restore one entry, then re-read both panes. */
    fun restoreFromTrash(id: String) {
        viewModelScope.launch {
            val result = trashRepository.restore(id)
            postMessage(
                when (result) {
                    is io.rootexplorer.pro.service.trash.TrashResult.Success ->
                        "已还原：${result.entry.originalPath}"
                    is io.rootexplorer.pro.service.trash.TrashResult.Failure ->
                        "还原失败：${result.reason}"
                },
            )
            refresh(PaneId.LEFT)
            refresh(PaneId.RIGHT)
        }
    }

    /** Delete one entry from the bin for good. */
    fun deleteFromTrash(id: String) {
        viewModelScope.launch {
            val result = trashRepository.deletePermanently(id)
            postMessage(
                when (result) {
                    is io.rootexplorer.pro.service.trash.TrashResult.Success ->
                        "已永久删除：${result.entry.displayName}"
                    is io.rootexplorer.pro.service.trash.TrashResult.Failure ->
                        "删除失败：${result.reason}"
                },
            )
        }
    }

    /** Empty the bin, optionally applying a cleanup rule. */
    fun emptyTrash(olderThanMillis: Long? = null, largerThanBytes: Long? = null) {
        if (taskManager.isBusy) {
            postMessage("已有文件任务正在执行，请等待完成或先取消")
            return
        }
        if (taskManager.startEmptyTrash(olderThanMillis, largerThanBytes)) {
            watchTaskCompletion(move = false)
        }
    }

    /** Drop index records whose payload no longer exists. */
    suspend fun reconcileTrash(): Int = trashRepository.reconcile()

    /**
     * Reconcile the bin's index and report what was cleaned up.
     *
     * Kept as a fire-and-forget action so the trash screen can bind it to a refresh button
     * without owning a coroutine scope of its own.
     */
    fun requestTrashRefresh() {
        viewModelScope.launch {
            val dropped = trashRepository.reconcile()
            postMessage(
                if (dropped > 0) {
                    "回收站索引已整理，移除了 $dropped 条失效记录"
                } else {
                    "回收站索引已是最新"
                },
            )
        }
    }

    /** Answer a pending name-collision question. */
    fun answerConflict(policy: io.rootexplorer.pro.service.fileoperation.ConflictPolicy) {
        taskManager.answerConflict(policy)
    }

    /** Abort the whole operation from the conflict prompt. */
    fun cancelConflict() = taskManager.cancelConflict()

    /** Stop the running transfer or trash task. */
    fun cancelTask() = taskManager.cancel()

    /** Remove a finished task result from the task bar. */
    fun dismissTask() = taskManager.dismiss()

    /** The task bar state. */
    val taskState: kotlinx.coroutines.flow.StateFlow<io.rootexplorer.pro.service.taskmanager.TaskState> =
        taskManager.state

    /** Read full attributes for the properties dialog. */
    suspend fun statFor(path: String): io.rootexplorer.pro.core.filesystem.StatResult =
        fileRepository.stat(path)

    // ------------------------------------------------------------------ root changes (P2)

    /**
     * Apply a permission change and report what was actually observed afterwards.
     *
     * The message always reflects the verification: "applied and re-read" is reported only when
     * the re-read agrees, and a command that exited 0 without changing anything is reported as
     * a failure to apply, which is what the brief requires.
     */
    fun changePermissions(
        entry: FileEntry,
        permission: LinuxPermission,
        recursive: Boolean,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(rootBusy = true) }
            val result = permissionChanger.changeMode(
                entry = entry,
                permission = permission,
                recursive = recursive,
                readStat = { path -> fileRepository.stat(path) },
            )
            _state.update { it.copy(rootBusy = false) }
            postMessage(describeChange(result, entry.name))
            refresh(paneOf(entry.path))
        }
    }

    /** Apply an owner/group change, verified by re-reading the path. */
    fun changeOwnership(
        entry: FileEntry,
        owner: String,
        group: String?,
        recursive: Boolean,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(rootBusy = true) }
            val result = permissionChanger.changeOwnership(
                entry = entry,
                owner = owner,
                group = group,
                recursive = recursive,
                readStat = { path -> fileRepository.stat(path) },
            )
            _state.update { it.copy(rootBusy = false) }
            postMessage(describeChange(result, entry.name))
            refresh(paneOf(entry.path))
        }
    }

    /** A warning for a sensitive path, or null when it is not sensitive. */
    fun sensitiveWarning(path: String): io.rootexplorer.pro.core.permissions.SensitivePath? =
        permissionChanger.sensitiveWarning(path)

    /** Validate an owner or group specifier before it is used. */
    fun validateOwnerSpec(value: String): String? = permissionChanger.validateId(value)

    /** Resolve a user name to a uid through the device's own account database. */
    suspend fun resolveUid(name: String): Int? = permissionChanger.resolveUid(name)

    /** Resolve a group name to a gid. */
    suspend fun resolveGid(name: String): Int? = permissionChanger.resolveGid(name)

    /** Look up the name for a uid, or null when the device has no such account. */
    suspend fun resolveUserName(uid: Int): String? = permissionChanger.resolveUserName(uid)

    /** Look up the name for a gid. */
    suspend fun resolveGroupName(gid: Int): String? = permissionChanger.resolveGroupName(gid)

    /** Turn a change result into a message the user can act on. */
    private fun describeChange(
        result: io.rootexplorer.pro.core.permissions.ChangeResult,
        name: String,
    ): String = when (result) {
        is io.rootexplorer.pro.core.permissions.ChangeResult.Applied ->
            "$name：${result.description}"

        is io.rootexplorer.pro.core.permissions.ChangeResult.NotApplied ->
            "$name：修改未生效。${result.reason}"

        is io.rootexplorer.pro.core.permissions.ChangeResult.Failed ->
            "$name：修改失败。${result.reason}"

        io.rootexplorer.pro.core.permissions.ChangeResult.RootRequired ->
            "修改权限或所有者需要 Root 权限，请先在侧边栏授权。"

        is io.rootexplorer.pro.core.permissions.ChangeResult.ReadOnlyFileSystem ->
            "$name：所在分区为只读，无法修改（${result.path}）。应用不会尝试重新挂载系统分区。"

        is io.rootexplorer.pro.core.permissions.ChangeResult.Unverified ->
            "$name：${result.reason}"
    }

    private fun describe(result: OperationResult): String =
        when (result) {
            is OperationResult.Success -> result.message
            is OperationResult.Failure -> result.message
            is OperationResult.NotImplemented -> result.message
        }

    /**
     * Turn an operation result into UI state.
     *
     * A verified success re-reads the directory so the list reflects the disk. A success that
     * could not be verified is reported as unverified rather than as a plain success.
     */
    private fun finishOperation(
        result: io.rootexplorer.pro.service.fileoperation.OperationResult,
        refreshPane: PaneId,
    ) {
        when (result) {
            is io.rootexplorer.pro.service.fileoperation.OperationResult.Success -> {
                postMessage(
                    if (result.verified) result.message
                    else "${result.message}（未能重新读取验证结果）",
                )
                refresh(refreshPane)
            }

            is io.rootexplorer.pro.service.fileoperation.OperationResult.Failure ->
                postMessage("失败：${result.message}")

            is io.rootexplorer.pro.service.fileoperation.OperationResult.NotImplemented ->
                postMessage(result.message)
        }
    }

    /** Which panel currently shows the directory containing [path]. */
    private fun paneOf(path: String): PaneId {
        val parent = FsEngine.parentOf(path)
        val current = _state.value
        return when {
            FsEngine.normalise(current.left.path) == parent -> PaneId.LEFT
            FsEngine.normalise(current.right.path) == parent -> PaneId.RIGHT
            else -> current.activePane
        }
    }

    // ------------------------------------------------------------------ bookmarks & home

    fun addBookmark(path: String = _state.value.active.path) {
        val updated = settingsRepository.addBookmark(path)
        _state.update { it.copy(bookmarks = updated) }
        postMessage("已添加书签：$path")
    }

    fun removeBookmark(path: String) {
        val updated = settingsRepository.removeBookmark(path)
        _state.update { it.copy(bookmarks = updated) }
        postMessage("已移除书签：$path")
    }

    fun setHome(path: String = _state.value.active.path) {
        settingsRepository.saveHome(path)
        postMessage("已设为首页：$path")
    }

    // ------------------------------------------------------------------ root

    /**
     * Ask for root, which is the only action that makes the root manager show its prompt.
     * Called from an explicit user action, never at launch.
     */
    fun requestRoot(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(message = "正在请求 Root 权限…") }
            val status = fs.requestRoot(force)
            _state.update { it.copy(rootStatus = status, message = status.detailText) }
            if (status.isUsable) {
                // Root changed which engine can serve requests, so the listings are stale.
                refresh(PaneId.LEFT)
                refresh(PaneId.RIGHT)
            }
        }
    }

    fun markRootDenied(detail: String) {
        fs.rootAccess.markDenied(detail)
        _state.update { it.copy(rootStatus = fs.rootAccess.status) }
    }

    // ------------------------------------------------------------------ messages

    fun postMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------------ internals

    private fun loadDirectory(pane: PaneId, path: String) {
        loadJobs[pane]?.cancel()
        loadJobs[pane] = viewModelScope.launch {
            updatePane(pane) { it.copy(isLoading = true, error = null) }

            val result = fileRepository.list(path)
            val snapshot = _state.value

            when (result) {
                is ListingResult.Success -> {
                    updatePane(pane) {
                        it.withListing(
                            listing = result.listing,
                            filter = snapshot.filter,
                            sortSpec = snapshot.sortSpec,
                            showHidden = snapshot.showHidden,
                        )
                    }
                }

                is ListingResult.Failure -> {
                    // Keep the requested path visible so the user can see where they are, but
                    // clear the entries: showing the previous directory's files under the new
                    // path would be actively misleading.
                    updatePane(pane) {
                        it.copy(
                            path = FsEngine.normalise(path),
                            rawEntries = emptyList(),
                            entries = emptyList(),
                            isLoading = false,
                            error = result.error,
                            warnings = emptyList(),
                            selected = emptySet(),
                            isSelectionMode = false,
                            totalOnDisk = 0,
                            filteredOutCount = 0,
                        )
                    }
                }
            }

            // Capacity is independent of the listing, so a failure to read it must not turn a
            // successful listing into an error.
            when (val storage = fileRepository.storage(path)) {
                is StorageResult.Success -> updatePane(pane) { it.copy(storage = storage.stats) }
                is StorageResult.Failure -> updatePane(pane) { it.copy(storage = null) }
            }
        }
    }

    private inline fun updatePane(pane: PaneId, transform: (PaneState) -> PaneState) {
        _state.update { current ->
            if (pane == PaneId.LEFT) {
                current.copy(left = transform(current.left))
            } else {
                current.copy(right = transform(current.right))
            }
        }
    }

    /** Re-apply filter/sort/hidden to entries already in memory, without re-reading the disk. */
    private fun PaneState.resorted(
        filter: FileFilter,
        sortSpec: SortSpec,
        showHidden: Boolean,
    ): PaneState {
        val visible = rawEntries.filter { showHidden || !it.isHidden }
        val passing = visible.filter { filter.matches(it) }
        return copy(
            entries = sortSpec.apply(passing),
            filteredOutCount = visible.size - passing.size,
        )
    }

    /** Storage volumes, for the drawer. */
    fun storageVolumes() = storageVolumes.current()

    /** Access-state text for the current path, refreshed on demand. */
    fun currentAccessWarning(): String? = fileRepository.accessStateText(_state.value.active.path)

    /** True when the app currently holds broad shared-storage access. */
    fun hasSharedStorageAccess(): Boolean = fileRepository.hasSharedStorageAccess()
}

/** Root status the UI shows before any probe has happened. */
val InitialRootStatus: RootStatus = RootStatus.Unknown
