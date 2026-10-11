package io.rootexplorer.pro.service.trash

import android.content.Context
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.ShellQuote
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One item sitting in the recycle bin. */
data class TrashEntry(
    /** Directory name inside the trash root; unique and filesystem-safe. */
    val id: String,
    /** Where the item came from, so it can be put back. */
    val originalPath: String,
    /** When it was deleted, epoch millis. */
    val deletedAtMillis: Long,
    val isDirectory: Boolean,
    /** Size in bytes at the time of deletion; null when it could not be measured. */
    val sizeBytes: Long?,
) {
    /** The name to show: the last segment of the original path. */
    val displayName: String
        get() = originalPath.trimEnd('/').substringAfterLast('/').ifEmpty { originalPath }
}

/** Result of moving something to the recycle bin. */
sealed interface TrashResult {
    data class Success(val entry: TrashEntry) : TrashResult
    data class Failure(val reason: String) : TrashResult
}

/** Where the trash lives and how big it is. */
data class TrashLocation(
    val directory: String,
    val viaRoot: Boolean,
)

/**
 * The recycle bin.
 *
 * Why an in-app bin rather than the system one: Android has no public, universally available
 * trash API, and the brief asks for restore, permanent delete and cleanup that actually work.
 *
 * Design decisions worth stating:
 *
 * * **It is an index, not a directory listing.** `index.tsv` records where each item came
 *   from; the trash directory alone cannot tell you that, so a bin built by listing
 *   directories could never restore anything.
 * * **It lives on the same volume as the data when root is available.** That makes trashing a
 *   rename rather than a multi-gigabyte copy. Without root it falls back to the app's private
 *   directory, where a cross-volume move is a real copy — slow but honest, and reported.
 * * **Nothing is deleted silently.** Entries whose payload vanished are dropped from the index
 *   at read time; entries whose index line is corrupt are skipped rather than guessed at.
 */
