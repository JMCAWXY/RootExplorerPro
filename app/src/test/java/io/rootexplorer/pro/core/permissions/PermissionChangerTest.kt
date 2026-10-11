package io.rootexplorer.pro.core.permissions

import io.rootexplorer.pro.core.root.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `chmod`/`chown` rules.
 *
 * The most important behaviour in this file is the verification rule the brief calls out: an
 * exit code of 0 must never be enough to claim success. On a read-only partition or a
 * FUSE-backed mount, the command can succeed while the attributes are unchanged, and reporting
 * that as success would be a lie.
 */
class PermissionChangerTest {

    /** Root is never available here, so no `su` process is ever started. */
    private fun changer() = PermissionChanger(rootShell = RootShell(), rootAvailable = { false })

    private fun changerWithRoot() = PermissionChanger(rootShell = RootShell(), rootAvailable = { true })

    // ------------------------------------------------------------------ mode decision

    @Test
    fun `a matching re-read is reported as applied`() {
        val requested = LinuxPermission.fromOctal("600")!!
        val observed = LinuxPermission.fromOctal("600")!!

        val result = changer().decideModeOutcome(requested, observed, commandExitCode = 0, commandOutput = "")

        assertTrue(result is ChangeResult.Applied)
        assertEquals("600", (result as ChangeResult.Applied).after)
    }

    @Test
    fun `an exit code of zero with unchanged mode is NOT success`() {
        // This is the exact scenario the brief warns about.
        val requested = LinuxPermission.fromOctal("600")!!
        val observed = LinuxPermission.fromOctal("644")!!

        val result = changer().decideModeOutcome(requested, observed, commandExitCode = 0, commandOutput = "")

        assertTrue("exit 0 with no change must not be Applied", result is ChangeResult.NotApplied)
        assertEquals("644", (result as ChangeResult.NotApplied).still)
    }

    @Test
    fun `a non-zero exit code with unchanged mode is reported as failed`() {
        val requested = LinuxPermission.fromOctal("600")!!
        val observed = LinuxPermission.fromOctal("644")!!

        val result = changer().decideModeOutcome(
            requested,
            observed,
            commandExitCode = 1,
            commandOutput = "chmod: Read-only file system",
        )

        assertTrue(result is ChangeResult.Failed)
        assertTrue((result as ChangeResult.Failed).reason.contains("Read-only"))
    }

    @Test
    fun `a non-zero exit code with a blank message still explains the state`() {
        val result = changer().decideModeOutcome(
            LinuxPermission.fromOctal("600")!!,
            LinuxPermission.fromOctal("644")!!,
            commandExitCode = 1,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.Failed)
        assertTrue((result as ChangeResult.Failed).reason.contains("644"))
    }

    @Test
    fun `unreadable permissions yield Unverified rather than success`() {
        val result = changer().decideModeOutcome(
            LinuxPermission.fromOctal("600")!!,
            observed = null,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.Unverified)
    }

    @Test
    fun `special bits are compared as part of the mode`() {
        // 4755 vs 0755 differ only in setuid: that must not be treated as equal.
        val result = changer().decideModeOutcome(
            requested = LinuxPermission.fromOctal("4755")!!,
            observed = LinuxPermission.fromOctal("755")!!,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.NotApplied)
    }

    // ------------------------------------------------------------------ ownership decision

    @Test
    fun `a matching owner name is reported as applied`() {
        val result = changer().decideOwnershipOutcome(
            requestedOwner = "root",
            requestedGroup = "root",
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.Applied)
    }

    @Test
    fun `a numeric request matches the name the device reports`() {
        // Asking for uid 0 while the device reports "root" must count as success.
        val result = changer().decideOwnershipOutcome(
            requestedOwner = "0",
            requestedGroup = null,
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.Applied)
    }

    @Test
    fun `a name request matches a numeric report`() {
        // Needs root enabled: resolving "root" to uid 0 goes through the device's account
        // database, which is what makes a name request comparable to a numeric report.
        val result = changerWithRoot().decideOwnershipOutcome(
            requestedOwner = "root",
            requestedGroup = null,
            observedOwnerName = null,
            observedOwnerId = 0,
            observedGroupName = null,
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
            requestedOwnerId = 0,
        )
        assertTrue(result is ChangeResult.Applied)
    }

