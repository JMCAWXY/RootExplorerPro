package io.rootexplorer.pro.service.trash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recycle-bin index codec.
 *
 * The index is the only thing that records where a trashed file came from, so a codec bug
 * means a file that can never be restored to the right place. Tab and newline are legal in
 * Linux file names, which is exactly the case that would otherwise split one record into two
 * and corrupt every record after it.
 */
class TrashIndexCodecTest {

    private fun entry(
        id: String = "abc123",
        path: String = "/storage/emulated/0/Download/a.txt",
        deletedAt: Long = 1_700_000_000_000L,
        isDirectory: Boolean = false,
        size: Long? = 1024L,
    ) = TrashEntry(id, path, deletedAt, isDirectory, size)

    @Test
    fun `plain record round trips`() {
        val original = entry()
        val decoded = TrashRepository.decodeRecord(TrashRepository.encodeRecord(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `directory flag round trips`() {
        val original = entry(isDirectory = true, size = null)
        val decoded = TrashRepository.decodeRecord(TrashRepository.encodeRecord(original))
        assertEquals(true, decoded?.isDirectory)
        assertNull(decoded?.sizeBytes)
    }

    @Test
    fun `a path containing a tab does not split the record`() {
        val original = entry(path = "/data/we\tird.txt")
        val line = TrashRepository.encodeRecord(original)

        // Exactly five fields, no matter what the file name contains.
        assertEquals(5, line.split('\t').size)
        assertEquals(original, TrashRepository.decodeRecord(line))
    }

    @Test
    fun `a path containing a newline does not split the record`() {
        val original = entry(path = "/data/two\nlines.txt")
        val line = TrashRepository.encodeRecord(original)

        assertEquals(5, line.split('\t').size)
        assertTrue("escape must remove raw newlines", !line.contains('\n'))
        assertEquals(original, TrashRepository.decodeRecord(line))
    }

    @Test
    fun `a path containing a backslash round trips`() {
        val original = entry(path = "/data/back\\slash.txt")
        assertEquals(original, TrashRepository.decodeRecord(TrashRepository.encodeRecord(original)))
    }

    @Test
    fun `a path containing carriage return round trips`() {
        val original = entry(path = "/data/cr\rreturn.txt")
        assertEquals(original, TrashRepository.decodeRecord(TrashRepository.encodeRecord(original)))
    }

    @Test
    fun `tab newline and backslash together round trip`() {
        val nasty = "/data/a\tb\nc\\d\re.txt"
        val original = entry(path = nasty)
        val decoded = TrashRepository.decodeRecord(TrashRepository.encodeRecord(original))
        assertEquals(nasty, decoded?.originalPath)
    }

    @Test
    fun `chinese and space characters are preserved verbatim`() {
        val original = entry(path = "/storage/emulated/0/我的 文档/报告 2024.txt")
        assertEquals(original, TrashRepository.decodeRecord(TrashRepository.encodeRecord(original)))
    }

    @Test
    fun `a malformed line is rejected rather than guessed at`() {
        assertNull(TrashRepository.decodeRecord(""))
        assertNull(TrashRepository.decodeRecord("onlyonefield"))
        assertNull(TrashRepository.decodeRecord("id\tpath\ttime")) // too few fields
        assertNull(TrashRepository.decodeRecord("\t/path\t123\t0\t1")) // blank id
        assertNull(TrashRepository.decodeRecord("id\t/path\tnotanumber\t0\t1"))
    }

    @Test
    fun `a record with an empty size decodes to null size`() {
        val line = "id123\t/path/file.txt\t1700000000000\t0\t"
        val decoded = TrashRepository.decodeRecord(line)
        assertNull(decoded?.sizeBytes)
    }

    @Test
    fun `an unknown escape sequence is passed through unchanged`() {
        // Forward compatibility: a future field type must not corrupt an old reader.
        assertEquals("a\\qb", TrashRepository.unescapeField("a\\qb"))
    }

    @Test
    fun `escape is idempotent for text without special characters`() {
        assertEquals("/simple/path.txt", TrashRepository.escapeField("/simple/path.txt"))
    }

    @Test
    fun `display name is the last path segment`() {
        assertEquals("a.txt", entry(path = "/storage/emulated/0/a.txt").displayName)
        assertEquals("dir", entry(path = "/data/dir").displayName)
        assertEquals("dir", entry(path = "/data/dir/").displayName)
    }

    @Test
    fun `display name falls back to the whole path for the root`() {
        // Degenerate but must not produce an empty label.
        assertEquals("/", entry(path = "/").displayName)
    }
}
