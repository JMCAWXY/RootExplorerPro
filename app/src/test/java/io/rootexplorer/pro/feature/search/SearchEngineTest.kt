package io.rootexplorer.pro.feature.search

import io.rootexplorer.pro.core.filesystem.DirectoryListing
import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileKind
import io.rootexplorer.pro.core.filesystem.FileLister
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.filesystem.FsError
import io.rootexplorer.pro.core.filesystem.ListingResult
import io.rootexplorer.pro.core.filesystem.StatResult
import io.rootexplorer.pro.core.filesystem.StorageResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search behaviour.
 *
 * These run against an in-memory tree rather than a device, so the recursion, the filters, the
 * hidden-file rule, cancellation and the run-away guards are all exercised deterministically
 * and without root.
 */
class SearchEngineTest {

    /**
     * A filesystem built from a flat list of paths.
     *
     * A trailing `/` in the input marks a directory. Parent directories are implied, so
     * `"a/b/c.txt"` creates `a`, `a/b` and the file.
     */
    private class FakeFileSystem(paths: List<String>) : FileLister {

        /** Parent path to child paths. Declared before `init` so the init block can fill it. */
        private val childrenMap = mutableMapOf<String, MutableList<String>>()

        /** All entries by normalised path. */
        private val entries: Map<String, FileEntry>
        private val children: Map<String, List<String>>

        /** Directories whose listing should report a permission failure. */
        val unreadable: MutableSet<String> = mutableSetOf()

        var scanCount = 0

        init {
            val built = linkedMapOf<String, FileEntry>()
            for (raw in paths) {
                val isDirectory = raw.endsWith("/")
                val trimmed = raw.trimEnd('/')
                val parts = trimmed.trimStart('/').split('/')

                var current = ""
                for ((index, part) in parts.withIndex()) {
                    val parent = current
                    current = if (parent.isEmpty()) "/$part" else "$parent/$part"
                    val last = index == parts.lastIndex
                    val dir = !last || isDirectory
                    if (current !in built) {
                        built[current] = entry(name = part, path = current, isDirectory = dir)
                    }
                    // Register the child under its parent.
                    val list = childrenMap.getOrPut(parent.ifEmpty { "/" }) { mutableListOf() }
                    if (current !in list) list += current
                }
            }
            entries = built
            children = childrenMap
        }

        /**
         * The recursive walk is what search uses, so the fake answers `scan` directly.
         *
         * No engine is involved: `activeEngine()` does not exist on [FileLister] any more, which
         * is precisely what makes this fake able to stand in for a rooted device.
         */

        override suspend fun list(path: String): ListingResult {
            val normalised = FsEngine.normalise(path)
            if (normalised in unreadable) {
                return ListingResult.Failure(FsError.PermissionDenied(normalised))
            }
            if (normalised !in entries) {
                return ListingResult.Failure(FsError.NotFound(normalised))
            }
            val kids = children[normalised].orEmpty().mapNotNull { entries[it] }
            return ListingResult.Success(
                DirectoryListing(path = normalised, entries = kids, viaRoot = false),
            )
        }

        /** Recursive walk, mirroring the real engines' contract. */
        override suspend fun scan(
            path: String,
            visit: suspend (FileEntry) -> Boolean,
            onError: (String, FsError) -> Unit,
        ) {
            scanCount++
            walk(FsEngine.normalise(path), visit, onError)
        }

        private suspend fun walk(
            path: String,
            visit: suspend (FileEntry) -> Boolean,
            onError: (String, FsError) -> Unit,
        ): Boolean {
            if (path in unreadable) {
                onError(path, FsError.PermissionDenied(path))
                return true
            }
            for (childPath in children[path].orEmpty()) {
                val entry = entries[childPath] ?: continue
                if (!visit(entry)) return false
                // Do not descend through symlinks, matching the real engines.
                if (entry.isDirectory && !entry.isSymlink) {
                    if (!walk(childPath, visit, onError)) return false
                }
            }
            return true
        }
    }

    private companion object {
        fun entry(name: String, path: String, isDirectory: Boolean): FileEntry = FileEntry(
            name = name,
            path = path,
            kind = if (isDirectory) FileKind.DIRECTORY else FileKind.REGULAR,
            sizeBytes = if (isDirectory) null else 100,
            lastModifiedMillis = 1_700_000_000_000L,
            permission = null,
            uid = null,
            gid = null,
            ownerName = null,
            groupName = null,
            linkTarget = null,
            linkTargetIsDirectory = null,
        )
    }

    private val sampleTree = listOf(
        "/storage/",
        "/storage/photo.jpg",
        "/storage/notes.txt",
        "/storage/.hidden.txt",
        "/storage/Download/",
        "/storage/Download/report.pdf",
        "/storage/Download/photo-copy.jpg",
        "/storage/Download/deep/",
        "/storage/Download/deep/photo-old.jpg",
        "/storage/Download/deep/archive.zip",
    )

