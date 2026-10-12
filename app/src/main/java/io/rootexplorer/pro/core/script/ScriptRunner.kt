package io.rootexplorer.pro.core.script

import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.ShellQuote
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which environment an executed script runs in. */
enum class ScriptEnvironment(val label: String, val description: String) {
    SYSTEM(
        label = "使用系统环境执行",
        description = "沿用系统的 PATH 与环境变量，效果等同于在终端里直接执行。",
    ),
    PACKAGED(
        label = "使用扩展包环境执行",
        description = "把应用自带的库目录放进 PATH 与 LD_LIBRARY_PATH，脚本可以调用随应用打包的组件。",
    ),
}

/** What running a script produced. */
sealed interface ScriptOutcome {

    /** The script ran. [exitCode] is the shell's own status; null means it could not be read. */
    data class Finished(
        val exitCode: Int?,
        val output: String,
        val usedRoot: Boolean,
        /** True when the script printed more than the app will hold. */
        val truncated: Boolean,
    ) : ScriptOutcome

    data class Failed(val reason: String) : ScriptOutcome
}

/**
 * Runs a shell script the user explicitly asked to run.
 *
 * Why `sh <path>` rather than executing the file itself: a script that was downloaded or copied in
 * usually has no executable bit, and depending on one would make 执行 fail for a reason the user
 * cannot see. Naming the interpreter also means a missing shebang is not a problem.
 *
 * Output is capped. A script is arbitrary code and `yes` is a perfectly legal thing to write;
 * without a cap the app would try to hold an unbounded string and die holding it.
 */
class ScriptRunner(
    private val rootShell: RootShell,
    /** Where the app's own native libraries live, used by [ScriptEnvironment.PACKAGED]. */
    private val nativeLibraryDir: String = "",
    /** Where the app keeps extra executables, used by [ScriptEnvironment.PACKAGED]. */
    private val filesBinDir: String = "",
) {

    /** Run [scriptPath]. Never throws: every failure comes back as [ScriptOutcome.Failed]. */
    suspend fun run(
        scriptPath: String,
        environment: ScriptEnvironment,
        useRoot: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): ScriptOutcome = withContext(Dispatchers.IO) {
        if (!File(scriptPath).isFile) {
            return@withContext ScriptOutcome.Failed("脚本不存在：$scriptPath")
        }
        if (useRoot) runAsRoot(scriptPath, environment, timeoutMs)
        else runDirect(scriptPath, environment, timeoutMs)
    }

    private fun runAsRoot(
        scriptPath: String,
        environment: ScriptEnvironment,
        timeoutMs: Long,
    ): ScriptOutcome {
        val command = buildScriptCommand(scriptPath, environment, nativeLibraryDir, filesBinDir)
        val result = rootShell.runBlocking(command, timeoutMs)

        if (result.launchError != null) {
            return ScriptOutcome.Failed("无法执行 su：${result.launchError}")
        }
        val output = result.combinedOutput
        if (result.timedOut) {
            return ScriptOutcome.Failed(
                "脚本超过 ${timeoutMs / 1000} 秒仍未结束，已中止。\n" + output.takeLast(MAX_OUTPUT_CHARS),
            )
        }
        return ScriptOutcome.Finished(
            exitCode = result.exitCode,
            output = output.take(MAX_OUTPUT_CHARS),
            usedRoot = true,
            truncated = output.length > MAX_OUTPUT_CHARS,
        )
    }

    private fun runDirect(
        scriptPath: String,
        environment: ScriptEnvironment,
        timeoutMs: Long,
    ): ScriptOutcome {
        val builder = ProcessBuilder("/system/bin/sh", scriptPath).redirectErrorStream(true)

        if (environment == ScriptEnvironment.PACKAGED) {
            val extra = listOf(nativeLibraryDir, filesBinDir).filter { it.isNotBlank() }
            if (extra.isNotEmpty()) {
                val env = builder.environment()
                env["PATH"] = (extra + (env["PATH"] ?: "")).filter { it.isNotEmpty() }.joinToString(":")
                env["LD_LIBRARY_PATH"] =
                    (extra + (env["LD_LIBRARY_PATH"] ?: "")).filter { it.isNotEmpty() }.joinToString(":")
            }
        }

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ScriptOutcome.Failed("无法启动 sh：${e.message ?: e.javaClass.simpleName}")
        }

        val sink = ByteArrayOutputStream()
        val truncated = booleanArrayOf(false)

        // The read runs on its own thread so a script that prints nothing and never exits still
        // hits the timeout below instead of blocking this coroutine on read() forever.
        val reader = thread(isDaemon = true, name = "script-output") {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val room = MAX_OUTPUT_CHARS - sink.size()
                        if (room <= 0) {
                            truncated[0] = true
                            break
                        }
                        sink.write(buffer, 0, minOf(read, room))
                        if (read > room) truncated[0] = true
                    }
                }
            } catch (e: IOException) {
                // A closed stream only means the process ended; the exit code still tells the story.
            }
        }

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(2_000)
            return ScriptOutcome.Failed(
                "脚本超过 ${timeoutMs / 1000} 秒仍未结束，已中止。\n" +
                    sink.toString(Charsets.UTF_8.name()).takeLast(MAX_OUTPUT_CHARS),
            )
        }
        reader.join(2_000)

        val exit = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            null
        }
        return ScriptOutcome.Finished(
            exitCode = exit,
            output = sink.toString(Charsets.UTF_8.name()),
            usedRoot = false,
            truncated = truncated[0],
        )
    }

    private companion object {
        /** Long enough for real work, short enough that a hang is noticed. */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** Characters of output the app will hold. */
        const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}

/**
 * The command line for running [scriptPath] in [environment].
 *
 * Public and pure so the two places a mistake is silent — the shell quoting and the environment
 * prefix — can be asserted in a test. Every value that reaches the shell goes through [ShellQuote].
 */
fun buildScriptCommand(
    scriptPath: String,
    environment: ScriptEnvironment,
    nativeLibraryDir: String,
    filesBinDir: String,
): String {
    val prefix = when (environment) {
        ScriptEnvironment.SYSTEM -> ""
        ScriptEnvironment.PACKAGED -> {
            val dirs = listOf(nativeLibraryDir, filesBinDir).filter { it.isNotBlank() }
            if (dirs.isEmpty()) {
                // Nothing was configured to add, so say nothing rather than pretend: the script
                // then simply runs in the system environment.
                ""
            } else {
                val path = dirs.joinToString(":") { ShellQuote.quote(it) }
                "PATH=$path:\$PATH LD_LIBRARY_PATH=$path "
            }
        }
    }
    return "${prefix}sh ${ShellQuote.quote(scriptPath)} 2>&1"
}
