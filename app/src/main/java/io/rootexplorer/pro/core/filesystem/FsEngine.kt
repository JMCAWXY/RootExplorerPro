package io.rootexplorer.pro.core.filesystem

/**
 * The filesystem operations search needs.
 *
 * Search depends on this narrow interface rather than on a concrete filesystem class, which
 * keeps it testable on the JVM: a test supplies a fake that answers `list` and `scan` from an
 * in-memory tree, with no root shell and no device involved.
 */
interface FileLister {

    /** List one directory. */
    suspend fun list(path: String): ListingResult

    /**
     * Walk [path] depth-first, handing every entry to [visit].
     *
     * Exposed here as well as on [FsEngine] so that search can walk a tree through the same
     * object it lists with, which is what lets the fake in the unit tests stand in for the
     * real filesystem.
     */
    suspend fun scan(
        path: String,
        visit: suspend (FileEntry) -> Boolean,
        onError: (String, FsError) -> Unit = { _, _ -> },
    )
}

/**
 * The uniform filesystem interface the rest of the app talks to.
 *
 * Two implementations exist: [JavaFsEngine] (this process's own credentials) and
 * [RootFsEngine] (a root shell). The UI never learns which one served a request except
 * through [DirectoryListing.viaRoot], which it surfaces deliberately so the user knows why
 * an otherwise-unreadable path is visible.
 */
interface FsEngine {

    /** True when this engine performs its work with root privileges. */
    val requiresRoot: Boolean

    /** List [path]. Never throws for ordinary filesystem problems; returns a failure instead. */
    suspend fun list(path: String): ListingResult

    /** Read detailed attributes for a single [path]. */
    suspend fun stat(path: String): StatResult

    /** Capacity of the filesystem holding [path]. */
    suspend fun storage(path: String): StorageResult

    /** Resolve a symbolic link to its target, or null when [path] is not a link. */
    suspend fun readLink(path: String): String?

    /**
     * Walk [path] depth-first, handing every entry to [visit].
     *
     * Search uses this rather than repeated [list] calls so each engine can walk the tree in
     * the way that suits it: the Java engine enumerates a directory once, and the root engine
     * issues a single `find` for the whole tree instead of one process per directory.
     *
     * [visit] is called for entries inside [path] (not for [path] itself). Returning false
     * stops the walk immediately. The callback must be cheap: it runs on the walker's thread
     * and, for the root engine, while a `find` process is alive.
     *
     * Symbolic links are reported but never followed, so a link cycle cannot make the walk
     * run forever. [onError] receives a path that could not be read, so a partial walk can say
     * what it missed instead of silently returning fewer results.
     */
    suspend fun scan(
        path: String,
        visit: suspend (FileEntry) -> Boolean,
        onError: (String, FsError) -> Unit = { _, _ -> },
    )

    companion object {

        /**
         * Normalise a user-supplied path.
         *
         * Handles the things people actually type: missing leading slash, doubled slashes,
         * "." segments, and trailing slashes (except for the root itself). ".." is resolved
         * only when it is unambiguous, because on the root filesystem ".." from a mount point
         * does not always mean the lexical parent.
         */
        fun normalise(path: String): String {
            var p = path.trim()
            if (p.isEmpty()) return "/"
            if (!p.startsWith("/")) p = "/$p"
            val segments = ArrayDeque<String>()
            for (segment in p.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> segments.removeLastOrNull()
                    else -> segments.addLast(segment)
                }
            }
            if (segments.isEmpty()) return "/"
            return segments.joinToString(separator = "/", prefix = "/")
        }

        /** The lexical parent of [path], or null when [path] is already the root. */
        fun parentOf(path: String): String? {
            val normalised = normalise(path)
            if (normalised == "/") return null
            val index = normalised.lastIndexOf('/')
            return if (index <= 0) "/" else normalised.substring(0, index)
        }

        /** Append [name] to [parent], producing a normalised child path. */
        fun childOf(parent: String, name: String): String =
            normalise(if (parent == "/") "/$name" else "$parent/$name")
    }
}
