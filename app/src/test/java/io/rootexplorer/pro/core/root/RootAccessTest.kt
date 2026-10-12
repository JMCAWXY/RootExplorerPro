package io.rootexplorer.pro.core.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The root probe and its memory.
 *
 * The command runner is injected, so these tests exercise the decision rules — when a probe is
 * allowed to run at all, and what is remembered afterwards — without spawning `su`.
 *
 * The rule worth protecting here is "never cause the authorization prompt": a launch on a device
 * that has never granted root must execute nothing at all, and a remembered grant that no longer
 * works must be forgotten so the next launch does not ask again.
 */
class RootAccessTest {

    /** Records every command it is asked to run, and answers with a canned result. */
    private class FakeRunner(private var result: ShellResult = granted()) {

        val commands = mutableListOf<String>()

        fun answering(newResult: ShellResult) {
            result = newResult
        }

        fun asRunner(): (String, Long) -> ShellResult = { command, _ ->
            commands += command
            result
        }

        companion object {
            fun granted() = ShellResult(
                exitCode = 0,
                stdout = "uid=0(root) gid=0(root)\nROOT_SU_UID=0\nROOT_SU_VER=KernelSU\n",
                stderr = "",
                timedOut = false,
            )

            /** `su` ran, but returned a non-root uid — the shape of a refusal. */
            fun denied() = ShellResult(
                exitCode = 1,
                stdout = "uid=2000(shell)\nROOT_SU_UID=2000\n",
                stderr = "su: denied\n",
                timedOut = false,
            )
        }
    }

    @Test
    fun `a successful probe is granted and remembered`() = runBlocking {
        val runner = FakeRunner()
        val remembered = mutableListOf<Boolean>()
        val access = RootAccess(
            runCommand = runner.asRunner(),
            onGrantRecorded = { remembered += it },
        )

        val status = access.probe()

        assertTrue("expected Granted, got $status", status is RootStatus.Granted)
        assertEquals(1, runner.commands.size)
        assertEquals(listOf(true), remembered)
    }

    @Test
    fun `a refused probe is not remembered as a grant`() = runBlocking {
        val runner = FakeRunner(FakeRunner.denied())
        val remembered = mutableListOf<Boolean>()
        val access = RootAccess(
            runCommand = runner.asRunner(),
            onGrantRecorded = { remembered += it },
        )

        val status = access.probe()

        assertTrue("expected a refusal, got $status", status !is RootStatus.Granted)
        assertEquals(listOf(false), remembered)
    }

    @Test
    fun `nothing is executed when root was never granted`() = runBlocking {
        val runner = FakeRunner()
        val access = RootAccess(runCommand = runner.asRunner())

        val restored = access.restoreIfPreviouslyGranted()

        assertNull("nothing to restore, so the caller must not be told root is back", restored)
        assertTrue(
            "no command may run: this is exactly what would make the root manager prompt",
            runner.commands.isEmpty(),
        )
    }

    @Test
    fun `a remembered grant is re-established without a user action`() = runBlocking {
        val runner = FakeRunner()
        val access = RootAccess(
            runCommand = runner.asRunner(),
            wasGrantedBefore = { true },
        )

        val restored = access.restoreIfPreviouslyGranted()

        assertTrue("expected Granted, got $restored", restored is RootStatus.Granted)
        assertEquals(1, runner.commands.size)
    }

    @Test
    fun `a remembered grant that no longer works is forgotten`() = runBlocking {
        val runner = FakeRunner(FakeRunner.denied())
        val remembered = mutableListOf<Boolean>()
        val access = RootAccess(
            runCommand = runner.asRunner(),
            wasGrantedBefore = { true },
            onGrantRecorded = { remembered += it },
        )

        val restored = access.restoreIfPreviouslyGranted()

        assertTrue("expected a refusal, got $restored", restored !is RootStatus.Granted)
        // Otherwise the next launch would probe again, and probing a revoked grant is what makes
        // the root manager ask the user a second time — the complaint this feature fixes.
        assertEquals(listOf(false), remembered)
    }

    @Test
    fun `a granted status is not probed again`() = runBlocking {
        val runner = FakeRunner()
        val access = RootAccess(
            runCommand = runner.asRunner(),
            wasGrantedBefore = { true },
        )

        access.restoreIfPreviouslyGranted()
        access.probe()
        access.probe()

        assertEquals("only the first probe may run `su`", 1, runner.commands.size)
    }
}
