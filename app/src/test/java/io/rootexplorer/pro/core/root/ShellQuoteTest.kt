package io.rootexplorer.pro.core.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command-injection tests for the shell quoting layer.
 *
 * This is the highest-risk code in the project: every one of these strings, if it reached a
 * root shell unquoted, would execute as root. The tests assert the exact quoted form rather
 * than merely that something changed.
 */
class ShellQuoteTest {

    @Test
    fun `ordinary paths are left readable`() {
        assertEquals("/data/adb", ShellQuote.quote("/data/adb"))
        assertEquals("magisk.db", ShellQuote.quote("magisk.db"))
    }

    @Test
    fun `spaces are preserved and quoted`() {
        assertEquals("'/storage/emulated/0/my documents'", ShellQuote.quote("/storage/emulated/0/my documents"))
    }

    @Test
    fun `command substitution is neutralised`() {
        // Without quoting, $(...) would execute.
        assertEquals("'/tmp/$(reboot)'", ShellQuote.quote("/tmp/\$(reboot)"))
        assertEquals("'/tmp/`reboot`'", ShellQuote.quote("/tmp/`reboot`"))
    }

    @Test
    fun `command separators are neutralised`() {
        assertEquals("'/tmp/a;rm -rf /'", ShellQuote.quote("/tmp/a;rm -rf /"))
        assertEquals("'/tmp/a&&reboot'", ShellQuote.quote("/tmp/a&&reboot"))
        assertEquals("'/tmp/a|reboot'", ShellQuote.quote("/tmp/a|reboot"))
    }

    @Test
    fun `embedded single quotes use the close escape reopen form`() {
        // "it's" must survive as one argument containing a literal apostrophe.
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
    }

    @Test
    fun `a name that is only a quote is fully escaped`() {
        assertEquals("''\\'''", ShellQuote.quote("'"))
    }

    @Test
    fun `redirection and glob characters are neutralised`() {
        assertEquals("'/tmp/a>b'", ShellQuote.quote("/tmp/a>b"))
        assertEquals("'/tmp/a<b'", ShellQuote.quote("/tmp/a<b"))
        assertEquals("'/tmp/*'", ShellQuote.quote("/tmp/*"))
        assertEquals("'/tmp/?x'", ShellQuote.quote("/tmp/?x"))
        assertEquals("'/tmp/[a]'", ShellQuote.quote("/tmp/[a]"))
        assertEquals("'/tmp/~root'", ShellQuote.quote("/tmp/~root"))
        assertEquals("'/tmp/a#b'", ShellQuote.quote("/tmp/a#b"))
    }

    @Test
    fun `variable expansion is neutralised`() {
        assertEquals("'/tmp/\$HOME'", ShellQuote.quote("/tmp/\$HOME"))
    }

    @Test
    fun `newlines and tabs are kept inside the quotes`() {
        val quoted = ShellQuote.quote("/tmp/a\nb")
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertTrue(quoted.contains("\n"))
        // The newline must be inside the quotes, never able to start a second command.
        assertTrue(quoted.indexOf('\n') < quoted.lastIndexOf('\''))
    }

    @Test
    fun `empty argument becomes an empty quoted string`() {
        assertEquals("''", ShellQuote.quote(""))
    }

    @Test
    fun `join produces one word per argument`() {
        assertEquals("/a /b", ShellQuote.join("/a", "/b"))
        assertEquals("'/a b' /c", ShellQuote.join("/a b", "/c"))
        assertEquals("'/a b' '/c d'", ShellQuote.join(listOf("/a b", "/c d")))
    }

    @Test
    fun `join keeps a malicious second argument contained`() {
        val command = "mv ${ShellQuote.join("/data/x", "/data/y; reboot")}"
        assertEquals("mv /data/x '/data/y; reboot'", command)
        assertFalse(command.endsWith("reboot"))
    }

    @Test
    fun `chinese and other non-ascii names are quoted unchanged`() {
        val name = "/storage/emulated/0/我的 文件.txt"
        val quoted = ShellQuote.quote(name)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertTrue(quoted.contains("我的 文件.txt"))
    }
}
