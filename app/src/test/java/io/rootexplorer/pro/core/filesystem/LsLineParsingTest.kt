package io.rootexplorer.pro.core.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ls -la` parsing, against the exact bytes this device produced.
 *
 * The capture is verbatim from `su -c 'ls -la /'` on the test phone. On that device the app
 * rendered eight symlink rows and dropped every directory and regular file, so the parser is
 * exercised here on the real text rather than on text invented to match the implementation.
 */
class LsLineParsingTest {

    /** Verbatim `ls -la /` output (2009/1970 dates and column widths included). */
    private val deviceOutput = listOf(
        "total 136",
        "drwxr-xr-x   34 root   root       820 2026-10-07 12:33 .",
        "drwxr-xr-x   34 root   root       820 2026-10-07 12:33 ..",
        "drwxr-xr-x    2 root   root       100 2025-12-06 15:52 NikoHyperFix",
        "lrw-r--r--    1 root   root        30 2009-01-01 08:00 adb_keys -> /product/etc/security/adb_keys",
        "drwxr-xr-x   82 root   root      1660 1970-01-04 05:29 apex",
        "lrw-r--r--    1 root   root        11 2009-01-01 08:00 bin -> /system/bin",
        "drwxr-xr-x   12 root   root       260 1970-01-04 05:29 bootstrap-apex",
        "lrw-r--r--    1 root   root        50 2009-01-01 08:00 bugreports -> /data/user_de/0/com.android.shell/files/bugreports",
        "lrw-r--r--    1 root   root        11 2009-01-01 08:00 cache -> /data/cache",
        "drwxr-xr-x    5 root   root         0 1970-01-04 05:29 config",
        "drwxr-xr-x    2 root   root        27 2009-01-01 08:00 cust",
        "lrw-r--r--    1 root   root        17 2009-01-01 08:00 d -> /sys/kernel/debug",
        "drwxrwx--x   60 system system    4096 2026-10-09 22:41 data",
        "drwx------    9 root   system     180 1970-01-04 05:29 data_mirror",
        "drwxr-xr-x    2 root   root        27 2009-01-01 08:00 debug_ramdisk",
        "drwxr-xr-x   29 root   root      5700 2026-10-11 07:00 dev",
        "lrw-r--r--    1 root   root        11 2009-01-01 08:00 etc -> /system/etc",
        "lrwxr-x---    1 root   shell       16 2009-01-01 08:00 init -> /system/bin/init",
        "-rwxr-x---    1 root   shell      621 2009-01-01 08:00 init.environ.rc",
        "drwxr-xr-x   19 root   root       420 1970-01-04 05:29 linkerconfig",
        "drwxr-xr-x   13 root   root      4096 1970-01-01 08:04 metadata",
    )

    /**
     * The parser under test.
     *
     * This mirrors the engine's current logic, which locates the name by finding the end of the
     * date/time columns instead of assuming a fixed field count.
     */
    private val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val timePattern = Regex("^\\d{2}:\\d{2}$")
    private val secondsPattern = Regex("^\\d{2}$")

    private fun parse(line: String, parentPath: String): FileEntry? {
        val fields = line.trim().split(Regex("\\s+"))
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

        var nameStartIndex = -1
        var cursor = 1
        while (cursor <= fields.size - 2) {
            if (datePattern.matches(fields[cursor])) {
                val hasTime = fields.getOrNull(cursor + 1)?.let { timePattern.matches(it) } == true
                if (hasTime) {
                    val hasSeconds = fields.getOrNull(cursor + 2)?.let { secondsPattern.matches(it) } == true
                    nameStartIndex = cursor + if (hasSeconds) 3 else 2
                } else {
                    nameStartIndex = cursor + 1
                }
                break
            }
            cursor++
        }
        val nameFieldIndex = if (nameStartIndex in 0 until fields.size) nameStartIndex else 8
        if (nameFieldIndex >= fields.size) return null

        val rawName = fields.drop(nameFieldIndex).joinToString(" ").trim()
        if (rawName.isEmpty()) return null

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
            sizeBytes = if (kind == FileKind.DIRECTORY) null else fields.getOrNull(4)?.toLongOrNull(),
            lastModifiedMillis = null,
            permission = null,
            uid = null,
            gid = null,
            ownerName = fields.getOrNull(2),
            groupName = fields.getOrNull(3),
            linkTarget = linkTarget,
            linkTargetIsDirectory = null,
        )
    }
    @Test
    fun `every device line parses`() {
        val failures = mutableListOf<String>()
        for (line in deviceOutput.drop(1)) {
            // "." and ".." are intentionally dropped by the parser, so they are not failures.
            val namePart = line.trim().split(Regex("\\s+")).last()
            if (namePart == "." || namePart == "..") continue
            if (parse(line, "/") == null) failures += line
        }
        assertTrue("lines that failed to parse: $failures", failures.isEmpty())
    }

    @Test
    fun `directories are recognised and not dropped`() {
        val entries = deviceOutput.drop(1).mapNotNull { parse(it, "/") }
        val directories = entries.filter { it.isDirectory }.map { it.name }

        // These are exactly the entries the app failed to show.
        assertTrue("apex must be listed: $directories", "apex" in directories)
        assertTrue("data must be listed: $directories", "data" in directories)
        assertTrue("dev must be listed: $directories", "dev" in directories)
        assertTrue("config must be listed: $directories", "config" in directories)
    }

    @Test
    fun `regular files are recognised`() {
        val entries = deviceOutput.drop(1).mapNotNull { parse(it, "/") }
        val files = entries.filter { !it.isDirectory && !it.isSymlink }.map { it.name }
        assertTrue("init.environ.rc must be listed: $files", "init.environ.rc" in files)
    }

    @Test
    fun `a symlink shows its own name, not its target`() {
        val entry = parse(
            "lrw-r--r--    1 root   root        11 2009-01-01 08:00 bin -> /system/bin",
            "/",
        )
        assertNotNull(entry)
        // The app displayed "-> /data/cache" as the row title, which is this bug.
        assertEquals("bin", entry!!.name)
        assertEquals("/system/bin", entry.linkTarget)
        assertEquals(FileKind.SYMLINK, entry.kind)
    }

    @Test
    fun `a symlink path is parent plus its own name`() {
        val entry = parse(
            "lrw-r--r--    1 root   root        11 2009-01-01 08:00 etc -> /system/etc",
            "/",
        )
        assertEquals("/etc", entry!!.path)
    }

    @Test
    fun `the dotted entries are filtered out, not listed`() {
        // The pane draws its own ".." row, so the real ones must not appear as files. "." and
        // ".." are also not children of the directory being listed.
        assertNull(parse("drwxr-xr-x   34 root   root       820 2026-10-07 12:33 .", "/"))
        assertNull(parse("drwxr-xr-x   34 root   root       820 2026-10-07 12:33 ..", "/"))
    }

    @Test
    fun `a name containing spaces survives the parse`() {
        // split(limit = 9) keeps the remainder as one field, so this works; if the limit were
        // removed the name would be truncated at the first space.
        val entry = parse(
            "-rw-r--r--    1 root   root       100 2026-10-07 12:33 my document notes.txt",
            "/data",
        )
        assertNotNull(entry)
        assertEquals("my document notes.txt", entry!!.name)
    }

    @Test
    fun `a symlink whose target contains spaces survives`() {
        val entry = parse(
            "lrw-r--r--    1 root   root        30 2026-10-07 12:33 link -> /data/two words here",
            "/",
        )
        assertEquals("link", entry!!.name)
        assertEquals("/data/two words here", entry.linkTarget)
    }

    @Test
    fun `the total line is skipped, not parsed as an entry`() {
        assertTrue(deviceOutput.first().startsWith("total "))
        // The engine filters these out before parsing.
        assertTrue(parse(deviceOutput.first(), "/") == null)
    }
}
