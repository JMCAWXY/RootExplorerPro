package io.rootexplorer.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.rootexplorer.pro.feature.archive.ArchiveViewModel
import io.rootexplorer.pro.feature.archive.CompressDialog
import io.rootexplorer.pro.feature.archive.ExtractDialog
import io.rootexplorer.pro.feature.editor.EditorScreen
import io.rootexplorer.pro.feature.editor.EditorViewModel
import io.rootexplorer.pro.feature.filebrowser.FileBrowserViewModel
import io.rootexplorer.pro.feature.hash.HashDialog
import io.rootexplorer.pro.feature.hash.HashViewModel
import io.rootexplorer.pro.feature.installedapps.InstalledAppsScreen
import io.rootexplorer.pro.feature.installedapps.InstalledAppsViewModel
import io.rootexplorer.pro.feature.search.SearchScreen
import io.rootexplorer.pro.feature.search.SearchViewModel
import io.rootexplorer.pro.feature.terminal.TerminalScreen
import io.rootexplorer.pro.feature.terminal.TerminalViewModel
import io.rootexplorer.pro.feature.trash.EmptyRule
import io.rootexplorer.pro.feature.trash.TrashScreen
import io.rootexplorer.pro.service.trash.TrashEntry
import io.rootexplorer.pro.service.trash.TrashLocation
import io.rootexplorer.pro.core.shell.ShellKind
import io.rootexplorer.pro.ui.components.RootExplorerScreen
import io.rootexplorer.pro.ui.theme.RootExplorerTheme

