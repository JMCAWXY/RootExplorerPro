package io.rootexplorer.pro.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal command framing.
 *
 * The framing is where a silent mistake costs the most: if the sentinel is not exactly what the
 * shell prints, every command appears to hang until its timeout, and if the status is misread a
 * failed command looks successful. Both halves are pure functions, so they are asserted here
 * rather than discovered on a device.
 *
 * The escaping rule is the subtle part: the frame is built in Kotlin and executed by the shell, so
 * `$?`, `$(pwd)` and `$__DSH_STATUS` must reach the shell unexpanded by Kotlin.
 */
class ShellSessionFramingTest {

    // ------------------------------------------------------------------ the frame

    @Test
    fun `the frame leaves shell expansions for the shell`() {
        val framed = ShellSession.frame("ls -la")

        // Kotlin must not have consumed these: they must still be literal text going to the shell.
        assertTrue("exit status must reach the shell", framed.contains("\"\$?\""))
        assertTrue("pwd substitution must reach the shell", framed.contains("\$(pwd"))
    }

    @Test
    fun `the frame starts with the command itself`() {
        val framed = ShellSession.frame("id")
        assertEquals("id", framed.lineSequence().first())
    }

    @Test
    fun `the marker text appears exactly once in the frame`() {
        // The marker is a Kotlin const, so it is inlined. If any shell variable shared its name the
        // word would occur twice and the frame would be ambiguous.
        val framed = ShellSession.frame("id")
        val occurrences = Regex(Regex.escape(ShellSession.SENTINEL_PREFIX)).findAll(framed).count()
        assertEquals("the marker must appear once: $framed", 1, occurrences)
    }

    @Test
    fun `the frame ends with a status line carrying the sentinel`() {
        val framed = ShellSession.frame("id")
        val lastLine = framed.trimEnd('\n').lineSequence().last()

        assertTrue(
            "the last line must print the sentinel: $lastLine",
            lastLine.contains(ShellSession.SENTINEL_PREFIX),
        )
        assertTrue("the status line is printed by printf: $lastLine", lastLine.startsWith("printf"))
    }

    @Test
    fun `a command containing quotes is passed through unchanged`() {
        // The frame must not try to re-quote the user's command; the shell receives it verbatim.
        val command = """echo "hello world" && echo 'single'"""
        val framed = ShellSession.frame(command)
        assertTrue(framed.startsWith(command))
    }

    @Test
    fun `a multi-line command still gets exactly one status line`() {
        val framed = ShellSession.frame("echo a\necho b")
        val sentinelCount = Regex(Regex.escape(ShellSession.SENTINEL_PREFIX)).findAll(framed).count()
        assertEquals("exactly one status line", 1, sentinelCount)
    }

    @Test
    fun `the sentinel is distinctive enough not to occur by accident`() {
        // A command that echoes ordinary text must not be mistaken for the end of the frame.
        assertTrue(ShellSession.SENTINEL_PREFIX.startsWith("__"))
        assertTrue(ShellSession.SENTINEL_PREFIX.endsWith("="))
    }

    // ------------------------------------------------------------------ the status payload

    @Test
    fun `a success payload yields exit code zero and a directory`() {
        val parsed = ShellSession.parseStatusPayload("0|/data/adb")
        assertEquals(0, parsed.exitCode)
        assertEquals("/data/adb", parsed.directory)
    }

    @Test
    fun `a failure payload keeps its non-zero code`() {
        val parsed = ShellSession.parseStatusPayload("1|/system")
        assertEquals(1, parsed.exitCode)
        assertEquals("/system", parsed.directory)
    }

    @Test
    fun `a large exit code is preserved`() {
        assertEquals(127, ShellSession.parseStatusPayload("127|/").exitCode)
        assertEquals(255, ShellSession.parseStatusPayload("255|/").exitCode)
    }

    @Test
    fun `an empty directory half is dropped rather than shown`() {
        // `pwd` can fail, which leaves the second half empty.
        val parsed = ShellSession.parseStatusPayload("0|")
        assertEquals(0, parsed.exitCode)
        assertNull("an empty directory must not become the shown path", parsed.directory)
    }

    @Test
    fun `a relative directory is rejected`() {
        // Only an absolute path is a believable `pwd` result.
        assertNull(ShellSession.parseStatusPayload("0|relative/dir").directory)
        assertNull(ShellSession.parseStatusPayload("0|.").directory)
    }

    @Test
    fun `a missing separator leaves the directory null but keeps the code`() {
        val parsed = ShellSession.parseStatusPayload("0")
        assertEquals(0, parsed.exitCode)
        assertNull(parsed.directory)
    }

    @Test
    fun `an unparsable exit code stays null instead of becoming zero`() {
        // Reporting a bogus 0 would present a failed command as a success.
        val parsed = ShellSession.parseStatusPayload("notanumber|/data")
        assertNull(parsed.exitCode)
        assertEquals("/data", parsed.directory)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val parsed = ShellSession.parseStatusPayload("  0|/data/local/tmp  ")
        assertEquals(0, parsed.exitCode)
        assertEquals("/data/local/tmp", parsed.directory)
    }

    @Test
    fun `a directory containing spaces survives`() {
        val parsed = ShellSession.parseStatusPayload("0|/storage/emulated/0/my documents")
        assertEquals("/storage/emulated/0/my documents", parsed.directory)
    }

    @Test
    fun `an empty payload yields nulls`() {
        val parsed = ShellSession.parseStatusPayload("")
        assertNull(parsed.exitCode)
        assertNull(parsed.directory)
    }

    // ------------------------------------------------------------------ constants

    @Test
    fun `the output caps are finite and generous`() {
        // A cap of zero, or an absurd one, would break either the transcript or the memory bound.
        assertTrue(ShellSession.MAX_OUTPUT_LINES in 100..100_000)
        assertTrue(ShellSession.MAX_OUTPUT_CHARS in 10_000..10_000_000)
    }

    @Test
    fun `suggestions are read-only commands`() {
        // Tapping a suggestion must not be able to change the device, so nothing destructive is
        // offered as a one-tap example.
        val destructive = listOf("rm", "mv", "chmod", "chown", "dd", "mkfs", "reboot", "pm ", "kill")
        for (suggestion in ShellSession.suggestions) {
            for (bad in destructive) {
                assertTrue(
                    "suggestion '$suggestion' must not be destructive",
                    !suggestion.contains(bad),
                )
            }
        }
    }

    @Test
    fun `both shell kinds are labelled`() {
        assertEquals("普通 Shell", ShellKind.NORMAL.label)
        assertEquals("Root Shell", ShellKind.ROOT.label)
    }
}