    @Test
    fun `an unresolvable name against a different numeric report is not success`() {
        // The honest outcome when a name cannot be resolved and the ids disagree.
        val result = changer().decideOwnershipOutcome(
            requestedOwner = "nosuchuser",
            requestedGroup = null,
            observedOwnerName = null,
            observedOwnerId = 0,
            observedGroupName = null,
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
            requestedOwnerId = null,
        )
        assertTrue(result is ChangeResult.NotApplied)
    }

    @Test
    fun `a group mismatch is not reported as applied`() {
        val result = changer().decideOwnershipOutcome(
            requestedOwner = "root",
            requestedGroup = "shell",
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.NotApplied)
    }

    @Test
    fun `a null group means only the owner is checked`() {
        val result = changer().decideOwnershipOutcome(
            requestedOwner = "root",
            requestedGroup = null,
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "something-else",
            observedGroupId = 3003,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.Applied)
    }

    @Test
    fun `an unchanged owner after exit 0 is not success`() {
        val result = changer().decideOwnershipOutcome(
            requestedOwner = "shell",
            requestedGroup = null,
            observedOwnerName = "root",
            observedOwnerId = 0,
            observedGroupName = "root",
            observedGroupId = 0,
            commandExitCode = 0,
            commandOutput = "",
        )
        assertTrue(result is ChangeResult.NotApplied)
    }

    // ------------------------------------------------------------------ validation

    @Test
    fun `a relative target path is rejected`() {
        assertNotNull(changer().validateTarget("data/adb"))
        assertNotNull(changer().validateTarget("./file"))
    }

    @Test
    fun `an empty target path is rejected`() {
        assertNotNull(changer().validateTarget(""))
        assertNotNull(changer().validateTarget("   "))
    }

    @Test
    fun `the root directory itself is rejected`() {
        assertNotNull(changer().validateTarget("/"))
        assertNotNull(changer().validateTarget("//"))
    }

    @Test
    fun `a null byte in the path is rejected`() {
        assertNotNull(changer().validateTarget("/data/a\u0000b"))
    }

    @Test
    fun `ordinary absolute paths are accepted`() {
        assertNull(changer().validateTarget("/data/adb/magisk.db"))
        assertNull(changer().validateTarget("/storage/emulated/0/my file.txt"))
        assertNull(changer().validateTarget("/data/我的文件"))
    }

    @Test
    fun `valid owner specifiers are accepted`() {
        val changer = changer()
        assertNull(changer.validateId("root"))
        assertNull(changer.validateId("shell"))
        assertNull(changer.validateId("_system"))
        assertNull(changer.validateId("media_rw"))
        assertNull(changer.validateId("0"))
        assertNull(changer.validateId("10234"))
    }

    @Test
    fun `invalid owner specifiers are rejected`() {
        val changer = changer()
        assertNotNull("empty", changer.validateId(""))
        assertNotNull("space", changer.validateId("ro ot"))
        assertNotNull("semicolon", changer.validateId("root;reboot"))
        assertNotNull("dollar", changer.validateId("\$(reboot)"))
        assertNotNull("slash", changer.validateId("a/b"))
        assertNotNull("leading digit name", changer.validateId("1abc"))
        assertNotNull("too long", changer.validateId("a".repeat(33)))
    }

    @Test
    fun `an out-of-range numeric id is rejected`() {
        assertNotNull(changer().validateId("100000"))
        assertNotNull(changer().validateId("-1"))
    }

    // ------------------------------------------------------------------ sensitive paths

    @Test
    fun `system paths are flagged as sensitive`() {
        val changer = changer()
        assertNotNull(changer.sensitiveWarning("/system"))
        assertNotNull(changer.sensitiveWarning("/system/app/Foo.apk"))
        assertNotNull(changer.sensitiveWarning("/vendor/lib64/libfoo.so"))
        assertNotNull(changer.sensitiveWarning("/data/adb"))
        assertNotNull(changer.sensitiveWarning("/data/adb/modules"))
        assertNotNull(changer.sensitiveWarning("/metadata"))
    }

