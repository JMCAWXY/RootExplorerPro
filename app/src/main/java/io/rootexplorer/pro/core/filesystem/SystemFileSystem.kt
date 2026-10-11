package io.rootexplorer.pro.core.filesystem

import io.rootexplorer.pro.core.root.RootAccess
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single filesystem facade the rest of the app uses.
 *
 * It owns both engines and decides which one can serve a request:
 *
 * * root granted -> the root engine, because it can also read the system paths and it gives
 *   the numeric owner and full mode;
 * * otherwise    -> the Java engine, which still handles all of shared storage.
 *
 * [rootStatus] is exposed so the UI can show the real state and so callers can tell the
 * difference between "this path is empty" and "we cannot see this path without root".
 */
class SystemFileSystem(
    val rootAccess: RootAccess,
    private val rootShell: RootShell,
) : FileLister {

    val javaEngine: FsEngine = JavaFsEngine()
    val rootEngine: FsEngine = RootFsEngine(rootShell)

    val rootStatus: RootStatus get() = rootAccess.status

    /** The engine that will serve a request right now. */
    fun activeEngine(): FsEngine = if (rootAccess.status.isUsable) rootEngine else javaEngine

    /**
     * List [path], probing for root and retrying once when that is what the path needs.
     *
     * Why the retry exists: the engine is chosen from a cached root status, and at launch that
     * status is still `Unknown` because the app deliberately does not probe until something needs
     * root. A listing issued in that window goes to the Java engine, is denied on a root-only
     * path, and — before this change — kept failing until the app was restarted, which is exactly
     * the "grant root, still permission denied, restart and it works" behaviour reported from a
     * real device.
     *
     * Now a denial on a root-only path triggers the probe and re-runs the request through the root
     * engine, so the first tap on `/` or `/data` authorises and succeeds. When there is genuinely
     * no root, the probe is not repeated (the status is cached) and the error stays as it was.
     */
    override suspend fun list(path: String): ListingResult {
        var result = activeEngine().list(path)

        if (needsRootRetry((result as? ListingResult.Failure)?.error) && rootAccess.probe().isUsable) {
            // Re-run through the root engine; the shell may have been granted just now.
            result = rootEngine.list(path)
        }

        // Report a genuine denial as "root required" so the UI can offer authorization instead
        // of showing an empty folder.
        val error = (result as? ListingResult.Failure)?.error
        if (error is FsError.PermissionDenied && isTypicallyRootOnly(path)) {
            return ListingResult.Failure(FsError.RootRequired(path))
        }
        return result
    }

    /**
     * True when the failure is one a root retry could fix.
     *
     * Only "permission denied" counts, and only while the request went through the Java engine.
     * A missing path or a broken filesystem would fail identically as root, so retrying those
     * would prompt the user for authorization for nothing.
     */
    private fun needsRootRetry(error: FsError?): Boolean {
        if (rootAccess.status.isUsable) return false
        if (activeEngine() !== javaEngine) return false
        return error is FsError.PermissionDenied
    }

    /**
     * Read attributes for [path], preferring the root engine and retrying once when the path
     * needs root. The properties dialog reaches files the list can only see as root.
     */
    suspend fun stat(path: String): StatResult {
        var result = activeEngine().stat(path)

        if (needsRootRetry((result as? StatResult.Failure)?.error) && rootAccess.probe().isUsable) {
            result = rootEngine.stat(path)
        }

        val error = (result as? StatResult.Failure)?.error
        if (error is FsError.PermissionDenied && isTypicallyRootOnly(path)) {
            return StatResult.Failure(FsError.RootRequired(path))
        }
        return result
    }

    /**
     * Walk a tree with the best engine available.
     *
     * A permission failure on a path that is normally root-only is reported as needing root,
     * so a search can say "authorize root to search here" instead of quietly returning fewer
     * results. Ordinary denials inside shared storage are passed through as they are.
     */
    override suspend fun scan(
        path: String,
        visit: suspend (io.rootexplorer.pro.core.filesystem.FileEntry) -> Boolean,
        onError: (String, io.rootexplorer.pro.core.filesystem.FsError) -> Unit,
    ) {
        // Decide the engine *before* emitting anything, so the walk is never run twice. A walk
        // that starts while the root status is still Unknown would run as the app's own UID and
        // report a root-only tree as entirely unreadable, so the probe happens up front here
        // rather than being retried afterwards (a retry would duplicate every entry already
        // handed to the caller).
        if (isTypicallyRootOnly(path) && !rootAccess.status.isUsable && activeEngine() === javaEngine) {
            rootAccess.probe()
        }

        val engine = activeEngine()
        val usingRoot = engine === rootEngine
        engine.scan(
            path = path,
            visit = visit,
            onError = { failedPath, error ->
                val mapped = if (!usingRoot &&
                    error is FsError.PermissionDenied &&
                    isTypicallyRootOnly(failedPath)
                ) {
                    FsError.RootRequired(failedPath)
                } else {
                    error
                }
                onError(failedPath, mapped)
            },
        )
    }

    /**
     * Read capacity for the filesystem holding [path].
     *
     * StatFs is used even when root is available: it reports the mount's own figures without
     * spawning a process, and `df` is only a fallback for paths StatFs cannot resolve.
     */
    suspend fun storage(path: String): StorageResult {
        val javaResult = javaEngine.storage(path)
        if (javaResult is StorageResult.Success) return javaResult
        if (rootAccess.status.isUsable) {
            val rootResult = rootEngine.storage(path)
            if (rootResult is StorageResult.Success) return rootResult
        }
        return javaResult
    }

    /** Resolve a symbolic link, or null when [path] is not one. */
    suspend fun readLink(path: String): String? {
        val viaRoot = if (rootAccess.status.isUsable) rootEngine.readLink(path) else null
        return viaRoot ?: javaEngine.readLink(path)
    }

    /**
     * Probe root on the user's behalf and report the result.
     *
     * This is the only place that triggers the root manager's authorization prompt, and it
     * is only called from an explicit user action.
     */
    suspend fun requestRoot(force: Boolean = false): RootStatus = rootAccess.probe(force)

    /** Paths that are, on every normal Android build, unreadable without root. */
    private fun isTypicallyRootOnly(path: String): Boolean {
        val normalised = FsEngine.normalise(path)
        return ROOT_ONLY_PREFIXES.any { normalised == it || normalised.startsWith("$it/") }
    }

    companion object {
        private val ROOT_ONLY_PREFIXES = listOf(
            "/data",
            "/system",
            "/vendor",
            "/product",
            "/system_ext",
            "/metadata",
            "/root",
            "/init.rc",
        )
    }
}

