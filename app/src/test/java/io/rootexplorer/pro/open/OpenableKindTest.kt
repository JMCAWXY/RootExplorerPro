package io.rootexplorer.pro.open

import io.rootexplorer.pro.core.filesystem.FileEntry
import io.rootexplorer.pro.core.filesystem.FileKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What "open" means for each kind of entry.
 *
 * The rule is pure, so it is tested here rather than by tapping rows on a device. Getting it wrong
 * is visible: an image that fails to open, or a file that claims to be openable and then does
 * nothing.
 */
class OpenableKindTest {

    private fun entry(
        name: String,
        kind: FileKind = FileKind.REGULAR,
        linkTargetIsDirectory: Boolean? = null,
    ) = FileEntry(
        name = name,
        path = "/data/$name",
        kind = kind,
        sizeBytes = null,
        lastModifiedMillis = null,
        permission = null,
        uid = null,
        gid = null,
        ownerName = null,
        groupName = null,
        linkTarget = null,
        linkTargetIsDirectory = linkTargetIsDirectory,
    )

    @Test
    fun `a directory is navigation`() {
        assertEquals(OpenableKind.DIRECTORY, openableKind(entry("DCIM", FileKind.DIRECTORY)))
    }

    @Test
    fun `a link that leads to a directory is navigation too`() {
        assertEquals(
            OpenableKind.DIRECTORY,
            openableKind(entry("sdcard", FileKind.SYMLINK, linkTargetIsDirectory = true)),
        )
    }

    @Test
    fun `a link that does not lead to a directory is not navigation`() {
        assertEquals(
            OpenableKind.UNSUPPORTED,
            openableKind(entry("link", FileKind.SYMLINK, linkTargetIsDirectory = false)),
        )
    }

    @Test
    fun `an image opens the viewer, whatever the case of the extension`() {
        assertEquals(OpenableKind.IMAGE, openableKind(entry("photo.JPG")))
        assertEquals(OpenableKind.IMAGE, openableKind(entry("shot.png")))
    }

    @Test
    fun `a name the archive engine can parse opens the archive browser`() {
        assertEquals(OpenableKind.ARCHIVE, openableKind(entry("backup.zip")))
        assertEquals(OpenableKind.ARCHIVE, openableKind(entry("logs.tar.gz")))
    }

    @Test
    fun `an installer package is not treated as an archive`() {
        // An APK is a zip underneath, but the archive engine does not parse the name, so claiming
        // it would open a browser that could only fail.
        assertEquals(OpenableKind.UNSUPPORTED, openableKind(entry("app.apk")))
    }

    @Test
    fun `an unknown type is reported as unsupported rather than doing nothing quietly`() {
        assertEquals(OpenableKind.UNSUPPORTED, openableKind(entry("readme")))
        assertEquals(OpenableKind.UNSUPPORTED, openableKind(entry("data.bin")))
    }

    @Test
    fun `a text file opens the editor`() {
        assertEquals(OpenableKind.TEXT, openableKind(entry("notes.txt")))
        assertEquals(OpenableKind.TEXT, openableKind(entry("config.json")))
        assertEquals(OpenableKind.TEXT, openableKind(entry("AndroidManifest.xml")))
        assertEquals(OpenableKind.TEXT, openableKind(entry("build.gradle")))
        assertEquals(OpenableKind.TEXT, openableKind(entry("values.yaml")))
    }

    @Test
    fun `a shell script is offered the run dialog rather than the editor`() {
        // Running is what a `.sh` is usually for; the dialog still offers 编辑 as a button.
        assertEquals(OpenableKind.SCRIPT, openableKind(entry("boot.sh")))
        assertEquals(OpenableKind.SCRIPT, openableKind(entry("setup.BASH")))
    }

    @Test
    fun `a binary document is not treated as text just because it is a document`() {
        assertEquals(OpenableKind.UNSUPPORTED, openableKind(entry("report.pdf")))
        assertEquals(OpenableKind.UNSUPPORTED, openableKind(entry("scan.docx")))
        assertEquals(OpenableKind.UNSUPPORTED, openableKind(entry("book.epub")))
    }
}
