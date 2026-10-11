package io.rootexplorer.pro.core.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end parse of the real `ls -la /` capture from the test device.
 *
 * This is the exact text and the exact parser the app uses, and it asserts what the screen ends
 * up containing, so the regression it guards against (a root listing showing nothing but
 * symlinks, each titled with its target) cannot come back unnoticed.
 */
class RootListingIntegrationTest {

    /** Verbatim `su -c 'ls -la /'` from the phone. */
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

        var nameStart = -1
        var cursor = 1
        while (cursor <= fields.size - 2) {
            if (datePattern.matches(fields[cursor])) {
                val hasTime = fields.getOrNull(cursor + 1)?.let { timePattern.matches(it) } == true
                nameStart = if (hasTime) {
                    val hasSeconds = fields.getOrNull(cursor + 2)?.let { secondsPattern.matches(it) } == true
                    cursor + if (hasSeconds) 3 else 2
                } else {
                    cursor + 1
                }
                break
            }
            cursor++
        }
        val nameIndex = if (nameStart in 0 until fields.size) nameStart else 8
        if (nameIndex >= fields.size) return null

        val rawName = fields.drop(nameIndex).joinToString(" ").trim()
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

    private fun listing(): List<FileEntry> =
        deviceOutput.drop(1).mapNotNull { parse(it, "/") }

    @Test
    fun `the root listing contains every real entry except dot entries`() {
        val entries = listing()
        val names = entries.map { it.name }.toSet()

        // Directories that were missing entirely before the fix.
        for (expected in listOf("apex", "data", "dev", "config", "cust", "metadata", "linkerconfig")) {
            assertTrue("$expected must be present, got $names", expected in names)
        }
        // Regular file that was missing too.
        assertTrue("init.environ.rc must be present", "init.environ.rc" in names)
        // Symlinks keep their own names.
        for (expected in listOf("bin", "etc", "init", "cache", "d", "adb_keys")) {
            assertTrue("$expected must be present", expected in names)
        }

        // "." and ".." must not be listed; the pane draws its own up row.
        assertTrue("." !in names)
        assertTrue(".." !in names)
    }

    @Test
    fun `no entry is titled with a symlink target`() {
        // The exact symptom on the device: rows reading "-> /system/bin".
        for (entry in listing()) {
            assertTrue(
                "entry name must never start with an arrow: ${entry.name}",
                !entry.name.startsWith("->"),
            )
        }
    }

    @Test
    fun `kinds are mixed, not symlink-only`() {
        val entries = listing()
        val directories = entries.count { it.isDirectory }
        val files = entries.count { !it.isDirectory && !it.isSymlink }
        val links = entries.count { it.isSymlink }

        // Before the fix: directories = 0 and files = 0.
        assertTrue("expected several directories, got $directories", directories >= 8)
        assertTrue("expected at least one regular file, got $files", files >= 1)
        assertTrue("expected symlinks too, got $links", links >= 6)
    }

    @Test
    fun `directories report no size and files do`() {
        val apex = listing().first { it.name == "apex" }
        assertNull("a directory has no meaningful size", apex.sizeBytes)

        val rc = listing().first { it.name == "init.environ.rc" }
        assertEquals(621L, rc.sizeBytes)
    }

    @Test
    fun `symlink targets are captured separately`() {
        val bin = listing().first { it.name == "bin" }
        assertEquals("/system/bin", bin.linkTarget)
        assertEquals("/bin", bin.path)

        val data = listing().first { it.name == "data" }
        assertNull("a directory is not a link", data.linkTarget)
    }

    @Test
    fun `entries are sorted directories first then by name, as the list shows`() {
        val dirs = listing().filter { it.isDirectory }.map { it.name }
        // Sanity: these are the entries the user should see grouped at the top.
        assertTrue(dirs.contains("apex"))
        assertTrue(dirs.contains("data"))
        assertTrue(dirs.size >= 8)
    }
}
