package io.rootexplorer.pro.core.filesystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Path handling.
 *
 * Wrong normalisation is how a file manager ends up operating on a path the user did not
 * choose, so these cases are asserted explicitly.
 */
class FsEnginePathTest {

    @Test
    fun `normalise adds a leading slash`() {
        assertEquals("/data", FsEngine.normalise("data"))
        assertEquals("/data/adb", FsEngine.normalise("data/adb"))
    }

    @Test
    fun `normalise collapses repeated slashes`() {
        assertEquals("/data/adb", FsEngine.normalise("/data//adb"))
        assertEquals("/data/adb", FsEngine.normalise("///data///adb"))
    }

    @Test
    fun `normalise removes trailing slashes except at root`() {
        assertEquals("/data", FsEngine.normalise("/data/"))
        assertEquals("/", FsEngine.normalise("/"))
        assertEquals("/", FsEngine.normalise("////"))
    }

    @Test
    fun `normalise resolves dot segments`() {
        assertEquals("/data/adb", FsEngine.normalise("/data/./adb"))
        assertEquals("/data", FsEngine.normalise("/data/adb/.."))
        assertEquals("/", FsEngine.normalise("/data/.."))
    }

    @Test
    fun `normalise does not escape above the root`() {
        assertEquals("/", FsEngine.normalise("/../../.."))
        assertEquals("/data", FsEngine.normalise("/data/../data/../data"))
    }

    @Test
    fun `normalise trims whitespace and handles blank input`() {
        assertEquals("/data", FsEngine.normalise("  /data  "))
        assertEquals("/", FsEngine.normalise(""))
    }

    @Test
    fun `normalise keeps spaces inside a path`() {
        assertEquals("/storage/emulated/0/my documents", FsEngine.normalise("/storage/emulated/0/my documents/"))
    }

    @Test
    fun `normalise keeps unicode and newline characters`() {
        assertEquals("/data/我的文件", FsEngine.normalise("/data/我的文件"))
        assertEquals("/data/a\nb", FsEngine.normalise("/data/a\nb"))
    }

    @Test
    fun `parentOf returns the lexical parent`() {
        assertEquals("/data", FsEngine.parentOf("/data/adb"))
        assertEquals("/", FsEngine.parentOf("/data"))
        assertEquals("/storage/emulated/0", FsEngine.parentOf("/storage/emulated/0/Download"))
    }

    @Test
    fun `parentOf returns null at the root`() {
        assertNull(FsEngine.parentOf("/"))
    }

    @Test
    fun `parentOf normalises its input first`() {
        assertEquals("/data", FsEngine.parentOf("/data/adb/"))
        // "/data/adb/./" normalises to "/data/adb", so its parent is "/data".
        assertEquals("/data", FsEngine.parentOf("/data/adb/./"))
        // "/data/adb/.." normalises to "/data", so parentOf answers about "/data" — "/".
        // This is the deliberate lexical behaviour: ".." resolves before the parent is taken.
        assertEquals("/", FsEngine.parentOf("/data/adb/.."))
        assertEquals("/data", FsEngine.parentOf("//data//adb//"))
    }

    @Test
    fun `childOf appends to a path`() {
        assertEquals("/data/adb", FsEngine.childOf("/data", "adb"))
        assertEquals("/adb", FsEngine.childOf("/", "adb"))
    }

    @Test
    fun `childOf handles a trailing slash on the parent`() {
        assertEquals("/data/adb", FsEngine.childOf("/data/", "adb"))
    }

    @Test
    fun `childOf keeps a name with spaces intact`() {
        assertEquals("/data/my file.txt", FsEngine.childOf("/data", "my file.txt"))
    }
}