/** Well-known locations offered by the drawer and the bookmark editor. */
object WellKnownPaths {

    data class Shortcut(
        val label: String,
        val path: String,
        /** True when the path needs root to be useful. */
        val needsRoot: Boolean,
        /** True when the app should check for all-files access before browsing it. */
        val needsAllFilesAccess: Boolean = false,
    )

    val shortcuts: List<Shortcut> = listOf(
        Shortcut("根目录", "/", needsRoot = true),
        Shortcut("内部存储", "/storage/emulated/0", needsRoot = false, needsAllFilesAccess = true),
        Shortcut("Download", "/storage/emulated/0/Download", needsRoot = false, needsAllFilesAccess = true),
        Shortcut("DCIM", "/storage/emulated/0/DCIM", needsRoot = false, needsAllFilesAccess = true),
        Shortcut("Android/data", "/storage/emulated/0/Android/data", needsRoot = false, needsAllFilesAccess = true),
        Shortcut("/data", "/data", needsRoot = true),
        Shortcut("/data/adb", "/data/adb", needsRoot = true),
        Shortcut("/data/local/tmp", "/data/local/tmp", needsRoot = true),
        Shortcut("/system", "/system", needsRoot = true),
        Shortcut("/vendor", "/vendor", needsRoot = true),
        Shortcut("/product", "/product", needsRoot = true),
        Shortcut("/metadata", "/metadata", needsRoot = true),
    )

    /** The directory the app opens when it has no saved home. */
    val defaultHome: String = "/storage/emulated/0"
}
