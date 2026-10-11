package io.rootexplorer.pro.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `chmod`/`chown` decisions against values measured on a real rooted device.
 *
 * Every constant here was read off a Xiaomi 2211133C (Android 17, KernelUI) with `su`, not
 * invented to match the implementation. That matters because this was the last safety-relevant
 * path with no device evidence behind it, and the figures it depends on are exactly the kind of
 * thing that differs between toybox versions.
 *
 * Measured on the device:
 *
 * ```
 * touch f; chmod 4755 f;  stat -c '%A|%a' f  ->  -rwsr-xr-x|4755
 * chmod 2755 f;           stat -c '%A|%a' f  ->  -rwxr-sr-x|2755
 * chmod 1755 f;           stat -c '%A|%a' f  ->  -rwxr-xr-t|1755
 * chmod 644 f;            stat -c '%A|%a' f  ->  -rw-r--r--|644
 * chmod 755 /system/build.prop
 *   -> "chmod: chmod '/system/build.prop' to 0755: Read-only file system", exit 1
 *   -> mode afterwards still 600, i.e. unchanged
 * chown shell:shell f;    stat -> uid=2000 gid=2000 owner=shell group=shell
 * chown 0:0 f;            stat -> uid=0 gid=0 owner=root group=root
 * chown nosuchuser12345 f -> "chown: bad user 'nosuchuser12345'", exit 1, uid unchanged
 * ```
 *
 * The load-bearing conclusion is that `stat -c %a` reports **four** digits (`4755`), so comparing
 * the numeric mode is safe: setuid survives the comparison. Had it reported three, every setuid
 * change would have looked like a failure.
 */
class PermissionDeviceEvidenceTest {

    private val changer = PermissionChanger(
        rootShell = io.rootexplorer.pro.core.root.RootShell(),
        rootAvailable = { false },
    )

    /** Parse a symbolic permission, failing loudly if the device's form is not understood. */
    private fun symbolic(text: String): LinuxPermission {
        val parsed = LinuxPermission.fromSymbolic(text)
        assertNotNull("the device printed '$text', which must parse", parsed)
        return parsed!!
    }

    /** Parse an octal permission the way the editor produces one. */
    private fun octal(text: String): LinuxPermission {
        val parsed = LinuxPermission.fromOctal(text)
        assertNotNull("'$text' must parse", parsed)
        return parsed!!
    }

    // ---------------------------------------------------------------- the 4-digit premise

    @Test
    fun `a measured setuid mode parses with the setuid bit intact`() {
        // "-rwsr-xr-x|4755" is what the device printed.
        val parsed = symbolic("-rwsr-xr-x")
        assertEquals(0b100_111_101_101, parsed.modeBits)
        assertEquals("4755", parsed.octal)
        assertTrue("the setuid bit must be visible to the comparison", parsed.modeBits and 0b100_000_000_000 != 0)
    }

    @Test
    fun `a measured setgid mode parses with the setgid bit intact`() {
        val parsed = symbolic("-rwxr-sr-x")
        assertEquals(0b010_111_101_101, parsed.modeBits)
        assertEquals("2755", parsed.octal)
    }

    @Test
    fun `a measured sticky mode parses with the sticky bit intact`() {
        val parsed = symbolic("-rwxr-xr-t")
        assertEquals(0b001_111_101_101, parsed.modeBits)
        assertEquals("1755", parsed.octal)
    }

    // ---------------------------------------------------------------- the applied octal form

    @Test
    fun `the octal that gets applied keeps the special bits`() {
        // The device accepts and reports back the four-digit form, so that is the form `chmod`
        // must be given. Asserted on the accessor the engine now uses.
        assertEquals("4755", symbolic("-rwsr-xr-x").octal)
        assertEquals("2755", symbolic("-rwxr-sr-x").octal)
        assertEquals("1755", symbolic("-rwxr-xr-t").octal)
        assertEquals("0755", symbolic("-rwxr-xr-x").octal)
        assertEquals("0644", symbolic("-rw-r--r--").octal)
    }

    @Test
    fun `the masked octal form really does drop the special bits`() {
        // This is why the choice of accessor matters: octalSimple masks with 0x1FF, so feeding it
        // to chmod silently discarded setuid/setgid/sticky. Recorded here so the distinction
        // cannot be lost again.
        val setuid = symbolic("-rwsr-xr-x")
        assertEquals("4755", setuid.octal)
        assertEquals("755", setuid.octalSimple)
        assertTrue(
            "octalSimple must never be used as a chmod argument",
            setuid.octal != setuid.octalSimple,
        )
    }

    @Test
    fun `a setuid request round-trips through octal parsing`() {
        // What the editor's octal field produces after the fix: it is seeded with `octal` and
        // parsed back by fromOctal, which must preserve all four digits.
        val typed = octal("4755")
        assertEquals(0b100_111_101_101, typed.modeBits)
        assertTrue(typed.setUid)
        assertEquals("4755", typed.octal)
    }

