package io.rootexplorer.pro.core.filesystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sorting and filtering.
 *
 * The interesting cases are the ones where data is missing — a root `ls` listing gives no
 * timestamp, so most entries have `lastModifiedMillis == null`. Those must sort last in both
 * directions rather than collecting at the top of an ascending sort.
 */
class SortAndFilterTest {

    private fun entry(
        name: String,
        directory: Boolean = false,
        size: Long? = null,
        modified: Long? = null,
        hidden: Boolean = name.startsWith("."),
    ) = FileEntry(
        name = name,
        path = "/data/$name",
        kind = if (directory) FileKind.DIRECTORY else FileKind.REGULAR,
        sizeBytes = if (directory) null else size,
        lastModifiedMillis = modified,
        permission = null,
        uid = null,
        gid = null,
        ownerName = null,
        groupName = null,
        linkTarget = null,
        linkTargetIsDirectory = null,
    )

    private fun names(entries: List<FileEntry>) = entries.map { it.name }

    @Test
    fun `directories sort before files in ascending order`() {
        val input = listOf(entry("b.txt"), entry("adir", directory = true), entry("a.txt"))
        val sorted = SortSpec(FileSortField.NAME, SortDirection.ASCENDING).apply(input)
        assertEquals(listOf("adir", "a.txt", "b.txt"), names(sorted))
    }

    @Test
    fun `directories still sort first when descending`() {
        val input = listOf(entry("b.txt"), entry("adir", directory = true), entry("zdir", directory = true))
        val sorted = SortSpec(FileSortField.NAME, SortDirection.DESCENDING).apply(input)
        assertEquals(listOf("zdir", "adir", "b.txt"), names(sorted))
    }

    @Test
    fun `name sorting is case insensitive`() {
        val input = listOf(entry("Beta"), entry("alpha"), entry("Gamma"))
        val sorted = SortSpec(FileSortField.NAME, SortDirection.ASCENDING).apply(input)
        assertEquals(listOf("alpha", "Beta", "Gamma"), names(sorted))
    }

    @Test
    fun `size sorting puts unknown sizes last when ascending`() {
        val input = listOf(
            entry("unknown", size = null),
            entry("big", size = 5000),
            entry("small", size = 10),
        )
        val sorted = SortSpec(FileSortField.SIZE, SortDirection.ASCENDING).apply(input)
        assertEquals(listOf("small", "big", "unknown"), names(sorted))
    }

    @Test
    fun `size sorting still puts unknown sizes last when descending`() {
        val input = listOf(
            entry("unknown", size = null),
            entry("big", size = 5000),
            entry("small", size = 10),
        )
        val sorted = SortSpec(FileSortField.SIZE, SortDirection.DESCENDING).apply(input)
        assertEquals(listOf("big", "small", "unknown"), names(sorted))
    }

    @Test
    fun `date sorting keeps unknown timestamps last in both directions`() {
        val input = listOf(
            entry("old", modified = 1000),
            entry("unknown", modified = null),
            entry("new", modified = 9000),
        )
        val ascending = SortSpec(FileSortField.DATE, SortDirection.ASCENDING).apply(input)
        assertEquals(listOf("old", "new", "unknown"), names(ascending))

        val descending = SortSpec(FileSortField.DATE, SortDirection.DESCENDING).apply(input)
        assertEquals(listOf("new", "old", "unknown"), names(descending))
    }

    @Test
    fun `type sorting groups by extension then name`() {
        val input = listOf(
            entry("z.png"),
            entry("a.txt"),
            entry("a.png"),
        )
        val sorted = SortSpec(FileSortField.TYPE, SortDirection.ASCENDING).apply(input)
        assertEquals(listOf("a.png", "z.png", "a.txt"), names(sorted))
    }

    @Test
    fun `sorting an empty list is safe`() {
        assertEquals(emptyList<String>(), names(SortSpec().apply(emptyList())))
    }

    @Test
    fun `direction toggles`() {
        assertEquals(SortDirection.DESCENDING, SortDirection.ASCENDING.toggled())
        assertEquals(SortDirection.ASCENDING, SortDirection.DESCENDING.toggled())
    }

    // ------------------------------------------------------------------ filter

    @Test
    fun `an inactive filter matches everything`() {
        val filter = FileFilter()
        assertEquals(false, filter.isActive)
        assertEquals(true, filter.matches(entry("anything.bin")))
    }

    @Test
    fun `name filter matches case-insensitively and on substrings`() {
        val filter = FileFilter(nameQuery = "read")
        assertEquals(true, filter.matches(entry("README.md")))
        assertEquals(false, filter.matches(entry("notes.txt")))
    }

    @Test
    fun `extension filter ignores a leading dot in the query`() {
        val filter = FileFilter(extension = ".png")
        assertEquals(true, filter.matches(entry("a.png")))
        assertEquals(false, filter.matches(entry("a.jpg")))
    }

    @Test
    fun `folders only filter excludes files`() {
        val filter = FileFilter(kind = FileFilter.KindFilter.FOLDERS_ONLY)
        assertEquals(true, filter.matches(entry("dir", directory = true)))
        assertEquals(false, filter.matches(entry("file.txt")))
    }

    @Test
    fun `image filter matches known image extensions only`() {
        val filter = FileFilter(kind = FileFilter.KindFilter.IMAGES)
        assertEquals(true, filter.matches(entry("a.webp")))
        assertEquals(false, filter.matches(entry("a.mkv")))
    }

    @Test
    fun `apk filter matches only apk files`() {
        val filter = FileFilter(kind = FileFilter.KindFilter.APK)
        assertEquals(true, filter.matches(entry("app.apk")))
        assertEquals(false, filter.matches(entry("app.zip")))
    }

    @Test
    fun `hidden detection is based on a leading dot`() {
        assertEquals(true, entry(".nomedia").isHidden)
        assertEquals(false, entry("nomedia").isHidden)
    }

    @Test
    fun `extension extraction handles edge cases`() {
        assertEquals("png", entry("a.png").extension)
        assertEquals("", entry("noextension").extension)
        assertEquals("", entry(".hidden").extension)
        assertEquals("gz", entry("archive.tar.gz").extension)
    }
}
