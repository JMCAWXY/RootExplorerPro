package io.rootexplorer.pro.feature.filebrowser

import androidx.compose.runtime.Immutable
import io.rootexplorer.pro.core.filesystem.DirectoryListing
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileFilter
import io.rootexplorer.pro.core.filesystem.FsError
import io.rootexplorer.pro.core.filesystem.SortSpec
import io.rootexplorer.pro.core.filesystem.StorageStats
import io.rootexplorer.pro.data.repository.AppSettings

/** Identifies one of the two file panels. */
enum class PaneId(val title: String) {
    LEFT("左栏"),
    RIGHT("右栏"),
    ;

    val other: PaneId get() = if (this == LEFT) RIGHT else LEFT
}

/**
 * Everything one file panel needs to render itself.
 *
 * The panel keeps its own path, history, selection and scroll state, so the two panels are
 * genuinely independent — which is the whole point of a dual-pane manager.
 */
@Immutable
data class PaneState(
    val id: PaneId,
    /** Current directory. */
    val path: String = "/",
    /** Back/forward history: [historyIndex] points at the current [path] within this list. */
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1,
    /** Raw entries as read from the filesystem, before filtering and sorting. */
    val rawEntries: List<FileEntry> = emptyList(),
    /** Entries after the shared filter and sort spec are applied — this is what is shown. */
    val entries: List<FileEntry> = emptyList(),
    /** Paths of the entries the user selected. */
    val selected: Set<String> = emptySet(),
    /** True once at least one entry is selected; the action bar replaces the normal one. */
    val isSelectionMode: Boolean = false,
    val isLoading: Boolean = false,
    /** Set when the directory could not be read. Never rendered as an empty folder. */
    val error: FsError? = null,
    /** Non-fatal problems reported by the engine, shown as a subtle notice. */
    val warnings: List<String> = emptyList(),
    /** True when this listing came from the root shell rather than our own process. */
    val viaRoot: Boolean = false,
    /** Capacity of the filesystem holding this path. */
    val storage: StorageStats? = null,
    /** How many entries the filter removed, so the UI can say "N hidden by filter". */
    val filteredOutCount: Int = 0,
    /** Total entries on disk in this directory, before filtering (for the statistics line). */
    val totalOnDisk: Int = 0,
) {
    val canGoBack: Boolean get() = historyIndex > 0
    val canGoForward: Boolean get() = historyIndex >= 0 && historyIndex < history.size - 1
    val canGoUp: Boolean get() = path != "/"

    /** Directories and files after filtering, for the statistics line. */
    val folderCount: Int get() = entries.count { it.isDirectory }
    val fileCount: Int get() = entries.count { !it.isDirectory }

    /** The selected entries, resolved against the loaded listing. */
    val selectedEntries: List<FileEntry> get() = entries.filter { it.path in selected }

    /** A single selected entry, or null when the selection is empty or has several items. */
    val singleSelection: FileEntry?
        get() = selectedEntries.singleOrNull()

    /** The entry the properties dialog should show. */
    val primarySelection: FileEntry? get() = selectedEntries.firstOrNull()
}

/** The immutable state the file browser screen renders. */
@Immutable
data class FileBrowserUiState(
    val left: PaneState = PaneState(PaneId.LEFT),
    val right: PaneState = PaneState(PaneId.RIGHT),
    /** Which panel keyboard focus and the primary actions apply to. */
    val activePane: PaneId = PaneId.LEFT,
    val sortSpec: SortSpec = SortSpec(),
    val filter: FileFilter = FileFilter(),
    val showHidden: Boolean = false,
    /** True while the two-panel layout is shown; false means a single full-width panel. */
    val dualPane: Boolean = true,
    val themeMode: io.rootexplorer.pro.ui.theme.ThemeMode = io.rootexplorer.pro.ui.theme.ThemeMode.SYSTEM,
    /** Root state, mirrored here so the UI does not have to hold the filesystem itself. */
    val rootStatus: io.rootexplorer.pro.core.root.RootStatus =
        io.rootexplorer.pro.core.root.RootStatus.Unknown,
    /** Null when storage access is fine; otherwise the explanation to show in a banner. */
    val accessWarning: String? = null,
    val bookmarks: List<String> = emptyList(),
    /** One-off user-facing message (operation result, refusal, unsupported action). */
    val message: String? = null,
    /** True while a root operation (chmod/chown) is in flight. */
    val rootBusy: Boolean = false,
    /**
     * The directory the user actually asked to open.
     *
     * When shared-storage access is missing at launch we cannot open it, so the panes start in
     * the app's private directory instead. This records where the user meant to be, so the
     * panes can move there once the permission is granted.
     */
    val intendedHome: String = "/storage/emulated/0",
    /** True while the panes are showing the app-private fallback instead of [intendedHome]. */
    val onFallbackHome: Boolean = false,
) {
    fun pane(id: PaneId): PaneState = if (id == PaneId.LEFT) left else right

    /** The panel that is currently focused. */
    val active: PaneState get() = pane(activePane)

    /** The panel that is not focused — the destination of cross-pane operations. */
    val inactive: PaneState get() = pane(activePane.other)
}

/** Build the initial state from persisted settings. */
fun initialUiState(settings: AppSettings): FileBrowserUiState = FileBrowserUiState(
    sortSpec = SortSpec(settings.sortField, settings.sortDirection),
    showHidden = settings.showHiddenFiles,
    themeMode = settings.themeMode,
    bookmarks = settings.bookmarks,
)

/** Apply filter + sort to a raw listing, producing what the panel displays. */
fun PaneState.withListing(
    listing: DirectoryListing,
    filter: FileFilter,
    sortSpec: SortSpec,
    showHidden: Boolean,
): PaneState {
    val visible = listing.entries.filter { showHidden || !it.isHidden }
    val passing = visible.filter { filter.matches(it) }
    return copy(
        path = listing.path,
        rawEntries = listing.entries,
        entries = sortSpec.apply(passing),
        totalOnDisk = listing.entries.size,
        filteredOutCount = visible.size - passing.size,
        isLoading = false,
        error = null,
        warnings = listing.warnings,
        viaRoot = listing.viaRoot,
    )
}
