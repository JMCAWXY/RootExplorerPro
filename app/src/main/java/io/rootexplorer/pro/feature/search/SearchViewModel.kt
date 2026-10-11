package io.rootexplorer.pro.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.SortSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The state the search screen renders. */
data class SearchUiState(
    val query: SearchQuery? = null,
    val status: SearchStatus = SearchStatus.Idle,
    val results: List<FileEntry> = emptyList(),
    /** True while a search is in flight; the cancel button is shown instead of the search one. */
    val isRunning: Boolean = false,
    val sortSpec: SortSpec = SortSpec(),
) {
    val hasResults: Boolean get() = results.isNotEmpty()

    /** The result cap was hit, so this list is not the complete answer. */
    val isTruncated: Boolean
        get() = (status as? SearchStatus.Finished)?.truncated == true

    val unreadable: List<String>
        get() = (status as? SearchStatus.Finished)?.unreadable.orEmpty()
}

/**
 * Search state and lifecycle.
 *
 * A search owns a coroutine that is cancelled when [cancel] is called or the view model is
 * cleared, so leaving the screen stops the walk instead of leaving it running. Results arrive
 * in batches and are appended, which means the first hits show up while the tree is still
 * being walked.
 */
class SearchViewModel(
    private val engine: SearchEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Start a search, replacing any search already running.
     *
     * [unboundedAllowed] is the caller's decision: a query with no name, extension or category
     * constraint matches every file, so the screen asks for confirmation before running one.
     */
    fun search(query: SearchQuery) {
        cancel()

        _state.value = SearchUiState(
            query = query,
            status = SearchStatus.Running(scanned = 0, matches = 0),
            results = emptyList(),
            isRunning = true,
            sortSpec = _state.value.sortSpec,
        )

        searchJob = viewModelScope.launch {
            engine.search(query, _state.value.sortSpec).collect { event ->
                when (event) {
                    is SearchEvent.Progress -> _state.update { current ->
                        current.copy(
                            status = SearchStatus.Running(
                                scanned = event.scanned,
                                matches = event.matches,
                            ),
                        )
                    }

                    is SearchEvent.Found -> _state.update { current ->
                        current.copy(
                            results = current.results + event.entries,
                            status = SearchStatus.Running(
                                scanned = (current.status as? SearchStatus.Running)?.scanned ?: 0,
                                matches = current.results.size + event.entries.size,
                            ),
                        )
                    }

                    is SearchEvent.Done -> _state.update { current ->
                        current.copy(
                            isRunning = false,
                            status = SearchStatus.Finished(
                                matches = event.matches,
                                scanned = event.scanned,
                                truncated = event.truncated,
                                unreadable = event.unreadable,
                            ),
                        )
                    }

                    is SearchEvent.Failed -> _state.update { current ->
                        current.copy(
                            isRunning = false,
                            status = SearchStatus.Failed(event.reason),
                        )
                    }
                }
            }
        }
    }

    /** Stop the running search. The partial results are kept, marked as incomplete. */
    fun cancel() {
        val wasRunning = searchJob?.isActive == true
        searchJob?.cancel()
        searchJob = null

        if (!wasRunning) return
        _state.update { current ->
            val scanned = (current.status as? SearchStatus.Running)?.scanned ?: 0
            current.copy(
                isRunning = false,
                status = SearchStatus.Cancelled(matches = current.results.size, scanned = scanned),
            )
        }
    }

    /** Clear the results and go back to the empty state. */
    fun clear() {
        cancel()
        _state.value = SearchUiState(sortSpec = _state.value.sortSpec)
    }

    /** Re-sort the results already in hand; no re-search is needed. */
    fun setSortSpec(spec: SortSpec) {
        _state.update { current ->
            current.copy(sortSpec = spec, results = spec.apply(current.results))
        }
    }

    /**
     * Stop the walk when the view model goes away.
     *
     * This matters more here than for a typical screen: a recursive search can be a root `find`
     * over the whole filesystem, so leaving it running after the screen is gone would keep a
     * privileged process alive and burn battery for a result nobody will see.
     */
    override fun onCleared() {
        searchJob?.cancel()
        searchJob = null
    }
}
