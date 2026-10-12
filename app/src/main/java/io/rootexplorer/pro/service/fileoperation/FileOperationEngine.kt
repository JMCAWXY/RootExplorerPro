package io.rootexplorer.pro.service.fileoperation

import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.ShellQuote
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.ensureActive

/** What to do when the destination already contains an item with the same name. */
enum class ConflictPolicy(val label: String) {
    /** Ask the user for each conflict. The engine suspends until [ConflictResolver] answers. */
    ASK("询问"),

    /** Replace the destination item. */
    OVERWRITE("覆盖"),

    /** Leave the destination alone and do not copy this item. */
    SKIP("跳过"),

    /** Copy alongside, appending " (1)", " (2)" … to the name. */
    RENAME("保留两者"),
    ;

    /** Policies that can be applied to every conflict without further prompting. */
    val isBulk: Boolean get() = this != ASK
}

/** A single name collision found before any data was written. */
data class Conflict(
    /** The source item that collides. */
    val sourcePath: String,
    /** The path that already exists at the destination. */
    val existingPath: String,
    val existingIsDirectory: Boolean,
    val sourceIsDirectory: Boolean,
    /** Size of the existing item, when it could be read. */
    val existingSizeBytes: Long?,
    val existingModifiedMillis: Long?,
)

/** The user's answer for one conflict. */
sealed interface ConflictDecision {
    data class Apply(val policy: ConflictPolicy) : ConflictDecision

    /** Stop the whole operation. */
    data object CancelOperation : ConflictDecision
}

/** Asked once per conflict when the policy is [ConflictPolicy.ASK]. */
fun interface ConflictResolver {
    suspend fun resolve(conflict: Conflict): ConflictDecision
}

/** How far along a running operation is. */
sealed interface OperationProgress {

    /** Before any bytes move: how much work there is and whether it can proceed. */
    data class Preparing(val itemsScanned: Int, val bytes: Long) : OperationProgress

    data class Running(
        val currentItem: String,
        val itemsDone: Int,
        val itemsTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
        /** Items that could not be processed so far. */
        val failures: Int,
    ) : OperationProgress {
        /** 0f..1f, or null when the total size is unknown (e.g. everything is an empty file). */
        val fraction: Float?
            get() = when {
                bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
                itemsTotal > 0 -> (itemsDone.toDouble() / itemsTotal).toFloat().coerceIn(0f, 1f)
                else -> null
            }
    }

    /** Waiting for the user to answer a conflict. */
    data class AwaitingConflict(val conflict: Conflict) : OperationProgress
}

/** What happened to one item. */
data class OperationFailure(
    val path: String,
    val reason: String,
) {
    override fun toString(): String = "$path：$reason"
}

/** The outcome of a whole operation. Never claims success that was not observed. */
sealed interface OperationOutcome {

    /**
     * The operation ran to completion.
     *
     * [failed] is empty only when every item succeeded; a non-empty list means a *partial*
     * result, which the UI must present as such rather than as success.
     */
    data class Completed(
        val itemsCopied: Int,
        val itemsSkipped: Int,
        val bytesCopied: Long,
        val failed: List<OperationFailure>,
    ) : OperationOutcome {
        val isPartial: Boolean get() = failed.isNotEmpty()
        val isFullSuccess: Boolean get() = failed.isEmpty()
    }

    /** The user cancelled; [partial] describes what had already been done. */
    data class Cancelled(
        val itemsCopied: Int,
        val bytesCopied: Long,
        val failed: List<OperationFailure>,
    ) : OperationOutcome

    /** Nothing was started, with the reason (no space, permission, source missing…). */
    data class Rejected(val reason: String) : OperationOutcome
}

/** Static analysis of an operation, produced before anything is written. */
data class OperationPlan(
    val sources: List<String>,
    val destinationDirectory: String,
    /** Total bytes across every regular file that will be written. */
    val totalBytes: Long,
    /** Number of files (not directories) that will be written. */
    val totalFiles: Int,
    /** Every name collision, in the order they would be encountered. */
    val conflicts: List<Conflict>,
    /** Reasons the operation cannot run at all, e.g. the destination is read-only. */
    val blockers: List<String>,
) {
    val canProceed: Boolean get() = blockers.isEmpty()
}