    @Test
    fun `a similarly named path is not flagged`() {
        // "/systematic" must not be treated as "/system".
        assertNull(changer().sensitiveWarning("/systematic"))
        assertNull(changer().sensitiveWarning("/data/adbfoo"))
    }

    @Test
    fun `ordinary storage is not flagged`() {
        val changer = changer()
        assertNull(changer.sensitiveWarning("/storage/emulated/0/Download"))
        assertNull(changer.sensitiveWarning("/data/local/tmp"))
    }

    @Test
    fun `sensitive warnings explain the risk`() {
        val warning = changer().sensitiveWarning("/system")!!
        assertTrue(warning.reason.isNotBlank())
        assertEquals("/system", warning.path)
    }

    // ------------------------------------------------------------------ read-only partitions

    @Test
    fun `system partitions are known to be read-only`() {
        val changer = changer()
        assertTrue(changer.isTypicallyReadOnly("/system"))
        assertTrue(changer.isTypicallyReadOnly("/vendor/etc"))
        assertTrue(changer.isTypicallyReadOnly("/product"))
        assertTrue(changer.isTypicallyReadOnly("/metadata"))
    }

    @Test
    fun `user storage is not considered read-only`() {
        val changer = changer()
        assertTrue(!changer.isTypicallyReadOnly("/storage/emulated/0"))
        assertTrue(!changer.isTypicallyReadOnly("/data/local/tmp"))
        assertTrue(!changer.isTypicallyReadOnly("/data/adb"))
    }

    @Test
    fun `without root a change reports RootRequired rather than failing obscurely`() {
        val changer = changer()
        val entry = entryFor("/data/adb/x")

        val modeResult = kotlinx.coroutines.runBlocking {
            changer.changeMode(entry, LinuxPermission.fromOctal("600")!!, recursive = false) { path ->
                io.rootexplorer.pro.core.filesystem.StatResult.Failure(
                    io.rootexplorer.pro.core.filesystem.FsError.NotFound(path),
                )
            }
        }
        assertEquals(ChangeResult.RootRequired, modeResult)

        val ownerResult = kotlinx.coroutines.runBlocking {
            changer.changeOwnership(entry, "root", null, recursive = false) { path ->
                io.rootexplorer.pro.core.filesystem.StatResult.Failure(
                    io.rootexplorer.pro.core.filesystem.FsError.NotFound(path),
                )
            }
        }
        assertEquals(ChangeResult.RootRequired, ownerResult)
    }

    @Test
    fun `an invalid owner is rejected before any command runs`() {
        // rootAvailable is true, so the only thing stopping this is validation.
        val changer = changerWithRoot()
        val entry = entryFor("/data/local/tmp/x")

        val result = kotlinx.coroutines.runBlocking {
            changer.changeOwnership(entry, "root; reboot", null, recursive = false) { path ->
                io.rootexplorer.pro.core.filesystem.StatResult.Failure(
                    io.rootexplorer.pro.core.filesystem.FsError.NotFound(path),
                )
            }
        }
        assertTrue(result is ChangeResult.Failed)
        assertTrue((result as ChangeResult.Failed).reason.contains("所有者无效"))
    }

    @Test
    fun `an invalid target is rejected before any command runs`() {
        val changer = changerWithRoot()
        val entry = entryFor("relative/path")

        val result = kotlinx.coroutines.runBlocking {
            changer.changeMode(entry, LinuxPermission.fromOctal("600")!!, recursive = false) { path ->
                io.rootexplorer.pro.core.filesystem.StatResult.Failure(
                    io.rootexplorer.pro.core.filesystem.FsError.NotFound(path),
                )
            }
        }
        assertTrue("expected a validation failure, got $result", result is ChangeResult.Failed)
        assertTrue((result as ChangeResult.Failed).reason.contains("绝对路径"))
    }

    private fun entryFor(path: String) = io.rootexplorer.pro.core.filesystem.FileEntry(
        name = path.substringAfterLast('/'),
        path = path,
        kind = io.rootexplorer.pro.core.filesystem.FileKind.REGULAR,
        sizeBytes = 10,
        lastModifiedMillis = 0L,
        permission = LinuxPermission.fromOctal("644"),
        uid = 0,
        gid = 0,
        ownerName = "root",
        groupName = "root",
        linkTarget = null,
        linkTargetIsDirectory = null,
    )
}