    @Test
    fun `the observed setuid mode equals the requested one, so a real success is not called a failure`() {
        // requested 4755 (from the editor) vs observed 4755 (from the device re-read)
        val requested = octal("4755")
        val observed = symbolic("-rwsr-xr-x")

        val outcome = changer.decideModeOutcome(
            requested = requested,
            observed = observed,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(
            "a genuine setuid success must be reported as applied, was $outcome",
            outcome is ChangeResult.Applied,
        )
    }

    // ---------------------------------------------------------------- never a false success

    @Test
    fun `a silent failure on setuid is reported as not applied, not success`() {
        // The device's read-only case: the command "succeeds" but nothing changed. This is the
        // privilege-escalation-relevant false success the design exists to prevent.
        val requested = octal("4755") // 4755
        val observed = symbolic("-rwxr-xr-x") // 755, setuid silently dropped

        val outcome = changer.decideModeOutcome(requested, observed, commandExitCode = 0, commandOutput = "")
        assertTrue("must not claim success: $outcome", outcome is ChangeResult.NotApplied)
    }

    @Test
    fun `the real read-only filesystem message produces a failure carrying that text`() {
        // Verbatim stderr from the device.
        val message = "chmod: chmod '/system/build.prop' to 0755: Read-only file system"

        val outcome = changer.decideModeOutcome(
            requested = octal("755"),
            // The file was 600 before and after, which is what the re-read saw.
            observed = symbolic("-rw-------"),
            commandExitCode = 1,
            commandOutput = message,
        )

        assertTrue("a failing command must fail: $outcome", outcome is ChangeResult.Failed)
        assertEquals(message, (outcome as ChangeResult.Failed).reason)
    }

    @Test
    fun `a read-only path is recognised from the device path list`() {
        // These are the partitions the device confirmed read-only ("Read-only file system").
        assertTrue(changer.isTypicallyReadOnly("/system/build.prop"))
        assertTrue(changer.isTypicallyReadOnly("/system"))
        assertTrue(changer.isTypicallyReadOnly("/vendor/lib64/libc.so"))
        assertTrue(changer.isTypicallyReadOnly("/product/etc/build.prop"))

        // /data is writable on this device, so it must not be treated as read-only.
        assertFalse(changer.isTypicallyReadOnly("/data/local/tmp/rootexplorer_permtest"))
        assertFalse(changer.isTypicallyReadOnly("/storage/emulated/0/Download"))
    }

    @Test
    fun `an unverifiable mode is reported as unverified, never as applied`() {
        val outcome = changer.decideModeOutcome(
            requested = octal("755"),
            observed = null,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue("no observation means no claim: $outcome", outcome is ChangeResult.Unverified)
    }

    // ---------------------------------------------------------------- ownership

    @Test
    fun `the measured name-based chown is recognised as applied`() {
        // chown shell:shell -> uid=2000 gid=2000 owner=shell group=shell
        val outcome = changer.decideOwnershipOutcome(
            requestedOwner = "shell",
            requestedOwnerId = null,
            requestedGroup = "shell",
            requestedGroupId = null,
            observedOwnerName = "shell",
            observedOwnerId = 2000,
            observedGroupName = "shell",
            observedGroupId = 2000,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue("was $outcome", outcome is ChangeResult.Applied)
    }

    @Test
    fun `the measured numeric chown is recognised as applied`() {
        // chown 0:0 -> uid=0 gid=0 owner=root group=root
        val outcome = changer.decideOwnershipOutcome(
            requestedOwner = "0",
            requestedOwnerId = 0,
            requestedGroup = "0",
            requestedGroupId = 0,
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue("a numeric request matched by name must verify: $outcome", outcome is ChangeResult.Applied)
    }

    @Test
    fun `the real bad-user message produces a failure`() {
        // Verbatim stderr, exit 1, ownership unchanged.
        val message = "chown: bad user 'nosuchuser12345'"

        val outcome = changer.decideOwnershipOutcome(
            requestedOwner = "nosuchuser12345",
            requestedOwnerId = null,
            requestedGroup = null,
            requestedGroupId = null,
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 1,
            commandOutput = message,
        )
        assertTrue("was $outcome", outcome is ChangeResult.Failed)
        assertEquals(message, (outcome as ChangeResult.Failed).reason)
    }

    @Test
    fun `a chown that silently did nothing is not reported as applied`() {
        // Exit 0 but the owner is still root, which is the shape a false success would take.
        val outcome = changer.decideOwnershipOutcome(
            requestedOwner = "shell",
            requestedOwnerId = null,
            requestedGroup = null,
            requestedGroupId = null,
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue("was $outcome", outcome is ChangeResult.NotApplied)
    }

    // ---------------------------------------------------------------- validation, per the device

    @Test
    fun `the device sandbox path the tests used is accepted as a target`() {
        // The exact path used for the verification run must pass validation.
        assertEquals(null, changer.validateTarget("/data/local/tmp/rootexplorer_permtest"))
    }

    @Test
    fun `a relative path is still refused even though the shell would resolve it`() {
        // The shell's cwd is not a location the user chose, so a relative target is refused.
        assertTrue(changer.validateTarget("rootexplorer_permtest") != null)
        assertTrue(changer.validateTarget("./f") != null)
    }
}
