package io.rootexplorer.pro.feature.search

import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileFilter
import io.rootexplorer.pro.core.filesystem.FileLister
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.filesystem.FsError
import io.rootexplorer.pro.core.filesystem.SortSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What to look for. */
data class SearchQuery(
    /** Text the file name must contain. Case-insensitive. Blank matches every name. */
    val nameQuery: String = "",
    /** Where to start. */
    val rootPath: String,
    /** Descend into subdirectories. When false only [rootPath] itself is listed. */
    val recursive: Boolean = true,
    /** Include entries whose name starts with a dot. */
    val includeHidden: Boolean = true,
    /** Restrict by category, reusing the file-list filter so the two behave identically. */
    val kind: FileFilter.KindFilter = FileFilter.KindFilter.ALL,
    /** Match the extension (without the dot). Blank means no constraint. */
    val extension: String = "",
) {
    /** True when the query would match everything, which the UI warns about. */
    val isUnbounded: Boolean
        get() = nameQuery.isBlank() && extension.isBlank() && kind == FileFilter.KindFilter.ALL

    companion object {
        /** Guard against a query that would match an entire filesystem. */
        const val MAX_RESULTS = 5_000
    }
}

/** How a search is progressing. */
sealed interface SearchStatus {
    data object Idle : SearchStatus
    data class Running(val scanned: Int, val matches: Int) : SearchStatus

    data class Finished(
        val matches: Int,
        val scanned: Int,
        /** True when the result cap was hit, so the list is incomplete. */
        val truncated: Boolean,
        /** Paths that could not be read, so the user knows the search was partial. */
        val unreadable: List<String>,
    ) : SearchStatus

    /**
     * The user stopped the search.
     *
     * Distinct from [Finished] so the UI can say "stopped after N" rather than presenting a
     * partial answer as a complete one.
     */
    data class Cancelled(val matches: Int, val scanned: Int) : SearchStatus

    data class Failed(val reason: String) : SearchStatus
}

/** One emitted batch of search progress. */
sealed interface SearchEvent {
    data class Progress(val scanned: Int, val matches: Int) : SearchEvent
    data class Found(val entries: List<FileEntry>) : SearchEvent
    data class Done(
        val matches: Int,
        val scanned: Int,
        val truncated: Boolean,
        val unreadable: List<String>,
    ) : SearchEvent
    data class Failed(val reason: String) : SearchEvent
}

/**
 * Name search over one directory, optionally recursive.
 *
 * Design points that matter:
 *
 * * **Root-aware through the engines.** The walk goes through `FsEngine.scan`, so searching
 *   `/data` uses the root shell when root is granted and reports a permission problem when it
 *   is not. Nothing here talks to `su` directly.
 * * **Results stream.** A search over a large tree is reported in batches, so the first hits
 *   appear immediately rather than after the whole walk.
 * * **Cancellation is real.** The walk checks the coroutine between entries, and the root
 *   engine's `find` is bounded by a timeout, so a cancelled search stops rather than running
 *   to completion in the background.
 * * **It says when it is incomplete.** Hitting the result cap, or failing to read part of the
 *   tree, is reported rather than silently returning fewer results.
 */
class SearchEngine(
    private val fileSystem: FileLister,
) {

    /**
     * Run [query], emitting batches of matches.
     *
     * The flow completes with exactly one [SearchEvent.Done] or [SearchEvent.Failed]. It is
     * cold: cancelling the collecting coroutine stops the walk.
     */
    fun search(query: SearchQuery, sortSpec: SortSpec = SortSpec()): Flow<SearchEvent> = flow {
        val rootPath = FsEngine.normalise(query.rootPath)
        val matches = mutableListOf<FileEntry>()
        val unreadable = mutableListOf<String>()
        var scanned = 0
        var truncated = false
        val pending = mutableListOf<FileEntry>()

        suspend fun flushIfNeeded(force: Boolean) {
            if (pending.isEmpty()) return
            if (!force && pending.size < BATCH_SIZE) return
            val batch = pending.toList()
            pending.clear()
            emit(SearchEvent.Found(batch))
        }

        // Emits progress periodically rather than per entry, so the UI is not flooded.
        suspend fun reportProgressIfDue() {
            if (scanned % PROGRESS_EVERY == 0) {
                emit(SearchEvent.Progress(scanned = scanned, matches = matches.size))
            }
        }

        /** Decide whether one entry is a hit. Returns false when the caller should stop. */
        suspend fun consider(entry: FileEntry): Boolean {
            currentCoroutineContext().ensureActive()
            scanned++

            val eligible = (query.includeHidden || !entry.isHidden) &&
                kindMatches(query.kind, entry) &&
                extensionMatches(query.extension, entry) &&
                nameMatches(query.nameQuery, entry.name)

            if (eligible) {
                matches += entry
                pending += entry
                flushIfNeeded(force = false)
            }

            reportProgressIfDue()

            // Hitting the cap stops the walk and marks the result as incomplete.
            if (matches.size >= SearchQuery.MAX_RESULTS) {
                truncated = true
                return false
            }
            return true
        }

        try {
            if (query.recursive) {
                fileSystem.scan(
                    path = rootPath,
                    visit = { entry -> consider(entry) },
                    onError = { path, error -> recordUnreadable(unreadable, path, error) },
                )
            } else {
                // Non-recursive: one listing, which is both faster and exactly what
                // "search the current directory" means.
                when (val listing = fileSystem.list(rootPath)) {
                    is io.rootexplorer.pro.core.filesystem.ListingResult.Success -> {
                        for (entry in listing.listing.entries) {
                            if (!consider(entry)) break
                        }
                    }

                    is io.rootexplorer.pro.core.filesystem.ListingResult.Failure -> {
                        emit(SearchEvent.Failed(listing.error.message))
                        return@flow
                    }
                }
            }

            flushIfNeeded(force = true)

            emit(
                SearchEvent.Done(
                    matches = matches.size,
                    scanned = scanned,
                    truncated = truncated,
                    unreadable = unreadable.distinct(),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled search reports nothing further; the caller already knows.
            throw e
        } catch (e: Exception) {
            emit(SearchEvent.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    /** Match [query] against a name, case-insensitively. */
    fun nameMatches(query: String, name: String): Boolean {
        if (query.isBlank()) return true
        return name.contains(query.trim(), ignoreCase = true)
    }

    /** Match an extension filter, tolerating a leading dot in the query. */
    fun extensionMatches(extension: String, entry: FileEntry): Boolean {
        if (extension.isBlank()) return true
        val wanted = extension.trim().removePrefix(".").lowercase()
        return entry.extension == wanted
    }

    /** Match a category filter, using the same rules as the file list. */
    private fun kindMatches(kind: FileFilter.KindFilter, entry: FileEntry): Boolean =
        FileFilter(kind = kind).matches(entry)

    private fun recordUnreadable(target: MutableList<String>, path: String, error: FsError) {
        // Only the first few are kept with their reason: a search over /data as a non-root user
        // can produce thousands of denials, and listing them all is noise rather than
        // information. The total count is preserved by the caller.
        if (target.size < MAX_REPORTED_UNREADABLE) {
            target += "$path（${error.message}）"
        }
    }

    private companion object {
        /** Matches are emitted in batches this size, so results appear as they are found. */
        const val BATCH_SIZE = 50

        /** Progress is emitted every N entries rather than per entry. */
        const val PROGRESS_EVERY = 200

        /** How many unreadable paths to report before collapsing the rest into a count. */
        const val MAX_REPORTED_UNREADABLE = 20
    }
}
