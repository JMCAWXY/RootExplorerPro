package io.rootexplorer.pro.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command line a script is run with.
 *
 * This is the one place in the scripting feature where a mistake is silent: a missing quote turns a
 * file name into extra shell words, and an environment prefix that never reaches the shell is
 * quietly ignored. Both are asserted here rather than on a device.
 */
class ScriptRunnerTest {

    @Test
    fun `the system environment adds no prefix`() {
        assertEquals(
            "sh /data/local/tmp/a.sh 2>&1",
            buildScriptCommand("/data/local/tmp/a.sh", ScriptEnvironment.SYSTEM, "/lib", "/bin"),
        )
    }

    @Test
    fun `the packaged environment puts the app directories first on both search paths`() {
        assertEquals(
            "PATH=/data/app/x/lib/arm64:/files/bin:\$PATH " +
                "LD_LIBRARY_PATH=/data/app/x/lib/arm64:/files/bin sh /data/local/tmp/a.sh 2>&1",
            buildScriptCommand(
                scriptPath = "/data/local/tmp/a.sh",
                environment = ScriptEnvironment.PACKAGED,
                nativeLibraryDir = "/data/app/x/lib/arm64",
                filesBinDir = "/files/bin",
            ),
        )
    }

    @Test
    fun `the packaged environment adds nothing when there is nothing to add`() {
        // Better to run in the plain system environment than to emit a prefix pointing nowhere.
        assertEquals(
            "sh /data/local/tmp/a.sh 2>&1",
            buildScriptCommand("/data/local/tmp/a.sh", ScriptEnvironment.PACKAGED, "", ""),
        )
    }

    @Test
    fun `a hostile path stays one argument`() {
        // A space makes the path unsafe to leave bare, and the embedded quote must be closed and
        // reopened so the semicolon cannot start a new command.
        assertEquals(
            "sh '/data/my dir/it'\\''s; rm -rf /' 2>&1",
            buildScriptCommand("/data/my dir/it's; rm -rf /", ScriptEnvironment.SYSTEM, "", ""),
        )
    }

    @Test
    fun `the interpreter is named explicitly, so the executable bit is irrelevant`() {
        assertTrue(
            buildScriptCommand("/x/a.sh", ScriptEnvironment.SYSTEM, "", "").startsWith("sh "),
        )
    }
}
