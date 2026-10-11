package io.rootexplorer.pro.core.root

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Quotes a single argument so the shell passes it through unchanged.
 *
 * Why this matters: file names on Android can contain spaces, quotes, `$`, backticks,
 * newlines and semicolons. Building a command by plain string concatenation lets such a
 * name inject arbitrary extra commands into a **root** shell. Every path and every
 * argument that reaches [RootShell] therefore goes through this function.
 *
 * The implementation uses POSIX single quotes, which suppress all interpretation, and
 * represents an embedded single quote as `'\''` (close quote, escaped quote, reopen
 * quote). This is understood by the mksh/bash/toybox shells Android ships.
 */
object ShellQuote {

    /** Characters that are safe to leave unquoted; quoting everything else is also fine. */
    private val SAFE = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    /** Quote [value] for safe use as one shell word. */
    fun quote(value: String): String {
        if (value.isEmpty()) return "''"
        if (SAFE.matches(value)) return value
        val sb = StringBuilder(value.length + 2)
        sb.append('\'')
        for (ch in value) {
            if (ch == '\'') sb.append("'\\''") else sb.append(ch)
        }
        sb.append('\'')
        return sb.toString()
    }

    /** Quote and join several arguments into one command fragment. */
    fun join(vararg values: String): String = values.joinToString(" ") { quote(it) }

    /** Quote and join a list of arguments. */
    fun join(values: List<String>): String = values.joinToString(" ") { quote(it) }
}

/** The captured result of running one root command. */
data class ShellResult(
    /** Exit status of the command, or null when it never ran to completion. */
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** True when the command was killed because it exceeded its timeout. */
    val timedOut: Boolean,
    /** Set when the process could not be started or read, e.g. `su` is absent. */
    val launchError: String? = null,
) {
    val isSuccess: Boolean get() = exitCode == 0 && launchError == null && !timedOut

    /** stdout and stderr together, for diagnostics shown to the user. */
    val combinedOutput: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n").trim()
}

/**
 * The single gateway for privileged command execution.
 *
 * Every root action in the app goes through here. The class is deliberately narrow: it
 * can only run a command, and it always reports stdout, stderr, exit code and timeout
 * separately so that callers cannot mistake "the process started" for "the command
 * succeeded".
 *
 * Each invocation is a fresh `su -c <command>` process. The command is passed as a
 * *single argument* to `su`, never as a pre-split list, so nothing re-parses it on the
 * way in.
 */
class RootShell(
    /** How long a single command may run before it is killed. */
    private val defaultTimeoutMs: Long = 20_000L,
) {

    private val readerPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "root-shell-reader").apply { isDaemon = true }
    }

    /**
     * Run [command] with root privileges and wait for it to finish.
     *
     * [command] must already be a complete shell command; use [ShellQuote] to build it from
     * untrusted values. Returns a [ShellResult] instead of throwing, because "su is
     * missing" and "the user denied the prompt" are normal states the UI must display, not
     * programming errors.
     */
    fun runBlocking(command: String, timeoutMs: Long = defaultTimeoutMs): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            // No `su` binary, or it could not be executed at all.
            return ShellResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                timedOut = false,
                launchError = e.message ?: e.javaClass.simpleName,
            )
        }

        val stdoutFuture = readAsync(process.inputStream)
        val stderrFuture = readAsync(process.errorStream)
        // Closing stdin makes `su`'s child shell see EOF instead of waiting for input.
        runCatching { process.outputStream.close() }

        val exited = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!exited) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            return ShellResult(
                exitCode = null,
                stdout = stdoutFuture.awaitOrEmpty(),
                stderr = stderrFuture.awaitOrEmpty(),
                timedOut = true,
            )
        }

        val exit = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            null
        }

        return ShellResult(
            exitCode = exit,
            stdout = stdoutFuture.awaitOrEmpty(),
            stderr = stderrFuture.awaitOrEmpty(),
            timedOut = false,
        )
    }

    /**
     * Run a command whose exit status is meaningful to the caller.
     *
     * This exists so callers do not treat "process started" as success: the result carries
     * the real exit code and output.
     */
    fun runOrThrow(command: String, timeoutMs: Long = defaultTimeoutMs): ShellResult {
        val result = runBlocking(command, timeoutMs)
        if (result.launchError != null) {
            throw IOException("无法执行 su：${result.launchError}")
        }
        if (result.timedOut) {
            throw TimeoutException("命令执行超时：$command")
        }
        return result
    }

    private fun readAsync(stream: InputStream): Future<String> =
        readerPool.submit(
            Callable {
                stream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read < 0) break
                        buffer.write(chunk, 0, read)
                    }
                    buffer.toString(StandardCharsets.UTF_8.name())
                }
            },
        )

    private fun Future<String>.awaitOrEmpty(): String = try {
        get(5, TimeUnit.SECONDS)
    } catch (e: Exception) {
        ""
    }
}
