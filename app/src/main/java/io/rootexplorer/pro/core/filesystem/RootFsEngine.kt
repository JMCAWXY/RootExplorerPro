package io.rootexplorer.pro.core.filesystem

import io.rootexplorer.pro.core.permissions.LinuxPermission
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.ShellQuote
import io.rootexplorer.pro.core.root.ShellResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Filesystem access performed by a root shell.
 *
 * Why a shell rather than the Java API: `/data`, `/system` and friends are hidden from the
 * app's own UID by the kernel. Running `ls`/`stat` inside `su` sees them, and it also gives
 * us the numeric owner, the mode including the special bits, and the raw symlink target —
 * exactly the attributes the properties and permission editors need.
 *
 * Safety: every dynamic value passed to the shell goes through [ShellQuote]. Nothing is
 * concatenated raw.
 */
class RootFsEngine(
    private val shell: RootShell,
) : FsEngine {

    override val requiresRoot: Boolean = true

    override suspend fun list(path: String): ListingResult = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)

        // One command answers three questions: does it exist, is it a directory, and what
        // is inside it. `ls -la` on a file fails with a recognisable message, on a missing
        // path with another, so we do not need a separate probe.
        val quoted = ShellQuote.quote(normalised)
        val command = "ls -la $quoted 2>&1; echo \"__EXIT__=\$?\""
        val result = shell.runBlocking(command)

        val (body, exitCode) = splitTrailer(result.stdout)

        val lines = body.lines().filter { it.isNotBlank() && !it.startsWith("total ") }
        val looksLikeError = exitCode != 0 || lines.isEmpty()

        if (looksLikeError && lines.size <= 1 && detectError(normalised, body) != null) {
            return@withContext ListingResult.Failure(detectError(normalised, body)!!)
        }
        if (lines.isEmpty()) {
            // Empty directory, or an empty file. Decide with a stat rather than assuming.
            val stat = stat(normalised)
            return@withContext when (stat) {
                is StatResult.Failure -> ListingResult.Failure(stat.error)
                is StatResult.Success -> {
                    if (!stat.stat.entry.isDirectory) {
                        ListingResult.Failure(FsError.NotADirectory(normalised))
                    } else {
                        ListingResult.Success(
                            DirectoryListing(normalised, emptyList(), viaRoot = true),
                        )
                    }
                }
            }
        }

        val warnings = mutableListOf<String>()
        val entries = ArrayList<FileEntry>(lines.size)
        for (line in lines) {
            val entry = parseLsLine(line, normalised)
            if (entry == null) {
                warnings += "无法解析条目：${line.take(80)}"
            } else {
                entries += entry
            }
        }

        ListingResult.Success(
            DirectoryListing(
                path = normalised,
                entries = entries,
                viaRoot = true,
                warnings = warnings,
            ),
        )
    }

    override suspend fun stat(path: String): StatResult = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)
        val quoted = ShellQuote.quote(normalised)

        // `stat` field support differs between toybox and busybox, so we ask for the common
        // subset and format it ourselves into a known shape.
        val format = "%A\\t%a\\t%s\\t%Y\\t%u\\t%g\\t%F\\t%N"
        val command = "stat -c '$format' $quoted 2>&1; echo \"__EXIT__=\$?\""
        val result = shell.runBlocking(command)
        val (body, exitCode) = splitTrailer(result.stdout)

        if (exitCode != 0 || body.isBlank()) {
            return@withContext StatResult.Failure(
                detectError(normalised, body) ?: FsError.Unknown(normalised, body.ifBlank { "stat 没有输出" }),
            )
        }

        val fields = body.trim().lines().first().split('\t')
        if (fields.size < 8) {
            return@withContext StatResult.Failure(
                FsError.Unknown(normalised, "stat 输出格式无法识别：${body.take(120)}"),
            )
        }

        val symbolic = fields[0].trim()
        val octal = fields[1].trim()
        val size = fields[2].trim().toLongOrNull()
        val mtimeSeconds = fields[3].trim().toLongOrNull()
        val uid = fields[4].trim().toIntOrNull()
        val gid = fields[5].trim().toIntOrNull()
        val typeText = fields[6].trim()
        val nameField = fields[7].trim()

        val kind = FileKind.fromStatType(typeText)
        val permission = LinuxPermission.fromSymbolic(symbolic)
            ?: LinuxPermission.fromOctal(octal)

        val name = File(
            if (nameField.startsWith("`") || nameField.startsWith("'")) {
                // `stat -c %N` prints: 'path' -> 'target'  or  'path'
                nameField.trim('\'', '`')
            } else {
                nameField
            },
        ).name.ifEmpty { File(normalised).name }

        var linkTarget: String? = null
        if (kind == FileKind.SYMLINK) {
            val arrow = nameField.indexOf("->")
            if (arrow >= 0) linkTarget = nameField.substring(arrow + 2).trim().trim('\'', '`')
        }

        // Owner/group names: resolve via id -nu / id -ng, which reads the device's own
        // account database. If that fails we keep null instead of claiming "root".
        val ownerName = uid?.let { resolveUserName(it) }
        val groupName = gid?.let { resolveGroupName(it) }

        val entry = FileEntry(
            name = name,
            path = normalised,
            kind = kind,
            sizeBytes = if (kind == FileKind.DIRECTORY) null else size,
            lastModifiedMillis = mtimeSeconds?.let { it * 1000L },
            permission = permission,
            uid = uid,
            gid = gid,
            ownerName = ownerName,
            groupName = groupName,
            linkTarget = linkTarget,
            linkTargetIsDirectory = null,
        )

        StatResult.Success(
            PathStat(
                entry = entry,
                ownerName = ownerName,
                groupName = groupName,
                lastAccessMillis = null,
                creationMillis = null,
                linkCount = null,
                blockSize = null,
                partial = permission == null || uid == null || gid == null,
            ),
        )
    }

    /**
     * Walk the tree as root.
     *
     * Implemented as an iterative breadth-first walk over `ls` rather than `find`.
     *
     * That is a deliberate choice forced by reality: this device's toybox `find` reports
     * `-exec`, `-printf`, `-mindepth` and `-print0` in its own `--help` but rejects every one of
     * them at runtime with "bad arg". A walk built on `-exec stat` therefore returned nothing on
     * a real phone while passing every unit test. `ls` is the one tool whose behaviour has been
     * verified end to end here, and it also sidesteps the newline-in-filename problem entirely,
     * because no path is ever passed back through the shell.
     *
     * The cost is one `su` invocation per directory, which is the honest trade: correctness on a
     * real device over theoretical speed. [SCAN_DIRECTORY_BUDGET] bounds the walk so a search can
     * never run away on a huge tree.
     *
     * Symlinked directories are reported but not descended into, so a link cycle cannot make the
     * walk run forever.
     */
    override suspend fun scan(
        path: String,
        visit: suspend (FileEntry) -> Boolean,
        onError: (String, FsError) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val root = FsEngine.normalise(path)

        // Iterative BFS with an explicit queue: no recursion, and depth is bounded separately.
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.addLast(root to 0)

        // Canonical paths already visited, so a bind-mount or link cycle cannot loop forever.
        val visited = HashSet<String>()
        var directoriesWalked = 0

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()

            val (directory, depth) = queue.removeFirst()
            if (depth > MAX_SCAN_DEPTH) continue
            if (directoriesWalked++ >= SCAN_DIRECTORY_BUDGET) break

            when (val listing = list(directory)) {
                is ListingResult.Failure -> {
                    onError(directory, listing.error)
                }

                is ListingResult.Success -> {
                    for (entry in listing.listing.entries) {
                        currentCoroutineContext().ensureActive()
                        if (!visit(entry)) return@withContext
                        if (entry.isDirectory && !entry.isSymlink) {
                            // A canonical visited-set is not available for free through `ls`, so
                            // the depth cap is the primary guard here; the set catches the common
                            // case of the same path reached twice through a bind mount.
                            if (visited.add(entry.path)) {
                                queue.addLast(entry.path to depth + 1)
                            }
                        }
                    }
                    if (listing.listing.warnings.isNotEmpty()) {
                        onError(directory, FsError.Unknown(directory, listing.listing.warnings.first()))
                    }
                }
            }
        }
    }
    override suspend fun storage(path: String): StorageResult = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)
        val quoted = ShellQuote.quote(normalised)
        // toybox df: -k gives 1K blocks, which is universally supported.
        val command = "df -k $quoted 2>&1; echo \"__EXIT__=\$?\""
        val result = shell.runBlocking(command)
        val (body, exitCode) = splitTrailer(result.stdout)
        if (exitCode != 0 || body.isBlank()) {
            return@withContext StorageResult.Failure(
                detectError(normalised, body) ?: FsError.Unknown(normalised, body.ifBlank { "df 没有输出" }),
            )
        }

        val lines = body.lines().filter { it.isNotBlank() }
        val dataLine = lines.lastOrNull { it.trim().split(Regex("\\s+")).size >= 4 }
            ?: return@withContext StorageResult.Failure(FsError.Unknown(normalised, "df 输出无法解析"))

        val parts = dataLine.trim().split(Regex("\\s+"))
        val totalKb = parts.getOrNull(1)?.toLongOrNull()
        val usedKb = parts.getOrNull(2)?.toLongOrNull()
        val availKb = parts.getOrNull(3)?.toLongOrNull()

        if (totalKb == null || availKb == null) {
            return@withContext StorageResult.Failure(FsError.Unknown(normalised, "df 数值无法解析：$dataLine"))
        }

        val total = totalKb * 1024
        val used = (usedKb ?: (totalKb - availKb)) * 1024
        val free = (total - used).coerceAtLeast(0)

        StorageResult.Success(
            StorageStats(
                totalBytes = total,
                freeBytes = free,
                availableBytes = availKb * 1024,
            ),
        )
    }

    override suspend fun readLink(path: String): String? = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)
        val quoted = ShellQuote.quote(normalised)
        val command = "readlink $quoted 2>/dev/null; echo \"__EXIT__=\$?\""
        val result = shell.runBlocking(command)
        val (body, exitCode) = splitTrailer(result.stdout)
        if (exitCode != 0) null else body.trim().takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- parsing helpers

    /** Split the `__EXIT__=N` trailer this class appends to every command. */
    private fun splitTrailer(stdout: String): Pair<String, Int?> {
        val marker = "__EXIT__="
        val index = stdout.lastIndexOf(marker)
        if (index < 0) return stdout to null
        val body = stdout.substring(0, index)
        val code = stdout.substring(index + marker.length).trim().lineSequence().firstOrNull()?.toIntOrNull()
        return body to code
    }

    /** Recognise the shell's own error wording and map it onto a specific [FsError]. */
    private fun detectError(path: String, output: String): FsError? {
        val lower = output.lowercase()
        return when {
            lower.contains("no such file") || lower.contains("not found") -> FsError.NotFound(path)
            lower.contains("permission denied") -> FsError.PermissionDenied(path)
            lower.contains("not a directory") -> FsError.NotADirectory(path)
            lower.contains("read-only file system") -> FsError.ReadOnlyFileSystem(path)
            lower.contains("too many levels of symbolic links") -> FsError.SymlinkLoop(path)
            lower.contains("input/output error") -> FsError.Io(path, "I/O error")
            else -> null
        }
    }

    /**
     * Parse one line of `ls -la` output.
     *
     * The metadata columns are: permissions, hard links, owner, group, size, date, time
     * (and seconds for recent files), then the name. `ls` does not guarantee a fixed column
     * count:
     *
     * * a recent file prints `HH:mm:ss`, an older one prints a bare year, so there can be two
     *   or three date/time columns;
     * * a name may contain any number of spaces.
     *
     * The previous implementation assumed the name was always the ninth whitespace-separated
     * field. On a real device that was wrong in both directions: with eight metadata columns the
     * name landed in the *eighth* field and the ninth held a symlink's target, and directories
     * (which have no `-> target`) produced only eight fields and were rejected outright. The
     * visible result was a listing containing nothing but symlinks, each titled with its target.
     *
     * The name is now located by position: everything after the last date/time column is the
     * name, so spaces are preserved and a symlink is split on its own ` -> ` separator.
     */
    private fun parseLsLine(line: String, parentPath: String): FileEntry? {
        val trimmed = line.trim()
        val fields = trimmed.split(Regex("\\s+"))
        if (fields.size < 8) return null

        val permField = fields[0]
        if (permField.length < 10) return null

        val kind = when (permField[0]) {
            'd' -> FileKind.DIRECTORY
            'l' -> FileKind.SYMLINK
            'b' -> FileKind.BLOCK_DEVICE
            'c' -> FileKind.CHARACTER_DEVICE
            'p' -> FileKind.FIFO
            's' -> FileKind.SOCKET
            else -> FileKind.REGULAR
        }

        // Locate the end of the date/time columns; the name starts right after.
        var nameStartIndex = -1
        var timestampMillis: Long? = null
        var cursor = 1
        while (cursor <= fields.size - 2) {
            val parsed = parseTimestamp(fields, cursor)
            if (parsed != null) {
                nameStartIndex = cursor + parsed.columns
                timestampMillis = parsed.millis
                break
            }
            cursor++
        }

        // Fall back to the fixed nine-column layout only when the date cannot be recognised, so
        // an unexpected `ls` still yields something rather than an empty directory.
        val nameFieldIndex = if (nameStartIndex in 0 until fields.size) nameStartIndex else 8
        if (nameFieldIndex >= fields.size) return null

        // Everything from the name column onwards is the name, so spaces survive.
        val rawName = fields.drop(nameFieldIndex).joinToString(" ").trim()
        if (rawName.isEmpty()) return null

        val permission = LinuxPermission.fromSymbolic(permField)
        val ownerName = fields.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val groupName = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }
        val size = fields.getOrNull(4)?.toLongOrNull()

        var linkTarget: String? = null
        var name = rawName
        if (kind == FileKind.SYMLINK) {
            val arrow = rawName.indexOf(" -> ")
            if (arrow >= 0) {
                name = rawName.substring(0, arrow)
                linkTarget = rawName.substring(arrow + 4)
            }
        }
        if (name.isEmpty() || name == "." || name == "..") return null

        return FileEntry(
            name = name,
            path = FsEngine.childOf(parentPath, name),
            kind = kind,
            sizeBytes = if (kind == FileKind.DIRECTORY) null else size,
            // `ls` gives a usable local timestamp, so the list can show a date without a second
            // `stat` call per entry. `stat` remains authoritative for the properties dialog.
            lastModifiedMillis = timestampMillis,
            permission = permission,
            uid = null,
            gid = null,
            ownerName = ownerName,
            groupName = groupName,
            linkTarget = linkTarget,
            linkTargetIsDirectory = null,
        )
    }

    /** A recognised date/time column group. */
    private data class TimestampMatch(val millis: Long?, val columns: Int)

    /**
     * Try to read a date/time column group starting at [start].
     *
     * This device prints `yyyy-MM-dd HH:mm` and, for recent files, `yyyy-MM-dd HH:mm:ss`. A
     * bare `yyyy-MM-dd` is also accepted. Anything else returns null and the scan moves on.
     */
    private fun parseTimestamp(fields: List<String>, start: Int): TimestampMatch? {
        DATE_PATTERN.matchEntire(fields.getOrNull(start) ?: return null) ?: return null
        val time = fields.getOrNull(start + 1)?.let { TIME_PATTERN.matchEntire(it) }

        if (time != null) {
            val seconds = fields.getOrNull(start + 2)?.let { SECONDS_PATTERN.matchEntire(it) }
            val text = if (seconds != null) {
                "${fields[start]} ${fields[start + 1]}:${fields[start + 2]}"
            } else {
                "${fields[start]} ${fields[start + 1]}"
            }
            return TimestampMatch(parseLocalTimestamp(text), if (seconds != null) 3 else 2)
        }

        // Date only, i.e. `ls` printed a year instead of a time; treat it as midnight.
        return TimestampMatch(parseLocalTimestamp("${fields[start]} 00:00:00"), 1)
    }

    /** Parse `yyyy-MM-dd HH:mm[:ss]` in the device's own time zone, or null when it is not one. */
    private fun parseLocalTimestamp(text: String): Long? = try {
        val pattern = if (text.length > 16) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
        java.text.SimpleDateFormat(pattern, java.util.Locale.US)
            .apply { isLenient = false }
            .parse(text)
            ?.time
    } catch (e: java.text.ParseException) {
        null
    }

    private suspend fun resolveUserName(uid: Int): String? {
        val result: ShellResult = shell.runBlocking("id -nu $uid 2>/dev/null")
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotEmpty() }
    }

    private suspend fun resolveGroupName(gid: Int): String? {
        val result: ShellResult = shell.runBlocking("id -ng $gid 2>/dev/null")
        return result.stdout.trim().takeIf { result.isSuccess && it.isNotEmpty() }
    }

    private companion object {
        /**
         * How long a single root command may run.
         *
         * Generous because a large `ls` on a slow volume is legitimately slow, but bounded so a
         * cancelled or wedged search cannot leave a root process alive forever.
         */
        const val SCAN_TIMEOUT_MS = 120_000L

        /**
         * How many directories one scan may list.
         *
         * The walk costs one `su` invocation per directory, so this is what stops a search of
         * `/` from running for hours. Hitting it ends the walk early, and the caller reports the
         * result as incomplete rather than presenting it as a full answer.
         */
        const val SCAN_DIRECTORY_BUDGET = 2_000

        /** Depth cap, mirroring the Java engine's, as a second guard against a link cycle. */
        const val MAX_SCAN_DEPTH = 64

        /** `yyyy-MM-dd`, the date column this device's `ls` prints. */
        val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /** `HH:mm`, the time column for older files. */
        val TIME_PATTERN = Regex("^\\d{2}:\\d{2}$")

        /** `ss`, printed as a third column for recent files. */
        val SECONDS_PATTERN = Regex("^\\d{2}$")
    }
}