class TrashRepository(
    private val context: Context,
    private val rootShell: RootShell,
    private val rootAvailable: () -> Boolean,
) {

    private val mutex = Mutex()

    /**
     * The trash root.
     *
     * With root: `/data/media/0/.RootExplorerPro/trash`, which is on shared storage itself, so
     * moving a file in is a rename. Android's media scanner ignores dot-directories, so the bin
     * does not show up in the gallery or in other file managers.
     */
    fun location(): TrashLocation {
        val sharedTrash = File(SHARED_TRASH_PATH)
        if (rootAvailable()) {
            return TrashLocation(SHARED_TRASH_PATH, viaRoot = true)
        }
        if (sharedTrash.isDirectory && sharedTrash.canWrite()) {
            return TrashLocation(SHARED_TRASH_PATH, viaRoot = false)
        }
        val privateDir = File(context.filesDir, "trash")
        return TrashLocation(privateDir.absolutePath, viaRoot = false)
    }

    private fun payloadDir(): File = File(location().directory, "items")

    private fun indexFile(): File = File(location().directory, "index.tsv")

    /** Ensure the trash directories exist. Returns null on success, else the reason. */
    private suspend fun ensureLayout(): String? {
        val location = location()
        val root = File(location.directory)

        if (root.isDirectory) {
            payloadDir().mkdirs()
            return null
        }

        if (location.viaRoot) {
            val command = "mkdir -p ${ShellQuote.quote(payloadDir().absolutePath)} 2>&1"
            val result = rootShell.runBlocking(command)
            if (!payloadDir().isDirectory && !result.isSuccess) {
                return "无法创建回收站目录：${result.combinedOutput.ifBlank { "mkdir 失败" }}"
            }
            return null
        }

        return if (root.mkdirs() || root.isDirectory) {
            payloadDir().mkdirs()
            null
        } else {
            "无法创建回收站目录：${root.absolutePath}"
        }
    }

    /** Every readable entry, newest first. Never throws; a broken index yields an empty bin. */
    suspend fun entries(): List<TrashEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndexLocked().sortedByDescending { it.deletedAtMillis } }
    }

    /** Total bytes currently held, and how many entries there are. */
    suspend fun usage(): Pair<Long, Int> = withContext(Dispatchers.IO) {
        val list = mutex.withLock { readIndexLocked() }
        list.sumOf { it.sizeBytes ?: 0L } to list.size
    }

    /**
     * Move [path] into the bin.
     *
     * The item is moved, not copied, whenever the trash is on the same volume. If the move
     * cannot be done by rename (different filesystem, or no permission) it is refused rather
     * than silently turned into a slow copy that might half-finish — the caller can then tell
     * the user why.
     */
    suspend fun moveToTrash(path: String, sizeBytes: Long?): TrashResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLayout()?.let { return@withLock TrashResult.Failure(it) }

            val source = File(FsEngine.normalise(path))
            if (!source.exists() && !isSymlink(source)) {
                return@withLock TrashResult.Failure("文件不存在：${source.absolutePath}")
            }

            // Refuse to trash the trash.
            val sourcePath = source.absolutePath
            if (sourcePath.startsWith(location().directory)) {
                return@withLock TrashResult.Failure("不能把回收站内部的项目再放入回收站")
            }

            val id = newId()
            val payload = File(payloadDir(), id)

            val moved = tryMoveToTrash(source, payload)
            if (!moved) {
                return@withLock TrashResult.Failure(
                    "无法移入回收站：可能是跨存储卷或权限不足（${source.absolutePath}）",
                )
            }

            val entry = TrashEntry(
                id = id,
                originalPath = source.absolutePath,
                deletedAtMillis = System.currentTimeMillis(),
                isDirectory = payload.isDirectory,
                sizeBytes = sizeBytes ?: measureSize(payload),
            )
            val updated = readIndexLocked() + entry
            writeIndexLocked(updated)

            TrashResult.Success(entry)
        }
    }

    /**
     * Put an entry back where it came from.
     *
     * If the original location is occupied the restore is refused instead of overwriting —
     * restoring must never destroy newer data.
     */
    suspend fun restore(id: String): TrashResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndexLocked()
            val entry = index.firstOrNull { it.id == id }
                ?: return@withLock TrashResult.Failure("回收站索引中没有该条目：$id")

            val payload = File(payloadDir(), id)
            if (!payload.exists()) {
                writeIndexLocked(index - entry)
                return@withLock TrashResult.Failure("回收站中的文件已丢失，已从索引移除：${entry.originalPath}")
            }

            val target = File(entry.originalPath)
            if (target.exists() || isSymlink(target)) {
                return@withLock TrashResult.Failure("原位置已存在同名项目，未覆盖：${entry.originalPath}")
            }

            target.parentFile?.let { parent ->
                if (!parent.isDirectory) {
                    val created = if (rootAvailable()) {
                        rootShell.runBlocking("mkdir -p ${ShellQuote.quote(parent.absolutePath)} 2>&1")
                        parent.isDirectory
                    } else {
                        parent.mkdirs()
                    }
                    if (!created) {
                        return@withLock TrashResult.Failure("无法重建原目录：${parent.absolutePath}")
                    }
                }
            }

            if (!tryMove(payload, target)) {
                return@withLock TrashResult.Failure("还原失败：可能是跨存储卷或权限不足")
            }

            writeIndexLocked(index - entry)
            TrashResult.Success(entry.copy(originalPath = target.absolutePath))
        }
    }

    /** Delete one entry for good. */
    suspend fun deletePermanently(id: String): TrashResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndexLocked()
            val entry = index.firstOrNull { it.id == id }
                ?: return@withLock TrashResult.Failure("回收站索引中没有该条目：$id")

            val payload = File(payloadDir(), id)
            val removed = deleteRecursively(payload)
            if (!removed && payload.exists()) {
                return@withLock TrashResult.Failure("删除失败：${payload.absolutePath}")
            }
            writeIndexLocked(index - entry)
            TrashResult.Success(entry)
        }
    }

    /**
     * Empty the bin.
     *
     * [olderThanMillis] and [largerThanBytes] let the caller implement the cleanup rules the
     * brief asks for without another engine. Returns how many entries were removed.
     */
    suspend fun empty(
        olderThanMillis: Long? = null,
        largerThanBytes: Long? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndexLocked()
            val now = System.currentTimeMillis()
            val targets = index.filter { entry ->
                val ageOk = olderThanMillis?.let { now - entry.deletedAtMillis >= it } ?: true
                val sizeOk = largerThanBytes?.let { (entry.sizeBytes ?: 0L) >= it } ?: true
                ageOk && sizeOk
            }

            var removed = 0
            val failures = mutableListOf<String>()
            for (entry in targets) {
                val payload = File(payloadDir(), entry.id)
                val ok = deleteRecursively(payload) || !payload.exists()
                if (ok) removed++ else failures += entry.displayName
            }

            // Drop the entries we did remove, and keep any that survived so the user can retry.
            val removedIds = targets.filter { entry ->
                val payload = File(payloadDir(), entry.id)
                !payload.exists()
            }.map { it.id }.toSet()
            writeIndexLocked(index.filterNot { it.id in removedIds })

            if (failures.isEmpty()) {
                Result.success(removed)
            } else {
                Result.failure(IOException("部分项目未能删除：${failures.joinToString("、")}"))
            }
        }
    }

    /** Rewrite the index from what is actually on disk, dropping entries whose payload is gone. */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val index = readIndexLocked()
            val alive = index.filter { File(payloadDir(), it.id).exists() }
            val dropped = index.size - alive.size
            if (dropped > 0) writeIndexLocked(alive)
            dropped
        }
    }

    // ------------------------------------------------------------------ internals

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Move a file or directory into the bin.
     *
     * A plain rename is tried first because it is atomic and instant. `mv` under root is the
     * fallback for paths this process cannot write; it is also a rename on the same volume.
     * A cross-volume situation is deliberately *not* papered over with a copy.
     */
    private suspend fun tryMoveToTrash(source: File, payload: File): Boolean {
        if (source.renameTo(payload)) return true

        if (rootAvailable()) {
            // -n refuses to clobber, which cannot happen with a fresh UUID name anyway.
            val command = "mv -n ${ShellQuote.quote(source.absolutePath)} ${ShellQuote.quote(payload.absolutePath)} 2>&1"
            val result = rootShell.runBlocking(command)
            if (payload.exists() && !source.exists()) return true
            if (!result.isSuccess) return false
        }
        return payload.exists() && !source.exists()
    }

    private suspend fun tryMove(from: File, to: File): Boolean {
        if (from.renameTo(to)) return true
        if (rootAvailable()) {
            val command = "mv -n ${ShellQuote.quote(from.absolutePath)} ${ShellQuote.quote(to.absolutePath)} 2>&1"
            rootShell.runBlocking(command)
            if (to.exists() && !from.exists()) return true
        }
        return to.exists() && !from.exists()
    }

    private suspend fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true

        if (rootAvailable()) {
            val command = "rm -rf ${ShellQuote.quote(file.absolutePath)} 2>&1"
            rootShell.runBlocking(command)
            return !file.exists()
        }
        return file.deleteRecursively()
    }

    private fun measureSize(file: File): Long? = try {
        if (file.isFile) {
            file.length()
        } else {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    } catch (e: Exception) {
        null
    }

    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    // ------------------------------------------------------------------ index format

    /**
     * Read the index.
     *
     * Format: one tab-separated record per line, `id  originalPath  deletedAt  isDirectory
     * size`. Paths are escaped so a file name containing a tab or a newline cannot break the
     * index — those characters are legal on Linux, so this is not hypothetical.
     *
     * A malformed line is skipped rather than guessed at; losing one bin entry is far better
     * than restoring a file to the wrong directory.
     */
    private fun readIndexLocked(): List<TrashEntry> {
        val file = indexFile()
        if (!file.isFile) return emptyList()
        return try {
            file.readLines()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { decodeRecord(it) }
                .toList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun writeIndexLocked(entries: List<TrashEntry>) {
        val file = indexFile()
        file.parentFile?.mkdirs()
        val text = entries.joinToString(separator = "\n", postfix = "\n") { encodeRecord(it) }
        file.writeText(text)
    }

    companion object {
        /**
         * Shared-storage trash path.
         *
         * `/data/media/0` is the canonical underlying path for `/sdcard`, so trashing from
         * shared storage is a rename. It needs root to reach, which is why the fallback exists.
         */
        const val SHARED_TRASH_PATH = "/data/media/0/.RootExplorerPro/trash"

        /** Index file name, exposed so tests and diagnostics can refer to it. */
        const val INDEX_FILE_NAME = "index.tsv"

        /**
         * Escape a field for the tab-separated index.
         *
         * File names on Linux may legally contain tabs and newlines, either of which would
         * otherwise split one record into two and corrupt every record after it.
         */
        internal fun escapeField(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
        }

        /** Reverse of [escapeField]. Unknown escapes are passed through unchanged. */
        internal fun unescapeField(value: String): String = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                if (ch == '\\' && index + 1 < value.length) {
                    when (value[index + 1]) {
                        '\\' -> { append('\\'); index += 2 }
                        't' -> { append('\t'); index += 2 }
                        'n' -> { append('\n'); index += 2 }
                        'r' -> { append('\r'); index += 2 }
                        else -> { append(ch); index++ }
                    }
                } else {
                    append(ch)
                    index++
                }
            }
        }

        /** Render one index record line. Exposed for round-trip testing. */
        internal fun encodeRecord(entry: TrashEntry): String = listOf(
            entry.id,
            escapeField(entry.originalPath),
            entry.deletedAtMillis.toString(),
            if (entry.isDirectory) "1" else "0",
            entry.sizeBytes?.toString() ?: "",
        ).joinToString("\t")

        /** Parse one index record, or null when the line is not a valid record. */
        internal fun decodeRecord(line: String): TrashEntry? {
            val parts = line.split('\t')
            if (parts.size < 5) return null
            val id = parts[0].takeIf { it.isNotBlank() } ?: return null
            val path = unescapeField(parts[1])
            val deletedAt = parts[2].toLongOrNull() ?: return null
            return TrashEntry(
                id = id,
                originalPath = path,
                deletedAtMillis = deletedAt,
                isDirectory = parts[3] == "1",
                sizeBytes = parts[4].toLongOrNull(),
            )
        }
    }
}