/**
 * The single activity.
 *
 * Its only jobs are: build the view model from the application container, decide which screen
 * is showing, and forward user intents back to the view model. All logic lives in the view
 * model and below, so the activity stays a thin shell — which is what the brief asks for when
 * it warns against putting the whole app in MainActivity.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as RootExplorerApplication).container

        setContent {
            val viewModel: FileBrowserViewModel = viewModel(
                factory = viewModelFactory(container),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val taskState by viewModel.taskState.collectAsStateWithLifecycle()

            // Re-read the filesystem and re-evaluate storage access whenever the app comes
            // back to the foreground — most importantly after the user grants "all files
            // access" on the Settings screen we send them to.
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                viewModel.onResume()
                onPauseOrDispose { }
            }

            var screen by remember { mutableStateOf<Screen>(Screen.Browser) }
            // Non-null while the checksum dialog is open; it overlays whichever screen is shown.
            var hashTarget by remember { mutableStateOf<io.rootexplorer.pro.core.filesystem.FileEntry?>(null) }
            var archiveTarget by remember { mutableStateOf<ArchiveTarget?>(null) }

            RootExplorerTheme(mode = state.themeMode) {
                // The activity draws edge to edge (enableEdgeToEdge), so without explicit inset
                // padding the top bar renders underneath the status bar and the clock overlaps
                // the path text — which is exactly what happens on a real device, whose status
                // bar inset is 121 px (~44 dp) here. The insets are applied once, at the root, so
                // every screen inside is laid out inside the safe area.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    // Bound to a local because `screen` is a delegated property: a `when` over the
                    // delegate itself cannot smart-cast its branches.
                    val currentScreen = screen
                    when (currentScreen) {
                    Screen.Browser -> RootExplorerScreen(
                        state = state,
                        onNavigate = viewModel::navigate,
                        onOpenEntry = viewModel::open,
                        onToggleSelection = viewModel::toggleSelection,
                        onClearSelection = viewModel::clearSelection,
                        onSelectAll = viewModel::selectAll,
                        onInvertSelection = viewModel::invertSelection,
                        onSetActivePane = viewModel::setActivePane,
                        onSwapPanes = viewModel::swapPanes,
                        onToggleDualPane = viewModel::toggleDualPane,
                        onBack = viewModel::goBack,
                        onForward = viewModel::goForward,
                        onUp = viewModel::goUp,
                        onRefresh = viewModel::refresh,
                        onToggleHidden = viewModel::toggleShowHidden,
                        onRequestRoot = { viewModel.requestRoot() },
                        onCycleTheme = viewModel::cycleThemeMode,
                        onSetSortField = viewModel::setSortField,
                        onToggleSortDirection = viewModel::toggleSortDirection,
                        onSetFilter = viewModel::setFilter,
                        onClearFilter = viewModel::clearFilter,
                        onAddBookmark = viewModel::addBookmark,
                        onRemoveBookmark = viewModel::removeBookmark,
                        onSetHome = viewModel::setHome,
                        onCreateFolder = viewModel::createDirectory,
                        onCreateFile = viewModel::createFile,
                        onRename = viewModel::rename,
                        onDelete = viewModel::delete,
                        onCopySelection = viewModel::copySelectionToOtherPane,
                        onMoveSelection = viewModel::moveSelectionToOtherPane,
                        onTrashSelection = viewModel::trashSelection,
                        onOpenTrash = { screen = Screen.Trash },
                        onOpenSearch = { screen = Screen.Search },
                        onOpenHash = { hashTarget = it },
                        onOpenEditor = { entry -> screen = Screen.Editor(entry.path) },
                        onOpenCompress = { sources -> archiveTarget = ArchiveTarget.Compress(sources) },
                        onOpenExtract = { entry -> archiveTarget = ArchiveTarget.Extract(entry) },
                        onOpenApps = { screen = Screen.InstalledApps },
                        onOpenTerminal = { screen = Screen.Terminal },
                        onChangePermissions = viewModel::changePermissions,
                        onChangeOwnership = viewModel::changeOwnership,
                        onValidateOwnerSpec = viewModel::validateOwnerSpec,
                        onResolveUid = viewModel::resolveUid,
                        onResolveGid = viewModel::resolveGid,
                        onSensitiveWarning = viewModel::sensitiveWarning,
                        rootBusy = state.rootBusy,
                        onLoadStat = viewModel::statFor,
                        taskState = taskState,
                        onCancelTask = viewModel::cancelTask,
                        onDismissTask = viewModel::dismissTask,
                        onAnswerConflict = viewModel::answerConflict,
                        onCancelConflict = viewModel::cancelConflict,
                        onPostMessage = viewModel::postMessage,
                        onConsumeMessage = viewModel::consumeMessage,
                    )

                    Screen.Trash -> {
                        BackHandler { screen = Screen.Browser }
                        TrashRoute(
                            viewModel = viewModel,
                            onBack = { screen = Screen.Browser },
                        )
                    }

                    Screen.Search -> {
                        val searchViewModel: SearchViewModel = viewModel(
                            factory = searchViewModelFactory(container),
                        )
                        val searchState by searchViewModel.state.collectAsStateWithLifecycle()

                        BackHandler {
                            // Leaving the screen must stop the walk, not just hide it.
                            searchViewModel.cancel()
                            screen = Screen.Browser
                        }

                        SearchScreen(
                            state = searchState,
                            startPath = state.active.path,
                            hasRoot = state.rootStatus.isUsable,
                            onBack = {
                                searchViewModel.cancel()
                                screen = Screen.Browser
                            },
                            onSearch = searchViewModel::search,
                            onCancel = searchViewModel::cancel,
                            onClear = searchViewModel::clear,
                            onOpenResult = { entry ->
                                // A directory result navigates to it; a file result reveals its
                                // containing directory, because there is nothing else to do
                                // with a file until P3 adds open-with.
                                val target = if (entry.isDirectory) {
                                    entry.path
                                } else {
                                    io.rootexplorer.pro.core.filesystem.FsEngine
                                        .parentOf(entry.path) ?: state.active.path
                                }
                                viewModel.navigate(state.activePane, target)
                                screen = Screen.Browser
                            },
                            onRevealInPane = { entry ->
                                val parent = io.rootexplorer.pro.core.filesystem.FsEngine
                                    .parentOf(entry.path) ?: state.active.path
                                viewModel.navigate(state.activePane, parent)
                                viewModel.toggleSelection(state.activePane, entry)
                                screen = Screen.Browser
                            },
                        )
                    }

                    is Screen.Editor -> {
                        val editorPath = currentScreen.path
                        val editorViewModel: EditorViewModel = viewModel(
                            factory = editorViewModelFactory(container),
                        )
                        val editorState by editorViewModel.state.collectAsStateWithLifecycle()

                        // Open (or re-open) whenever the target path changes, so navigating to a
                        // different file in the same screen reloads it.
                        LaunchedEffect(editorPath) {
                            editorViewModel.open(editorPath)
                        }

                        BackHandler {
                            if (!editorState.isDirty) {
                                viewModel.refresh(state.activePane)
                                screen = Screen.Browser
                            }
                        }

                        EditorScreen(
                            state = editorState,
                            onBack = {
                                viewModel.refresh(state.activePane)
                                screen = Screen.Browser
                            },
                            onTextChange = editorViewModel::onTextChange,
                            onFindQueryChange = editorViewModel::setFindQuery,
                            onReplaceQueryChange = editorViewModel::setReplaceQuery,
                            onCaseSensitiveChange = editorViewModel::setCaseSensitive,
                            onReplaceAll = editorViewModel::replaceAll,
                            onRevert = editorViewModel::revert,
                            onSave = { editorViewModel.save() },
                            onConsumeMessage = editorViewModel::consumeMessage,
                            onConsumeError = editorViewModel::consumeError,
                            onRetryLoad = { editorViewModel.open(editorPath) },
                        )
                    }

                    is Screen.InstalledApps -> {
                        val appsViewModel: InstalledAppsViewModel = viewModel(
                            factory = installedAppsViewModelFactory(container),
                        )
                        val appsState by appsViewModel.state.collectAsStateWithLifecycle()

                        BackHandler { screen = Screen.Browser }

                        InstalledAppsScreen(
                            state = appsState,
                            iconFor = appsViewModel::iconFor,
                            onBack = { screen = Screen.Browser },
                            onQueryChange = appsViewModel::setQuery,
                            onKindFilterChange = appsViewModel::setKindFilter,
                            onSortFieldChange = appsViewModel::setSortField,
                            onToggleSortDirection = appsViewModel::toggleSortDirection,
                            onOpenApp = { app ->
                                val intent = appsViewModel.launchIntent(app.packageName)
                                if (intent == null) {
                                    viewModel.postMessage("${app.label} 没有可启动的界面")
                                } else {
                                    runCatching { startActivity(intent) }
                                        .onFailure { viewModel.postMessage("无法启动 ${app.label}") }
                                }
                            },
                            onShowDetails = { app ->
                                val intent = appsViewModel.detailsIntent(app.packageName)
                                if (intent == null) {
                                    viewModel.postMessage("系统未提供该应用的信息页面")
                                } else {
                                    runCatching { startActivity(intent) }
                                        .onFailure { viewModel.postMessage("无法打开系统信息页面") }
                                }
                            },
                            onExport = { app ->
                                exportAppApks(container, appsViewModel, app, state.active.path, viewModel)
                            },
                            onRefresh = appsViewModel::refresh,
                        )
                    }

                    is Screen.Terminal -> {
                        val terminalViewModel: TerminalViewModel = viewModel(
                            factory = terminalViewModelFactory(),
                        )
                        val terminalState by terminalViewModel.state.collectAsStateWithLifecycle()

                        BackHandler { screen = Screen.Browser }

                        // Open a plain shell on first entry. A root shell is never entered without
                        // the user asking, because it triggers the authorization prompt.
                        LaunchedEffect(Unit) {
                            terminalViewModel.selectShell(ShellKind.NORMAL)
                        }

                        TerminalScreen(
                            state = terminalState,
                            onBack = { screen = Screen.Browser },
                            onSelectShell = terminalViewModel::selectShell,
                            onInputChange = terminalViewModel::setInput,
                            onRun = { terminalViewModel.run() },
                            onStop = terminalViewModel::stop,
                            onClear = terminalViewModel::clear,
                            onDismissError = terminalViewModel::dismissError,
                        )
                    }
                }
                val hashEntry = hashTarget
                if (hashEntry != null) {
                    val hashViewModel: HashViewModel = viewModel(factory = hashViewModelFactory(container))
                    val hashState by hashViewModel.state.collectAsStateWithLifecycle()

                    HashDialog(
                        entryPath = hashEntry.path,
                        entryName = hashEntry.name,
                        fileSizeBytes = hashEntry.sizeBytes ?: 0L,
                        state = hashState,
                        onStart = hashViewModel::start,
                        onCancel = hashViewModel::cancel,
                        onPasteChange = hashViewModel::setPastedChecksum,
                        onVerifyAgainst = hashViewModel::verifyAgainst,
                        onDismiss = { hashTarget = null },
                    )
                }

                // Archive dialogs, likewise scoped to the activity so an extraction survives
                // a rotation.
                val archiveRequest = archiveTarget
                if (archiveRequest != null) {
                    val archiveViewModel: ArchiveViewModel =
                        viewModel(factory = archiveViewModelFactory(container))
                    val archiveState by archiveViewModel.state.collectAsStateWithLifecycle()

                    when (archiveRequest) {
                        is ArchiveTarget.Compress -> CompressDialog(
                            sources = archiveRequest.sources,
                            defaultDirectory = state.active.path,
                            state = archiveState,
                            onCompress = { targetPath, format, level ->
                                archiveViewModel.compress(
                                    sources = archiveRequest.sources,
                                    targetArchivePath = targetPath,
                                    format = format,
                                    compressionLevel = level,
                                )
                            },
                            onCancel = archiveViewModel::cancel,
                            onDismiss = {
                                archiveViewModel.reset()
                                archiveTarget = null
                                viewModel.refresh(state.activePane)
                            },
                        )

                        is ArchiveTarget.Extract -> ExtractDialog(
                            archivePath = archiveRequest.entry.path,
                            defaultDirectory = state.active.path,
                            entryCount = null,
                            state = archiveState,
                            onExtract = { targetDirectory, policy ->
                                archiveViewModel.extract(
                                    archivePath = archiveRequest.entry.path,
                                    targetDirectory = targetDirectory,
                                    policy = policy,
                                )
                            },
                            onCancel = archiveViewModel::cancel,
                            onDismiss = {
                                archiveViewModel.reset()
                                archiveTarget = null
                                viewModel.refresh(state.activePane)
                            },
                        )
                    }
                }
                }
            }
        }
    }

    /** Which archive dialog is open. */
    private sealed interface ArchiveTarget {
        data class Compress(val sources: List<String>) : ArchiveTarget
        data class Extract(val entry: io.rootexplorer.pro.core.filesystem.FileEntry) : ArchiveTarget
    }

    /**
     * Copy an app's APKs into [destinationDirectory].
     *
     * The copy goes through the same transfer engine the file browser uses, so an export gets
     * progress, cancellation, conflict handling and partial-failure reporting without a second
     * implementation. Only the naming differs, which is what the rename hints are for: an app's
     * APKs are physically named `base.apk` and `split_config.*.apk`, which are neither unique nor
     * informative, and copying them under those names would collide.
     */
    private fun exportAppApks(
        container: AppContainer,
        appsViewModel: InstalledAppsViewModel,
        app: io.rootexplorer.pro.core.apk.InstalledApp,
        destinationDirectory: String,
        browserViewModel: FileBrowserViewModel,
    ) {
        val sources = appsViewModel.exportSources(app)

        if (sources !is io.rootexplorer.pro.core.apk.AppExportResult.Success) {
            val reason = when (sources) {
                is io.rootexplorer.pro.core.apk.AppExportResult.Unavailable -> sources.reason
                is io.rootexplorer.pro.core.apk.AppExportResult.Failure -> sources.reason
                is io.rootexplorer.pro.core.apk.AppExportResult.Success -> "未知原因"
            }
            browserViewModel.postMessage("无法导出 ${app.label}：$reason")
            return
        }

        val hints = sources.files.mapIndexed { index, path ->
            path to appsViewModel.exportFileName(app, path, index)
        }.toMap()

        val started = container.taskManager.startTransfer(
            sources = sources.files,
            destination = destinationDirectory,
            move = false,
            policy = io.rootexplorer.pro.service.fileoperation.ConflictPolicy.RENAME,
            renameHints = hints,
        )

        browserViewModel.postMessage(
            if (started) {
                "正在导出 ${app.label}（${sources.files.size} 个 APK）到 $destinationDirectory"
            } else {
                "已有文件任务正在执行，请等待完成后再导出"
            },
        )
    }

    /** Which screen the activity is showing. Four entries do not justify a nav library yet. */
    private sealed interface Screen {
        data object Browser : Screen
        data object Trash : Screen
        data object Search : Screen
        data class Editor(val path: String) : Screen
        data object InstalledApps : Screen
        data object Terminal : Screen
    }

    /** The terminal has no dependencies beyond the shell it creates for itself. */
    private fun terminalViewModelFactory(): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(TerminalViewModel::class.java)) {
                    return TerminalViewModel() as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }

    private fun installedAppsViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(InstalledAppsViewModel::class.java)) {
                    return InstalledAppsViewModel(container.installedApps) as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }

    private fun editorViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
                    return EditorViewModel(container.textFileEngine) as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }

    private fun archiveViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ArchiveViewModel::class.java)) {
                    return ArchiveViewModel(container.archiveEngine) as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }

    private fun hashViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(HashViewModel::class.java)) {
                    return HashViewModel(container.hashEngine) as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }

    private fun searchViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                    return SearchViewModel(container.searchEngine) as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }

    private fun viewModelFactory(container: AppContainer): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(FileBrowserViewModel::class.java)) {
                    return FileBrowserViewModel(
                        fileRepository = container.fileRepository,
                        settingsRepository = container.settingsRepository,
                        fs = container.fileSystem,
                        storageVolumes = container.storageVolumes,
                        fileOperations = container.fileOperations,
                        taskManager = container.taskManager,
                        trashRepository = container.trashRepository,
                        permissionChanger = container.permissionChanger,
                    ) as T
                }
                throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
            }
        }
}