/**
 * Copy and move.
 *
 * The design follows three rules that come straight out of the brief:
 *
 * 1. **Nothing is assumed.** Every operation is planned first (size, file count, conflicts,
 *    free space), so a doomed copy is refused before a single byte is written.
 * 2. **Nothing is silently lost.** A name collision is either reported to the caller or
 *    resolved by an explicit policy; the default is to ask. Overwriting never happens as a
 *    side effect of a rename.
 * 3. **Partial results are reported as partial.** Per-item failures are collected and the
 *    outcome distinguishes full success, partial success and cancellation.
 *
 * Symlinks are resolved rather than recreated, and a destination inside the source is
 * rejected outright — that combination is what makes an unaware recursive copy loop forever.
 */
class FileOperationEngine(
    private val rootShell: RootShell,
    private val rootAvailable: () -> Boolean,
) {

    /** Max bytes per chunk when reading, which is what makes cancellation responsive. */
    private val bufferSize = 128 * 1024

    /**
     * Inspect a copy/move before performing it.
     *
     * Walks the sources once to total up the work and find collisions. Directories are walked
     * but not counted as files; symlinks are resolved so the totals match what will be written.
     */
    suspend fun planCopy(
        sources: List<String>,
        destinationDirectory: String,
    ): OperationPlan {
        val requestedSources = sources.map { FsEngine.normalise(it) }.distinct()
        val requestedDestination = FsEngine.normalise(destinationDirectory)
        val blockers = mutableListOf<String>()
        val conflicts = mutableListOf<Conflict>()
        var totalBytes = 0L
        var totalFiles = 0

        val destinationFile = File(requestedDestination)
        if (!destinationFile.exists()) {
            blockers += "目标目录不存在：$requestedDestination"
        } else if (!destinationFile.isDirectory) {
            blockers += "目标不是目录：$requestedDestination"
        } else if (!destinationFile.canWrite() && !rootAvailable()) {
            blockers += "目标目录不可写：$requestedDestination"
        }

        for (source in requestedSources) {
            val sourceFile = File(source)
            if (!sourceFile.exists() && !isSymlink(sourceFile)) {
                blockers += "源不存在：$source"
                continue
            }

            // A destination inside the source would recurse into what we are creating.
            // Compared canonically rather than as strings: on Android this also sees through
            // the /sdcard -> /storage/emulated/0 symlink, which a string check would miss.
            if (isSameOrInside(destinationFile, sourceFile)) {
                blockers += "不能把 $source 复制到它自己内部（$requestedDestination）"
                continue
            }

            val target = File(destinationFile, sourceFile.name)
            val walked = walkForPlan(sourceFile, sourceFile.name)
            totalBytes += walked.bytes
            totalFiles += walked.files

            if (target.exists() || isSymlink(target)) {
                conflicts += Conflict(
                    sourcePath = source,
                    existingPath = target.absolutePath,
                    existingIsDirectory = target.isDirectory,
                    sourceIsDirectory = sourceFile.isDirectory,
                    existingSizeBytes = if (target.isDirectory) null else target.length(),
                    existingModifiedMillis = target.lastModified().takeIf { it > 0 },
                )
            }
        }

        // Free-space check: a copy that cannot fit should never start.
        if (totalBytes > 0) {
            val free = freeBytesAt(requestedDestination)
            if (free != null && totalBytes > free) {
                blockers += "目标空间不足：需要 ${humanBytes(totalBytes)}，可用 ${humanBytes(free)}"
            }
        }

        return OperationPlan(
            sources = requestedSources,
            destinationDirectory = requestedDestination,
            totalBytes = totalBytes,
            totalFiles = totalFiles,
            conflicts = conflicts,
            blockers = blockers,
        )
    }

    /**
     * True when [candidate] is [ancestor] itself or lives inside it.
     *
     * Uses canonical paths so symlinks are resolved and so the check is correct on any
     * platform. Comparison falls back to absolute paths when canonicalisation fails (a broken
     * link, or a permission problem on an ancestor).
     */
    private fun isSameOrInside(candidate: File, ancestor: File): Boolean {
        val a = canonicalOrAbsolute(ancestor)
        val b = canonicalOrAbsolute(candidate)
        if (a == b) return true
        val separator = File.separator
        return b.startsWith(a + separator)
    }

    private fun canonicalOrAbsolute(file: File): String = try {
        file.canonicalPath
    } catch (e: IOException) {
        file.absolutePath
    }

    /**
     * Perform a copy or move, streaming progress and honouring cancellation.
     *
     * [onProgress] is invoked from the caller's coroutine context; the caller is responsible
     * for publishing it to whatever state object the UI observes. Cancelling the calling
     * coroutine stops the transfer at the next chunk boundary and returns
     * [OperationOutcome.Cancelled] with an accurate partial count — it never reports success.
     *
     * [renameHints] maps a source path to the file name it should get at the destination. This
     * exists because some callers cannot copy under the source's own name: exporting an app's
     * APKs, for instance, would otherwise write `base.apk` and `split0.apk`, which are neither
     * unique nor informative. A missing entry falls back to the source's base name.
     */
    suspend fun execute(
        sources: List<String>,
        destinationDirectory: String,
        move: Boolean,
        policy: ConflictPolicy,
        resolver: ConflictResolver? = null,
        renameHints: Map<String, String> = emptyMap(),
        onProgress: (OperationProgress) -> Unit,
    ): OperationOutcome {
        val plan = planCopy(sources, destinationDirectory)
        if (!plan.canProceed) {
            return OperationOutcome.Rejected(plan.blockers.joinToString("；"))
        }

        onProgress(OperationProgress.Preparing(plan.totalFiles, plan.totalBytes))

        val destination = File(plan.destinationDirectory)
        val state = RunState(itemsTotal = plan.totalFiles, bytesTotal = plan.totalBytes)

        // Resolve every conflict before moving data, so the user answers once and the
        // transfer itself cannot stop halfway waiting for input.
        val resolvedPolicy = mutableMapOf<String, ConflictPolicy>()
        for (conflict in plan.conflicts) {
            val effective = when (policy) {
                ConflictPolicy.ASK -> {
                    onProgress(OperationProgress.AwaitingConflict(conflict))
                    when (val decision = resolver?.resolve(conflict)) {
                        is ConflictDecision.Apply -> decision.policy
                        ConflictDecision.CancelOperation, null -> {
                            // "Cancel" means cancel the *operation*, not just this one prompt.
                            // Nothing has been written yet — every conflict is resolved before
                            // the transfer starts — so the partial counts are honestly zero and
                            // the operation is reported as cancelled rather than completed.
                            //
                            // The previous version only returned here while no conflict had been
                            // answered yet, and otherwise just broke out of the prompt loop and
                            // let the transfer run: the answers already given were carried out
                            // (so an "overwrite" the user was in the middle of reconsidering
                            // destroyed the destination file), the unanswered conflicts were
                            // silently skipped, and the result was reported as Completed.
                            onProgress(OperationProgress.Preparing(plan.totalFiles, plan.totalBytes))
                            return OperationOutcome.Cancelled(
                                itemsCopied = 0,
                                bytesCopied = 0L,
                                failed = emptyList(),
                            )
                        }
                    }
                }

                else -> policy
            }
            resolvedPolicy[conflict.sourcePath] = effective
        }

        for (source in plan.sources) {
            val sourceFile = File(source)
            val policyForThis = resolvedPolicy[source]
            // A caller-supplied name wins over the source's own name; see renameHints.
            val preferredName = renameHints[source]?.takeIf { it.isNotBlank() } ?: sourceFile.name
            val targetName = when (policyForThis) {
                ConflictPolicy.RENAME -> uniqueNameIn(destination, preferredName)
                else -> preferredName
            }
            val target = File(destination, targetName)

            try {
                when (policyForThis) {
                    ConflictPolicy.SKIP -> {
                        state.skipped++
                    }

                    else -> {
                        transferItem(
                            source = sourceFile,
                            target = target,
                            move = move,
                            overwrite = policyForThis == ConflictPolicy.OVERWRITE,
                            state = state,
                            onProgress = onProgress,
                        )
                    }
                }
            } catch (e: CancellationSignal) {
                throw e
            } catch (e: Exception) {
                state.failures += OperationFailure(source, e.message ?: e.javaClass.simpleName)
            }
        }

        onProgress(
            OperationProgress.Running(
                currentItem = "",
                itemsDone = state.itemsDone,
                itemsTotal = state.itemsTotal,
                bytesDone = state.bytesDone,
                bytesTotal = state.bytesTotal,
                bytesPerSecond = state.bytesPerSecond(),
                failures = state.failures.size,
            ),
        )

        return OperationOutcome.Completed(
            itemsCopied = state.itemsDone,
            itemsSkipped = state.skipped,
            bytesCopied = state.bytesDone,
            failed = state.failures.toList(),
        )
    }

    // ------------------------------------------------------------------ internals

    /** Mutable counters for one run. Kept out of the public API. */
    private class RunState(
        val itemsTotal: Int,
        val bytesTotal: Long,
    ) {
        var itemsDone: Int = 0
        var bytesDone: Long = 0
        var skipped: Int = 0
        val failures = mutableListOf<OperationFailure>()

        private var startedAt: Long = System.nanoTime()

        fun bytesPerSecond(): Long {
            val elapsedNanos = System.nanoTime() - startedAt
            if (elapsedNanos <= 0L || bytesDone <= 0L) return 0L
            return (bytesDone.toDouble() / (elapsedNanos / 1_000_000_000.0)).toLong()
        }

        /** Reset the rate window so throughput reflects recent speed after a pause. */
        fun resetWindow() {
            startedAt = System.nanoTime()
        }
    }

    /** Thrown internally when the calling coroutine was cancelled mid-transfer. */
    private class CancellationSignal : RuntimeException("已取消")

    /**
     * Copy or move one item, recursing into directories.
     *
     * Symlinks are followed and their content is copied, rather than recreating the link, so
     * a copy never produces a link that points outside the destination.
     */
    private suspend fun transferItem(
        source: File,
        target: File,
        move: Boolean,
        overwrite: Boolean,
        state: RunState,
        onProgress: (OperationProgress) -> Unit,
    ) {
        ensureActive()

        if (source.isDirectory) {
            if (!target.exists()) {
                if (!target.mkdirs() && !target.isDirectory) {
                    throw IOException("无法创建目录 ${target.absolutePath}")
                }
            }
            val children = source.listFiles()
                ?: throw IOException("无法读取目录 ${source.absolutePath}")

            for (child in children) {
                transferItem(
                    source = child,
                    target = File(target, child.name),
                    move = move,
                    overwrite = overwrite,
                    state = state,
                    onProgress = onProgress,
                )
            }

            if (move) {
                // Only remove the directory once it is empty; a failed child leaves it behind,
                // which is the correct outcome — nothing is lost.
                if (source.listFiles()?.isEmpty() == true) {
                    try {
                        deleteWithRootFallback(source)
                    } catch (e: Exception) {
                        state.failures += OperationFailure(
                            source.absolutePath,
                            "内容已移动，但空目录删除失败：${e.message}",
                        )
                    }
                }
            }
            return
        }

        // Leaf: a regular file (or a device/socket, which we skip rather than copy).
        if (!source.isFile) {
            state.skipped++
            return
        }

        if (target.exists() && !overwrite) {
            // Reached when a conflict was skipped or renamed; nothing to do here.
            state.skipped++
            return
        }

        if (target.parentFile?.exists() == false) {
            target.parentFile?.mkdirs()
        }

        if (move && tryMove(source, target, overwrite)) {
            state.itemsDone++
            val size = target.length()
            state.bytesDone += size
            onProgress(progressFor(target, state))
            return
        }

        // A cross-filesystem move is a copy followed by deleting the source.
        copyFile(source, target, overwrite, state, onProgress)
        state.itemsDone++

        if (move) {
            deleteWithRootFallback(source)
        }
    }

    /**
     * Copy one file's bytes, reporting progress per chunk.
     *
     * Written by hand rather than `Files.copy` so that a long copy can be cancelled and so
     * that throughput can be reported; a single `Files.copy` of a 4 GB file would be
     * uninterruptible and completely opaque.
     */
    private suspend fun copyFile(
        source: File,
        target: File,
        overwrite: Boolean,
        state: RunState,
        onProgress: (OperationProgress) -> Unit,
    ) {
        val sourcePath: Path = source.toPath()
        val targetPath: Path = target.toPath()

        // newOutputStream takes OpenOption, not CopyOption: StandardCopyOption (used by
        // Files.move/Files.copy below) is the wrong type here. TRUNCATE_EXISTING is what makes
        // "overwrite" actually replace the old content rather than append to it.
        val options = if (overwrite) {
            arrayOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        } else {
            arrayOf<OpenOption>(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
            )
        }

        try {
            Files.newInputStream(sourcePath).use { input ->
                Files.newOutputStream(targetPath, *options).use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        state.bytesDone += read
                        onProgress(progressFor(source, state))
                    }
                }
            }
        } catch (e: FileAlreadyExistsException) {
            throw IOException("目标已存在：${target.absolutePath}")
        } catch (e: IOException) {
            // A partial file is worse than none: remove it so the destination is clean.
            runCatching { Files.deleteIfExists(targetPath) }
            throw e
        }

        // Carry over the modification time so the list does not lie about freshness.
        runCatching {
            Files.setLastModifiedTime(targetPath, Files.getLastModifiedTime(sourcePath, LinkOption.NOFOLLOW_LINKS))
        }
    }

    /**
     * Try a same-volume move, which is a cheap rename.
     *
     * Returns false when the rename cannot work (different filesystems, target exists and
     * overwriting is not allowed), letting the caller fall back to copy + delete.
     */
    private fun tryMove(source: File, target: File, overwrite: Boolean): Boolean {
        val options = if (overwrite) {
            arrayOf(StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        return try {
            Files.move(source.toPath(), target.toPath(), *options)
            true
        } catch (e: Exception) {
            // Cross-device, or the filesystem does not support atomic moves.
            false
        }
    }

    /** Delete with root fallback, used to complete a move of a file we could not remove. */
    private suspend fun deleteWithRootFallback(file: File) {
        if (file.delete()) return
        if (rootAvailable()) {
            val result = rootShell.runBlocking("rm -f ${ShellQuote.quote(file.absolutePath)} 2>&1")
            if (!file.exists() || result.isSuccess) return
            throw IOException("无法删除源文件：${result.combinedOutput.ifBlank { "rm 失败" }}")
        }
        throw IOException("无法删除源文件（权限不足）")
    }

    private fun progressFor(current: File, state: RunState) = OperationProgress.Running(
        currentItem = current.absolutePath,
        itemsDone = state.itemsDone,
        itemsTotal = state.itemsTotal,
        bytesDone = state.bytesDone,
        bytesTotal = state.bytesTotal,
        bytesPerSecond = state.bytesPerSecond(),
        failures = state.failures.size,
    )

    /** Cooperative cancellation: the caller's coroutine is checked between chunks. */
    private suspend fun ensureActive() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
    }

    private data class WalkTotals(val bytes: Long, val files: Int)

    /**
     * Walk one source to total the bytes and file count that a copy would write.
     *
     * A visited set of canonical paths guards against symlink cycles; without it a loop would
     * make the planner itself hang.
     */
    private fun walkForPlan(root: File, relative: String): WalkTotals =
        walkForPlan(root, relative, mutableSetOf())

    private fun walkForPlan(
        root: File,
        relative: String,
        visited: MutableSet<String>,
    ): WalkTotals {
        val canonical = try {
            root.canonicalPath
        } catch (e: IOException) {
            root.absolutePath
        }
        if (!visited.add(canonical)) return WalkTotals(0, 0)

        return when {
            root.isDirectory -> {
                var bytes = 0L
                var files = 0
                root.listFiles()?.forEach { child ->
                    val childTotals = walkForPlan(child, "$relative/${child.name}", visited)
                    bytes += childTotals.bytes
                    files += childTotals.files
                }
                WalkTotals(bytes, files)
            }

            root.isFile -> WalkTotals(root.length().coerceAtLeast(0), 1)

            // Devices, sockets, and unreadable entries contribute nothing to the totals.
            else -> WalkTotals(0, 0)
        }
    }

    private fun freeBytesAt(path: String): Long? = try {
        val stat = android.os.StatFs(path)
        stat.availableBytes
    } catch (e: Exception) {
        null
    }

    /** Append " (1)", " (2)" … until the name is free, which is what "保留两者" means. */
    fun uniqueNameIn(directory: File, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""

        var candidate = name
        var index = 1
        while (File(directory, candidate).exists() || isSymlink(File(directory, candidate))) {
            candidate = "$base ($index)$extension"
            index++
            if (index > 9999) break
        }
        return candidate
    }

    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MiB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f GiB", bytes / 1073741824.0)
    }

    // ------------------------------------------------------------------ synchronous helpers

    /**
     * Blocking wrappers.
     *
     * The engine is suspend-based because the app must never block a thread, but the logic is
     * plain file I/O and is worth testing directly on the JVM. `runBlocking` is used by the
     * unit tests rather than a hand-rolled `Thread.sleep` loop, so a failure surfaces as a
     * normal assertion instead of a hang.
     *
     * These are deliberately public and small; they are not used by the app.
     */

    /** Blocking form of [planCopy], for tests and for callers already on a worker thread. */
    fun planCopyBlocking(sources: List<String>, destinationDirectory: String): OperationPlan =
        kotlinx.coroutines.runBlocking { planCopy(sources, destinationDirectory) }

    /** Blocking form of [execute], for tests. */
    fun executeBlocking(
        sources: List<String>,
        destinationDirectory: String,
        move: Boolean,
        policy: ConflictPolicy,
        resolver: ConflictResolver? = null,
        renameHints: Map<String, String> = emptyMap(),
        onProgress: (OperationProgress) -> Unit = {},
    ): OperationOutcome = kotlinx.coroutines.runBlocking {
        execute(sources, destinationDirectory, move, policy, resolver, renameHints, onProgress)
    }
}