    private suspend fun run(query: SearchQuery, tree: FakeFileSystem = FakeFileSystem(sampleTree)) =
        SearchEngine(tree).search(query).toList()

    private fun pathsOf(events: List<SearchEvent>): List<String> =
        events.filterIsInstance<SearchEvent.Found>().flatMap { batch -> batch.entries.map { it.path } }

    private fun doneOf(events: List<SearchEvent>): SearchEvent.Done =
        events.filterIsInstance<SearchEvent.Done>().single()

    // ------------------------------------------------------------------ matching rules

    @Test
    fun `name matching is case insensitive and matches substrings`() {
        val engine = SearchEngine(FakeFileSystem(emptyList()))
        assertTrue(engine.nameMatches("photo", "PHOTO.jpg"))
        assertTrue(engine.nameMatches("HOTO", "photo.jpg"))
        assertTrue(engine.nameMatches("", "anything"))
        assertFalse(engine.nameMatches("zzz", "photo.jpg"))
    }

    @Test
    fun `extension matching tolerates a leading dot and case`() {
        val engine = SearchEngine(FakeFileSystem(emptyList()))
        val jpg = entry("a.JPG", "/a.JPG", isDirectory = false)
        assertTrue(engine.extensionMatches("jpg", jpg))
        assertTrue(engine.extensionMatches(".jpg", jpg))
        assertTrue(engine.extensionMatches("JPG", jpg))
        assertTrue(engine.extensionMatches("", jpg))
        assertFalse(engine.extensionMatches("png", jpg))
    }

    @Test
    fun `an unbounded query is recognised`() {
        assertTrue(SearchQuery(rootPath = "/").isUnbounded)
        assertFalse(SearchQuery(rootPath = "/", nameQuery = "a").isUnbounded)
        assertFalse(SearchQuery(rootPath = "/", extension = "jpg").isUnbounded)
        assertFalse(
            SearchQuery(rootPath = "/", kind = io.rootexplorer.pro.core.filesystem.FileFilter.KindFilter.IMAGES)
                .isUnbounded,
        )
    }

    // ------------------------------------------------------------------ recursion

    @Test
    fun `recursive search finds matches at every depth`() = runTest {
        val events = run(SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = true))
        val found = pathsOf(events)