/**
 * Loads the bin's contents and keeps them in sync with operations from this screen.
 *
 * The reload counter is bumped after a restore or a delete so the list reflects the index
 * rather than an optimistic guess.
 */
@Composable
private fun TrashRoute(
    viewModel: FileBrowserViewModel,
    onBack: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var location by remember { mutableStateOf(TrashLocation("", false)) }
    var usage by remember { mutableStateOf(0L to 0) }
    var loading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadToken) {
        loading = true
        location = viewModel.trashLocation()
        entries = viewModel.trashEntries()
        usage = viewModel.trashUsage()
        loading = false
    }

    // Re-read when a trash task finishes so the list is never stale after an empty or restore.
    val taskState by viewModel.taskState.collectAsStateWithLifecycle()
    LaunchedEffect(taskState) {
        if (taskState is io.rootexplorer.pro.service.taskmanager.TaskState.Finished) {
            reloadToken++
        }
    }

    TrashScreen(
        entries = entries,
        location = location,
        totalBytes = usage.first,
        isLoading = loading,
        onBack = onBack,
        onRestore = { entry ->
            viewModel.restoreFromTrash(entry.id)
            reloadToken++
        },
        onDeletePermanently = { entry ->
            viewModel.deleteFromTrash(entry.id)
            reloadToken++
        },
        onEmpty = { rule: EmptyRule ->
            viewModel.emptyTrash(rule.olderThanMillis, rule.largerThanBytes)
        },
        onRefresh = {
            // Reconcile drops index records whose payload no longer exists, so "refresh" does
            // real work rather than only re-reading the same list.
            viewModel.requestTrashRefresh()
            reloadToken++
        },
    )
}
