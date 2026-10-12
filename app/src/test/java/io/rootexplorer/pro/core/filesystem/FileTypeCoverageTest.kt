package io.rootexplorer.pro.core.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Coverage of the extension table.
 *
 * The table is a plain lookup, so a missing entry is silent: the file reports itself as unknown and
 * shares the generic glyph with everything else that is unknown. These assertions are what turn a
 * commonly met format's absence into a build failure.
 */
class FileTypeCoverageTest {

    private fun infoOf(name: String) = FileTypeDetector.detectByName(name)

    @Test
    fun `the name decides the kind, which is what a fresh file has to go on`() {
        assertEquals(FileTypeDetector.Category.ARCHIVE, infoOf("12.zip").category)
        assertEquals("ZIP 压缩包", infoOf("12.zip").description)
        assertEquals(FileTypeDetector.Category.DOCUMENT, infoOf("1.txt").category)
        assertEquals("文本文档", infoOf("1.txt").description)
    }

    @Test
    fun `the extension case does not matter`() {
        assertEquals(FileTypeDetector.Category.ARCHIVE, infoOf("PHOTO.ZIP").category)
        assertEquals(FileTypeDetector.Category.IMAGE, infoOf("PHOTO.JPG").category)
        assertEquals(FileTypeDetector.Category.CODE, infoOf("Main.KT").category)
    }

    @Test
    fun `formats a phone actually meets are all recognised`() {
        val expected = mapOf(
            "a.rar" to FileTypeDetector.Category.ARCHIVE,
            "a.7z" to FileTypeDetector.Category.ARCHIVE,
            "a.tar.gz" to FileTypeDetector.Category.ARCHIVE,
            "a.apks" to FileTypeDetector.Category.ARCHIVE,
            "a.xapk" to FileTypeDetector.Category.ARCHIVE,
            "a.rpm" to FileTypeDetector.Category.ARCHIVE,
            "a.woff2" to FileTypeDetector.Category.FONT,
            "a.psd" to FileTypeDetector.Category.IMAGE,
            "a.dng" to FileTypeDetector.Category.IMAGE,
            "a.webp" to FileTypeDetector.Category.IMAGE,
            "a.mkv" to FileTypeDetector.Category.VIDEO,
            "a.ogv" to FileTypeDetector.Category.VIDEO,
            "a.flac" to FileTypeDetector.Category.AUDIO,
            "a.ape" to FileTypeDetector.Category.AUDIO,
            "a.mid" to FileTypeDetector.Category.AUDIO,
            "a.json" to FileTypeDetector.Category.CODE,
            "a.tsx" to FileTypeDetector.Category.CODE,
            "a.gradle" to FileTypeDetector.Category.CODE,
            "a.ps1" to FileTypeDetector.Category.CODE,
            "a.mobi" to FileTypeDetector.Category.DOCUMENT,
            "a.azw3" to FileTypeDetector.Category.DOCUMENT,
            "a.pages" to FileTypeDetector.Category.DOCUMENT,
            "a.srt" to FileTypeDetector.Category.DOCUMENT,
            "a.sqlite3" to FileTypeDetector.Category.DATABASE,
            "a.realm" to FileTypeDetector.Category.DATABASE,
            "a.aab" to FileTypeDetector.Category.APK,
            "a.oat" to FileTypeDetector.Category.APK,
        )

        val unknown = expected.filter { (name, category) -> infoOf(name).category != category }
        // Reported as the whole map so a failure names every format that regressed, not just the first.
        assertEquals(emptyMap<String, FileTypeDetector.Category>(), unknown)
    }

    @Test
    fun `an unrecognised extension still says what it saw instead of guessing`() {
        val info = infoOf("mystery.xyzzy")
        assertEquals(FileTypeDetector.Category.UNKNOWN, info.category)
        assertEquals("XYZZY 文件", info.description)
        assertNotEquals("application/octet-stream", infoOf("a.txt").mimeType)
    }
}
