package io.rootexplorer.pro.feature.installedapps

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.apk.AppKindFilter
import io.rootexplorer.pro.core.apk.AppSortField
import io.rootexplorer.pro.core.apk.InstalledApp
import io.rootexplorer.pro.core.apk.InstalledAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Installed-apps list state.
 *
 * The full list is held once and filtered/sorted in memory rather than re-queried: a device has
 * a few hundred packages at most, and re-enumerating on every keystroke would be far more
 * expensive than filtering a list already in hand.
 *
 * Icons are loaded lazily and cached. Rasterising every icon up front makes the first frame slow
 * on a device with many apps, so they are fetched when the row is first composed.
 */
class InstalledAppsViewModel(
    private val repository: InstalledAppRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(InstalledAppsUiState())
    val state: StateFlow<InstalledAppsUiState> = _state.asStateFlow()

    /** Every app, before filtering. */
    private var allApps: List<InstalledApp> = emptyList()

    private val iconCache = mutableMapOf<String, Drawable?>()

    init {
        refresh()
    }

    /** Re-read the package list. */
    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = repository.loadAll()
            result.fold(
                onSuccess = { apps ->
                    allApps = apps
                    _state.update {
                        it.copy(
                            apps = applyFilters(it, apps),
                            isLoading = false,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    allApps = emptyList()
                    _state.update {
                        it.copy(
                            apps = emptyList(),
                            isLoading = false,
                            error = describeLoadFailure(error),
                        )
                    }
                },
            )
        }
    }

    fun setQuery(query: String) {
        _state.update { current ->
            val updated = current.copy(query = query)
            updated.copy(apps = applyFilters(updated, allApps))
        }
    }

    fun setKindFilter(kind: AppKindFilter) {
        _state.update { current ->
            val updated = current.copy(kindFilter = kind)
            updated.copy(apps = applyFilters(updated, allApps))
        }
    }

    fun setSortField(field: AppSortField) {
        _state.update { current ->
            val updated = current.copy(sortField = field)
            updated.copy(apps = applyFilters(updated, allApps))
        }
    }

    fun toggleSortDirection() {
        _state.update { current ->
            val updated = current.copy(descending = !current.descending)
            updated.copy(apps = applyFilters(updated, allApps))
        }
    }

    /** The icon for a package, loaded and cached on first request. */
    fun iconFor(packageName: String): Drawable? {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]
        val icon = repository.loadIcon(packageName)
        iconCache[packageName] = icon
        return icon
    }

    /** Warm the icon cache off the main thread so scrolling stays smooth. */
    fun preloadIcons() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (app in allApps.take(ICON_PRELOAD_LIMIT)) {
                    if (!iconCache.containsKey(app.packageName)) {
                        iconCache[app.packageName] = repository.loadIcon(app.packageName)
                    }
                }
            }
            // Nudge the state so visible rows recompose with their icons available.
            _state.update { it.copy(loadingIcons = emptySet()) }
        }
    }

    fun launchIntent(packageName: String) = repository.launchIntent(packageName)

    fun detailsIntent(packageName: String) = repository.appDetailsIntent(packageName)

    /** What to copy when exporting [app]. */
    fun exportSources(app: InstalledApp) = repository.exportSources(app)

    /** The file name an exported part should get. */
    fun exportFileName(app: InstalledApp, sourcePath: String, index: Int): String =
        repository.exportFileName(app, sourcePath, index)

    fun find(packageName: String): InstalledApp? = allApps.firstOrNull { it.packageName == packageName }

    private fun applyFilters(state: InstalledAppsUiState, apps: List<InstalledApp>): List<InstalledApp> =
        repository.applyFilterAndSort(
            apps = apps,
            query = state.query,
            kind = state.kindFilter,
            sortField = state.sortField,
            descending = state.descending,
        )

    /**
     * Turn a query failure into something actionable.
     *
     * A SecurityException here means the package-visibility permission is missing, which is a
     * different problem from "this device has no apps" and must not be shown as an empty list.
     */
    private fun describeLoadFailure(error: Throwable): String = when (error) {
        is SecurityException ->
            "没有读取应用列表的权限（QUERY_ALL_PACKAGES）。无法显示已安装应用。"

        else -> "读取应用列表失败：${error.message ?: error.javaClass.simpleName}"
    }

    private companion object {
        /** Loading every icon at once would stall the first frame on a large device. */
        const val ICON_PRELOAD_LIMIT = 40
    }
}