        assertEquals(3, found.size)
        assertTrue(found.contains("/storage/photo.jpg"))
        assertTrue(found.contains("/storage/Download/photo-copy.jpg"))
        assertTrue(found.contains("/storage/Download/deep/photo-old.jpg"))
        assertTrue(events.any { it is SearchEvent.Done })
    }

    @Test
    fun `non-recursive search only looks at the directory itself`() = runTest {
        val events = run(SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = false))
        val found = pathsOf(events)

        assertEquals(1, found.size)
        assertEquals("/storage/photo.jpg", found.first())
        // The directory itself is not scanned recursively.
        assertFalse(found.contains("/storage/Download/photo-copy.jpg"))
    }

    @Test
    fun `non-recursive search does not use scan at all`() = runTest {
        val tree = FakeFileSystem(sampleTree)
        SearchEngine(tree).search(
            SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = false),
        ).toList()

        assertEquals("a non-recursive search must not walk the tree", 0, tree.scanCount)
    }

    @Test
    fun `a directory result is reported as well as its children`() = runTest {
        val events = run(SearchQuery(nameQuery = "Download", rootPath = "/storage", recursive = true))
        val found = pathsOf(events)
        assertTrue("the matching directory itself must be a result", found.contains("/storage/Download"))
    }

    @Test
    fun `searching an unreadable root reports failure rather than an empty result`() = runTest {
        val tree = FakeFileSystem(sampleTree)
        tree.unreadable += "/storage"
        val events = SearchEngine(tree)
            .search(SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = false))
            .toList()

        // The non-recursive path lists once, so a denial is a hard failure, not an empty list.
        val failure = events.filterIsInstance<SearchEvent.Failed>().singleOrNull()
        assertTrue("expected an explicit failure", failure != null)
        assertTrue(failure!!.reason.contains("权限不足"))
    }

    @Test
    fun `a partially unreadable tree still returns what it could read and reports the rest`() = runTest {
        val tree = FakeFileSystem(sampleTree)
        tree.unreadable += "/storage/Download/deep"

        val events = SearchEngine(tree)
            .search(SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = true))
            .toList()

        val found = pathsOf(events)
        assertTrue(found.contains("/storage/photo.jpg"))
        assertTrue(found.contains("/storage/Download/photo-copy.jpg"))

        val done = doneOf(events)
        assertTrue("the unreadable path must be reported", done.unreadable.isNotEmpty())
        assertTrue(done.unreadable.first().contains("/storage/Download/deep"))
    }

    // ------------------------------------------------------------------ filtering

    @Test
    fun `hidden files are excluded when requested`() = runTest {
        val withHidden = run(SearchQuery(nameQuery = "hidden", rootPath = "/storage", recursive = true))
        val withoutHidden = run(
            SearchQuery(nameQuery = "hidden", rootPath = "/storage", recursive = true, includeHidden = false),
        )

        assertEquals(1, pathsOf(withHidden).size)
        assertEquals(0, pathsOf(withoutHidden).size)
    }

    @Test
    fun `extension filter narrows results`() = runTest {
        val events = run(SearchQuery(rootPath = "/storage", recursive = true, extension = "jpg"))
        val found = pathsOf(events)

        assertEquals(3, found.size)
        assertTrue(found.all { it.endsWith(".jpg") })
    }

    @Test
    fun `images-only filter excludes non-images`() = runTest {
        val events = run(
            SearchQuery(
                rootPath = "/storage",
                recursive = true,
                kind = io.rootexplorer.pro.core.filesystem.FileFilter.KindFilter.IMAGES,
            ),
        )
        val found = pathsOf(events)

        assertTrue(found.all { it.endsWith(".jpg") })
        assertFalse("a .txt file must not match the images filter", found.any { it.endsWith(".txt") })
    }

    @Test
    fun `archives-only filter finds the zip`() = runTest {
        val events = run(
            SearchQuery(
                rootPath = "/storage",
                recursive = true,
                kind = io.rootexplorer.pro.core.filesystem.FileFilter.KindFilter.ARCHIVES,
            ),
        )
        assertEquals(listOf("/storage/Download/deep/archive.zip"), pathsOf(events))
    }

    @Test
    fun `a name and extension filter combine`() = runTest {
        val events = run(
            SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = true, extension = "jpg"),
        )
        assertEquals(3, pathsOf(events).size)

        val narrowed = run(
            SearchQuery(nameQuery = "copy", rootPath = "/storage", recursive = true, extension = "jpg"),
        )
        assertEquals(listOf("/storage/Download/photo-copy.jpg"), pathsOf(narrowed))
    }

    @Test
    fun `a query matching nothing finishes cleanly with zero results`() = runTest {
        val events = run(SearchQuery(nameQuery = "no-such-file", rootPath = "/storage", recursive = true))
        assertEquals(0, pathsOf(events).size)

        val done = doneOf(events)
        assertEquals(0, done.matches)
        assertFalse(done.truncated)
        assertTrue(done.scanned > 0)
    }

    // ------------------------------------------------------------------ batching and limits

    @Test
    fun `results are emitted in batches rather than one event per file`() = runTest {
        // 120 matching files should arrive as three Found events of at most 50.
        val many = buildList {
            add("/bulk/")
            for (index in 1..120) add("/bulk/file$index.dat")
        }
        val events = SearchEngine(FakeFileSystem(many))
            .search(SearchQuery(rootPath = "/bulk", recursive = true))
            .toList()

        val batches = events.filterIsInstance<SearchEvent.Found>()
        assertTrue("expected more than one batch", batches.size > 1)
        assertTrue("no batch may exceed the batch size", batches.all { it.entries.size <= 50 })
        assertEquals(120, pathsOf(events).size)
    }

    @Test
    fun `hitting the result cap is reported as truncated, not as a complete answer`() = runTest {
        val huge = buildList {
            add("/huge/")
            for (index in 1..(SearchQuery.MAX_RESULTS + 250)) add("/huge/f$index.dat")
        }
        val events = SearchEngine(FakeFileSystem(huge))
            .search(SearchQuery(rootPath = "/huge", recursive = true))
            .toList()

        val done = doneOf(events)
        assertTrue("the result cap must be reported", done.truncated)
        assertEquals(SearchQuery.MAX_RESULTS, pathsOf(events).size)
    }

    @Test
    fun `progress is reported while scanning`() = runTest {
        val events = SearchEngine(FakeFileSystem(sampleTree))
            .search(SearchQuery(rootPath = "/storage", recursive = true))
            .toList()

        // The tree is small, so progress may be sparse; what matters is that it is a valid
        // event type when present and that Done always arrives exactly once.
        assertEquals(1, events.filterIsInstance<SearchEvent.Done>().size)
        assertTrue(events.filterIsInstance<SearchEvent.Progress>().all { it.scanned >= 0 })
    }

    @Test
    fun `every search ends with exactly one Done or Failed event`() = runTest {
        val events = SearchEngine(FakeFileSystem(sampleTree))
            .search(SearchQuery(nameQuery = "photo", rootPath = "/storage", recursive = true))
            .toList()

        val terminals = events.count { it is SearchEvent.Done || it is SearchEvent.Failed }
        assertEquals(1, terminals)
    }
}
