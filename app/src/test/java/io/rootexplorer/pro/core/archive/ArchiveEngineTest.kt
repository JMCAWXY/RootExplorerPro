package io.rootexplorer.pro.core.archive

import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Archive creation, extraction and the Zip Slip defence.
 *
 * The security tests matter most here: an archive is untrusted input, and a naive extractor
 * writes wherever the entry name says. Each attack shape is asserted to be refused, and the
 * destination is then checked to confirm nothing was written outside it.
 */
class ArchiveEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = ArchiveEngine()

    private fun dir(name: String): File = temp.newFolder(name)

    private fun writeZip(target: File, entries: Map<String, String>) {
        ZipOutputStream(target.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    // ------------------------------------------------------------------ format detection

    @Test
    fun `format is detected from the file name`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromName("a.zip"))
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromName("A.ZIP"))
        assertEquals(ArchiveFormat.GZIP, ArchiveFormat.fromName("a.gz"))
        // Compound extensions resolve longest-first.
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.fromName("backup.tar.gz"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat.fromName("a.tar"))
        assertNull(ArchiveFormat.fromName("a.txt"))
        assertNull(ArchiveFormat.fromName("noextension"))
    }

    @Test
    fun `only zip and gzip can be created`() {
        assertTrue(ArchiveFormat.ZIP.canCreate)
        assertTrue(ArchiveFormat.GZIP.canCreate)
        assertFalse(ArchiveFormat.RAR.canCreate)
        assertFalse(ArchiveFormat.SEVEN_ZIP.canCreate)
    }

    @Test
    fun `rar and 7z are honestly marked as not extractable`() {
        // Claiming support and then failing would be worse than saying so up front.
        assertFalse(ArchiveFormat.RAR.canExtract)
        assertFalse(ArchiveFormat.SEVEN_ZIP.canExtract)
        assertTrue(ArchiveFormat.ZIP.canExtract)
        assertTrue(ArchiveFormat.TAR.canExtract)
    }

    // ------------------------------------------------------------------ zip slip defence

    @Test
    fun `a parent-directory entry is refused`() {
        val destination = dir("dest")
        assertNull(engine.resolveEntryTarget(destination, "../escape.txt"))
        assertNull(engine.resolveEntryTarget(destination, "../../etc/passwd"))
        assertNull(engine.resolveEntryTarget(destination, "a/../../escape.txt"))
        assertNull(engine.resolveEntryTarget(destination, "a/b/../../../escape.txt"))
    }

    @Test
    fun `an absolute entry is refused`() {
        val destination = dir("dest")
        assertNull(engine.resolveEntryTarget(destination, "/etc/passwd"))
        assertNull(engine.resolveEntryTarget(destination, "//server/share/x"))
    }

    @Test
    fun `a windows drive prefix is refused`() {
        val destination = dir("dest")
        // A malicious archive must behave the same on every platform.
        assertNull(engine.resolveEntryTarget(destination, "C:/Windows/system32/x.dll"))
        assertNull(engine.resolveEntryTarget(destination, "c:\\Windows\\x.dll"))
    }

    @Test
    fun `a backslash traversal is refused`() {
        val destination = dir("dest")
        assertNull(engine.resolveEntryTarget(destination, "..\\escape.txt"))
        assertNull(engine.resolveEntryTarget(destination, "a\\..\\..\\escape.txt"))
    }

    @Test
    fun `a null byte in the name is refused`() {
        val destination = dir("dest")
        assertNull(engine.resolveEntryTarget(destination, "a\u0000b.txt"))
    }

    @Test
    fun `an empty name is refused`() {
        val destination = dir("dest")
        assertNull(engine.resolveEntryTarget(destination, ""))
        assertNull(engine.resolveEntryTarget(destination, "   "))
    }

    @Test
    fun `an ordinary nested name is accepted and stays inside`() {
        val destination = dir("dest")
        val target = engine.resolveEntryTarget(destination, "sub/dir/file.txt")
        assertNotNull(target)
        assertTrue(target!!.absolutePath.startsWith(destination.absolutePath))
    }

    @Test
    fun `dot segments that stay inside are accepted after resolution`() {
        val destination = dir("dest")
        // "./a.txt" is harmless; only ".." is refused outright.
        val target = engine.resolveEntryTarget(destination, "./a.txt")
        assertNotNull(target)
    }

    @Test
    fun `extraction refuses a slip and writes nothing outside the destination`() = runTest {
        val destination = dir("dest")
        val archive = File(temp.root, "evil.zip")
        writeZip(
            archive,
            mapOf(
                "../escaped.txt" to "should never be written",
                "safe.txt" to "this one is fine",
            ),
        )

        val outcome = engine.extract(archive.absolutePath, destination.absolutePath)

        assertTrue(outcome is ArchiveOutcome.Success)
        val result = outcome as ArchiveOutcome.Success
        assertTrue("the slip must be reported as a failure", result.failed.isNotEmpty())
        assertTrue(result.failed.first().reason.contains("不安全"))

        // The safe entry was written; the escaping one was not.
        assertTrue(File(destination, "safe.txt").isFile)
        assertFalse("nothing may be written outside the destination", File(temp.root, "escaped.txt").exists())
    }

    // ------------------------------------------------------------------ round trip

    @Test
    fun `compress then extract round trips file contents`() = runTest {
        val source = dir("src")
        File(source, "a.txt").writeText("alpha")
        val sub = File(source, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeText("beta")

        val archive = File(temp.root, "out.zip")
        val compressed = engine.compress(
            sources = listOf(source.absolutePath),
            targetArchivePath = archive.absolutePath,
            format = ArchiveFormat.ZIP,
        )
        assertTrue("compress failed: $compressed", compressed is ArchiveOutcome.Success)
        assertTrue(archive.isFile)

        val destination = dir("extracted")
        val extracted = engine.extract(archive.absolutePath, destination.absolutePath)
        assertTrue("extract failed: $extracted", extracted is ArchiveOutcome.Success)

        // The source directory's own name becomes the archive root.
        assertEquals("alpha", File(destination, "src/a.txt").readText())
        assertEquals("beta", File(destination, "src/sub/b.txt").readText())
    }

    @Test
    fun `a single file round trips through zip`() = runTest {
        val source = File(temp.root, "single.txt").apply { writeText("contents here") }
        val archive = File(temp.root, "single.zip")

        engine.compress(listOf(source.absolutePath), archive.absolutePath, ArchiveFormat.ZIP)

        val destination = dir("out")
        engine.extract(archive.absolutePath, destination.absolutePath)
        assertEquals("contents here", File(destination, "single.txt").readText())
    }

    @Test
    fun `a directory listing reports the entries`() = runTest {
        val archive = File(temp.root, "list.zip")
        writeZip(archive, mapOf("one.txt" to "1", "two.txt" to "22"))

        val listing = engine.list(archive.absolutePath)
        assertTrue(listing.isSuccess)
        val names = listing.getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("one.txt", "two.txt"), names)
    }

    @Test
    fun `compressing an empty selection is refused`() = runTest {
        val archive = File(temp.root, "empty.zip")
        val outcome = engine.compress(emptyList(), archive.absolutePath, ArchiveFormat.ZIP)
        assertTrue(outcome is ArchiveOutcome.Failure)
        assertFalse("no archive should be created", archive.exists())
    }

    @Test
    fun `compressing to an unsupported format is refused`() = runTest {
        val source = File(temp.root, "x.txt").apply { writeText("x") }
        val outcome = engine.compress(
            listOf(source.absolutePath),
            File(temp.root, "x.rar").absolutePath,
            ArchiveFormat.RAR,
        )
        assertTrue(outcome is ArchiveOutcome.Failure)
    }

    @Test
    fun `extracting a missing archive fails clearly`() = runTest {
        val outcome = engine.extract(
            File(temp.root, "nope.zip").absolutePath,
            dir("d").absolutePath,
        )
        assertTrue(outcome is ArchiveOutcome.Failure)
        assertTrue((outcome as ArchiveOutcome.Failure).reason.contains("不存在"))
    }

    @Test
    fun `extracting a corrupted archive fails rather than producing an empty result`() = runTest {
        val bogus = File(temp.root, "bogus.zip")
        bogus.writeText("this is definitely not a zip file")

        val outcome = engine.extract(bogus.absolutePath, dir("d").absolutePath)
        assertTrue(outcome is ArchiveOutcome.Failure)
    }

    // ------------------------------------------------------------------ conflict policy

    @Test
    fun `skip policy leaves an existing file alone`() {
        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }
        assertNull(engine.applyPolicy(existing, ConflictPolicy.SKIP))
    }

    @Test
    fun `overwrite policy returns the same path`() {
        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }
        assertEquals(existing, engine.applyPolicy(existing, ConflictPolicy.OVERWRITE))
    }

    @Test
    fun `rename policy produces a free sibling name`() {
        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }
        val resolved = engine.applyPolicy(existing, ConflictPolicy.RENAME)

        assertNotNull(resolved)
        assertEquals("a (1).txt", resolved!!.name)
        assertFalse(resolved.exists())
    }

    @Test
    fun `ask policy is treated as the non-destructive option`() {
        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }
        // The engine cannot ask, so it must not choose to destroy.
        val resolved = engine.applyPolicy(existing, ConflictPolicy.ASK)
        assertNotNull(resolved)
        assertFalse(resolved!!.exists())
        assertEquals("OLD", existing.readText())
    }

    @Test
    fun `extraction with skip leaves the existing file untouched`() = runTest {
        val archive = File(temp.root, "conflict.zip")
        writeZip(archive, mapOf("a.txt" to "NEW"))

        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }

        val outcome = engine.extract(archive.absolutePath, destination.absolutePath, ConflictPolicy.SKIP)
        assertTrue(outcome is ArchiveOutcome.Success)
        assertEquals(1, (outcome as ArchiveOutcome.Success).skipped)
        assertEquals("OLD", existing.readText())
    }

    @Test
    fun `extraction with overwrite replaces the content`() = runTest {
        val archive = File(temp.root, "overwrite.zip")
        writeZip(archive, mapOf("a.txt" to "NEW"))

        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }

        engine.extract(archive.absolutePath, destination.absolutePath, ConflictPolicy.OVERWRITE)
        assertEquals("NEW", existing.readText())
    }

    @Test
    fun `extraction with rename keeps both files`() = runTest {
        val archive = File(temp.root, "rename.zip")
        writeZip(archive, mapOf("a.txt" to "NEW"))

        val destination = dir("dest")
        val existing = File(destination, "a.txt").apply { writeText("OLD") }

        engine.extract(archive.absolutePath, destination.absolutePath, ConflictPolicy.RENAME)
        assertEquals("OLD", existing.readText())
        assertEquals("NEW", File(destination, "a (1).txt").readText())
    }

    // ------------------------------------------------------------------ naming

    @Test
    fun `suggested archive name uses the source name and format extension`() {
        assertEquals("photos.zip", engine.suggestedArchiveName(listOf("/data/photos"), ArchiveFormat.ZIP))
        assertEquals("a.txt.gz", engine.suggestedArchiveName(listOf("/data/a.txt"), ArchiveFormat.GZIP))
        assertEquals("archive.zip", engine.suggestedArchiveName(listOf("/a", "/b"), ArchiveFormat.ZIP))
    }

    @Test
    fun `suggested name does not double the extension`() {
        assertEquals("a.zip", engine.suggestedArchiveName(listOf("/data/a.zip"), ArchiveFormat.ZIP))
    }

    @Test
    fun `plan records relative entry names for a directory`() {
        val source = dir("tree")
        File(source, "one.txt").writeText("1")
        val nested = File(source, "nested").apply { mkdirs() }
        File(nested, "two.txt").writeText("2")

        val plan = engine.planCompression(listOf(source.absolutePath))
        val names = plan.map { it.entryName }.toSet()

        assertTrue(names.contains("tree/one.txt"))
        assertTrue(names.contains("tree/nested/two.txt"))
    }

    @Test
    fun `plan for a single file uses the bare file name`() {
        val file = File(temp.root, "solo.bin").apply { writeText("x") }
        val plan = engine.planCompression(listOf(file.absolutePath))
        assertEquals(listOf("solo.bin"), plan.map { it.entryName })
    }
}
