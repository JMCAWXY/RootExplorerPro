package io.rootexplorer.pro.core.text

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Text loading, encoding detection, newline handling and the binary guard.
 *
 * The refusal cases carry the most weight: opening a binary file as text and saving it back is
 * how an editor destroys data, so each guard is asserted explicitly.
 */
class TextFileEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val engine = TextFileEngine()

    // ------------------------------------------------------------------ loading

    @Test
    fun `plain utf8 text loads with LF detected`() = runTest {
        val file = temp.newFile("a.txt").apply { writeText("line1\nline2\nline3\n") }

        val result = engine.load(file.absolutePath)
        assertTrue(result is TextLoadResult.Success)
        val document = (result as TextLoadResult.Success).document

        assertEquals("UTF-8", document.encoding)
        assertEquals(LineEnding.LF, document.lineEnding)
        assertFalse(document.hadBom)
        assertFalse(document.usedFallbackEncoding)
        assertEquals(4, document.lineCount)
    }

    @Test
    fun `crlf line endings are detected`() = runTest {
        val file = temp.newFile("b.txt").apply { writeText("line1\r\nline2\r\n") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        assertEquals(LineEnding.CRLF, document.lineEnding)
    }

    @Test
    fun `classic mac cr line endings are detected`() = runTest {
        val file = temp.newFile("c.txt").apply { writeText("line1\rline2\r") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        assertEquals(LineEnding.CR, document.lineEnding)
    }

    @Test
    fun `a utf8 bom is detected and stripped from the text`() = runTest {
        val file = temp.newFile("bom.txt")
        file.writeBytes(TextDocument.BOM_UTF8 + "hello".toByteArray(Charsets.UTF_8))

        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        assertTrue(document.hadBom)
        assertEquals("hello", document.text)
    }

    @Test
    fun `a plain utf8 file is not given a bom on save`() = runTest {
        val file = temp.newFile("nobom.txt").apply { writeText("hello") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        val bytes = document.serialise("hello")
        // Adding a BOM would change the first bytes for every other reader of the file.
        assertFalse(bytes.size >= 3 && bytes[0] == TextDocument.BOM_UTF8[0])
        assertEquals("hello", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `a bom is restored on save when the file had one`() = runTest {
        val file = temp.newFile("keepbom.txt")
        file.writeBytes(TextDocument.BOM_UTF8 + "hello".toByteArray(Charsets.UTF_8))
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        val bytes = document.serialise("changed")
        assertTrue(bytes.size > 3)
        assertEquals(TextDocument.BOM_UTF8[0], bytes[0])
        assertEquals("changed", String(bytes.copyOfRange(3, bytes.size), Charsets.UTF_8))
    }

    @Test
    fun `chinese text in utf8 loads correctly`() = runTest {
        val file = temp.newFile("cn.txt").apply { writeText("你好，世界\n第二行") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        assertEquals("UTF-8", document.encoding)
        assertTrue(document.text.startsWith("你好，世界"))
    }

    @Test
    fun `gb18030 text is decoded with the fallback and the encoding is recorded`() = runTest {
        val file = temp.newFile("gb.txt")
        file.writeBytes("中文测试".toByteArray(charset("GB18030")))

        val result = engine.load(file.absolutePath)
        assertTrue(result is TextLoadResult.Success)
        val document = (result as TextLoadResult.Success).document

        assertEquals("GB18030", document.encoding)
        assertTrue(document.usedFallbackEncoding)
        assertEquals("中文测试", document.text)
    }

    @Test
    fun `saving a gb18030 document writes gb18030 bytes back`() = runTest {
        val file = temp.newFile("gb2.txt")
        file.writeBytes("中文".toByteArray(charset("GB18030")))
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        val bytes = document.serialise("中文")
        assertEquals("中文", String(bytes, charset("GB18030")))
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `a file with nul bytes is refused as binary`() = runTest {
        val file = temp.newFile("bin.dat")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x02))

        val result = engine.load(file.absolutePath)
        assertTrue("expected a binary refusal, got $result", result is TextLoadResult.Binary)
        assertTrue((result as TextLoadResult.Binary).reason.contains("NUL"))
    }

    @Test
    fun `a file full of control characters is refused as binary`() = runTest {
        val file = temp.newFile("control.dat")
        file.writeBytes(ByteArray(600) { 0x01 })

        val result = engine.load(file.absolutePath)
        assertTrue(result is TextLoadResult.Binary)
    }

    @Test
    fun `a directory is refused`() = runTest {
        val dir = temp.newFolder("d")
        val result = engine.load(dir.absolutePath)
        assertTrue(result is TextLoadResult.Failure)
        assertTrue((result as TextLoadResult.Failure).reason.contains("目录"))
    }

    @Test
    fun `a missing file is refused`() = runTest {
        val result = engine.load(File(temp.root, "gone.txt").absolutePath)
        assertTrue(result is TextLoadResult.Failure)
    }

    @Test
    fun `a file over the size limit is refused with both numbers`() = runTest {
        val small = TextFileEngine(maxEditableBytes = 10)
        val file = temp.newFile("big.txt").apply { writeText("x".repeat(100)) }

        val result = small.load(file.absolutePath)
        assertTrue(result is TextLoadResult.TooLarge)
        val tooLarge = result as TextLoadResult.TooLarge
        assertEquals(100L, tooLarge.sizeBytes)
        assertEquals(10L, tooLarge.limitBytes)
    }

    @Test
    fun `an empty file loads as an empty document`() = runTest {
        val file = temp.newFile("empty.txt").apply { writeText("") }
        val result = engine.load(file.absolutePath)

        assertTrue(result is TextLoadResult.Success)
        val document = (result as TextLoadResult.Success).document
        assertEquals("", document.text)
        assertEquals(1, document.lineCount)
    }

    // ------------------------------------------------------------------ binary sniffing

    @Test
    fun `sniffing accepts ordinary text`() {
        assertNull(engine.sniffBinary("hello world\nsecond line".toByteArray()))
        assertNull(engine.sniffBinary("中文\n".toByteArray(Charsets.UTF_8)))
        assertNull(engine.sniffBinary(ByteArray(0)))
    }

    @Test
    fun `sniffing allows tab, lf, cr and form feed`() {
        assertNull(engine.sniffBinary("a\tb\nc\rd\u000Cf".toByteArray()))
    }

    @Test
    fun `sniffing rejects a single nul byte`() {
        assertTrue(engine.sniffBinary(byteArrayOf(0x61, 0x00, 0x62)) != null)
    }

    // ------------------------------------------------------------------ line endings

    @Test
    fun `normalising to crlf does not double the line breaks`() {
        val engineLocal = TextFileEngine()
        // A naive triple replace turns CRLF into two newlines; this must not.
        val result = engineLocal.normaliseLineEndings("a\r\nb\nc\rd", LineEnding.CRLF)
        assertEquals("a\r\nb\r\nc\r\nd", result)
        assertEquals(3, Regex("\r\n").findAll(result).count())
    }

    @Test
    fun `normalising to lf collapses every style`() {
        val result = TextFileEngine().normaliseLineEndings("a\r\nb\nc\rd", LineEnding.LF)
        assertEquals("a\nb\nc\nd", result)
    }

    @Test
    fun `detect prefers the dominant style`() {
        assertEquals(LineEnding.LF, LineEnding.detect("a\nb\nc\n"))
        assertEquals(LineEnding.CRLF, LineEnding.detect("a\r\nb\r\nc\r\n"))
        assertEquals(LineEnding.CR, LineEnding.detect("a\rb\rc\r"))
        assertEquals(LineEnding.LF, LineEnding.detect("no newline at all"))
    }

    // ------------------------------------------------------------------ search and replace

    @Test
    fun `match counting respects case sensitivity`() {
        val text = "Foo foo FOO"
        assertEquals(3, engine.countMatches(text, "foo", caseSensitive = false))
        assertEquals(1, engine.countMatches(text, "foo", caseSensitive = true))
        assertEquals(1, engine.countMatches(text, "Foo", caseSensitive = true))
    }

    @Test
    fun `an empty query counts nothing`() {
        assertEquals(0, engine.countMatches("abc", ""))
    }

    @Test
    fun `replace all reports how many replacements were made`() {
        val result = engine.replaceAll("foo bar foo", "foo", "baz", caseSensitive = true)
        assertEquals("baz bar baz", result.text)
        assertEquals(2, result.count)
    }

    @Test
    fun `replace all with no matches changes nothing`() {
        val result = engine.replaceAll("abc", "zzz", "y")
        assertEquals("abc", result.text)
        assertEquals(0, result.count)
    }

    @Test
    fun `the replacement is inserted literally without regex interpretation`() {
        // A user replacing text in a config file should not have to escape $ or backslashes.
        val result = engine.replaceAll("KEY=old", "old", "\$1\\n", caseSensitive = true)
        assertEquals("KEY=\$1\\n", result.text)
    }

    @Test
    fun `a query containing regex metacharacters is treated literally`() {
        val result = engine.replaceAll("a.b.c", ".", "-", caseSensitive = true)
        assertEquals("a-b-c", result.text)
        assertEquals(2, result.count)
    }

    // ------------------------------------------------------------------ saving

    @Test
    fun `saving writes the new content and creates a backup`() = runTest {
        val file = temp.newFile("save.txt").apply { writeText("original") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        val outcome = engine.save(document, "modified")

        assertTrue(outcome is SaveOutcome.Success)
        assertEquals("modified", file.readText())
        val backupPath = (outcome as SaveOutcome.Success).backupPath
        assertTrue("a backup must exist", backupPath != null)
        assertEquals("original", File(backupPath!!).readText())
    }

    @Test
    fun `saving preserves the detected line ending`() = runTest {
        val file = temp.newFile("crlf.txt").apply { writeText("a\r\nb\r\n") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        engine.save(document, "x\ny")

        assertEquals("x\r\ny", file.readText())
    }

    @Test
    fun `saving can skip the backup when asked`() = runTest {
        val file = temp.newFile("nobak.txt").apply { writeText("original") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        val outcome = engine.save(document, "modified", createBackup = false)

        assertTrue(outcome is SaveOutcome.Success)
        assertNull((outcome as SaveOutcome.Success).backupPath)
        assertFalse(File(temp.root, "nobak.txt.bak").exists())
    }

    @Test
    fun `no temporary file is left behind after a save`() = runTest {
        val file = temp.newFile("clean.txt").apply { writeText("original") }
        val document = (engine.load(file.absolutePath) as TextLoadResult.Success).document

        engine.save(document, "modified")

        val leftovers = temp.root.listFiles()?.filter { it.name.startsWith(".clean.txt") }.orEmpty()
        assertTrue("a temporary file was left behind: $leftovers", leftovers.isEmpty())
    }
}
