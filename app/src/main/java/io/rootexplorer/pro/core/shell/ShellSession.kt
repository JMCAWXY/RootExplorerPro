package io.rootexplorer.pro.core.shell

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Which shell a session runs. */
enum class ShellKind(val label: String) {
    /** `/system/bin/sh` as this app's own UID. */
    NORMAL("普通 Shell"),

    /**
     * The device's `su`, which shows the root manager's authorization prompt the first time.
     *
     * Never selected automatically: entering a root shell is a deliberate act, and the prompt is
     * the user's decision rather than something the app triggers on its own.
     */
    ROOT("Root Shell"),
}

/** A line of terminal output, tagged so the UI can style it. */
data class TerminalLine(val text: String, val kind: Kind) {
    enum class Kind {
        /** The echoed command, so the transcript reads like a real session. */
        COMMAND,

        /** Ordinary stdout. */
        OUTPUT,

        /** Something the terminal itself is reporting, e.g. a timeout. */
        NOTICE,
    }
}

/** The outcome of running one command. */
sealed interface CommandOutcome {
    data class Completed(val exitCode: Int?) : CommandOutcome

    /** The command outlived its timeout and the session was stopped. */
    data class TimedOut(val seconds: Int) : CommandOutcome

    data class Failed(val reason: String) : CommandOutcome
}

/**
 * A long-lived shell process.
 *
 * Why one process rather than one per command: `cd` and shell variables only mean anything inside
 * a session, and restarting `su` for every command would re-trigger the root prompt. The session
 * keeps stdin and stdout open and frames each result with a unique sentinel line that also
 * carries the exit status, which is the standard way to learn that a non-interactive command has
 * finished.
 *
 * Everything shown comes from the real process. Nothing is synthesised: an empty result stays
 * empty, and the exit status is the shell's own `$?`.
 *
 * A command cannot be interrupted in place through a pipe — there is no terminal to deliver
 * SIGINT to — so [stop] terminates the session instead. The next [run] starts a fresh one
 * automatically, which is honest about what happened and never leaves a runaway process behind.
 */
class ShellSession(
    val kind: ShellKind,
) {

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    private var ready = false
    private var runningCommand = false

    /** The directory the session last reported itself to be in. */
    var workingDirectory: String? = null
        private set

    val isBusy: Boolean get() = runningCommand
    val isAlive: Boolean get() = ready && process?.isAlive == true

    /**
     * Start the process.
     *
     * For a root session this is where the authorization prompt appears; a denial surfaces as a
     * failure with a readable reason rather than a dead session.
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        if (ready && process?.isAlive == true) return@withContext Result.success(Unit)

        val builder = ProcessBuilder(if (kind == ShellKind.ROOT) "su" else "/system/bin/sh")
            // Merge stderr into stdout so the transcript keeps the order the shell produced.
            .redirectErrorStream(true)

        try {
            val started = builder.start()
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
            reader = BufferedReader(InputStreamReader(started.inputStream, StandardCharsets.UTF_8))

            // A shell has no prompt protocol, so it is probed: send a marker and wait for its
            // sentinel. This proves the shell is reading stdin, and it is what stops a root
            // session's first real command from being swallowed while `su` is still starting.
            writeRaw("echo $READY_MARKER\n")
            when (val probe = readUntilSentinel(READY_MARKER, READY_TIMEOUT_MS, onLine = null)) {
                is ReadResult.Sentinel -> {
                    ready = true
                    Result.success(Unit)
                }

                is ReadResult.TimedOut -> {
                    close()
                    Result.failure(IOException("Shell 启动超时（${READY_TIMEOUT_MS / 1000} 秒无响应）"))
                }

                is ReadResult.Failed -> {
                    close()
                    Result.failure(IOException(probe.reason))
                }
            }
        } catch (e: IOException) {
            close()
            Result.failure(
                IOException(
                    if (kind == ShellKind.ROOT) {
                        "无法启动 Root Shell：${e.message ?: "su 不可用或授权被拒绝"}"
                    } else {
                        "无法启动 Shell：${e.message ?: e.javaClass.simpleName}"
                    },
                ),
            )
        }
    }

    /**
     * Run [command], streaming output through [onLine].
     *
     * The command runs in the session's own working directory, so `cd` persists between calls.
     * The framed command also prints `pwd`, which is how [workingDirectory] stays accurate without
     * a second round trip.
     */
    suspend fun run(
        command: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
        onLine: (TerminalLine) -> Unit,
    ): CommandOutcome = withContext(Dispatchers.IO) {
        if (runningCommand) return@withContext CommandOutcome.Failed("上一个命令仍在执行")

        if (!isAlive) {
            val started = start()
            if (started.isFailure) {
                return@withContext CommandOutcome.Failed(
                    started.exceptionOrNull()?.message ?: "会话无法启动",
                )
            }
        }

        runningCommand = true
        try {
            onLine(TerminalLine("$ ${command.trim()}", TerminalLine.Kind.COMMAND))

            // `__RC=$?` captures the status, and `pwd` refreshes the shown directory. Both are
            // emitted before the sentinel so one read yields output, status and location.
            writeRaw(frame(command))

            withTimeout(timeoutSeconds * 1000L + FRAME_GRACE_MS) {
                when (val result = readUntilSentinel(SENTINEL_PREFIX, timeoutSeconds * 1000L, onLine)) {
                    is ReadResult.Sentinel -> {
                        applyStatusPayload(result.payload)
                        CommandOutcome.Completed(result.exitCode)
                    }

                    is ReadResult.Failed -> CommandOutcome.Failed(result.reason)
                    is ReadResult.TimedOut -> {
                        // No SIGINT is possible through a pipe, so the session is stopped and the
                        // transcript says so rather than pretending the command ended cleanly.
                        onLine(
                            TerminalLine(
                                "命令超过 ${timeoutSeconds} 秒仍未返回，已终止该会话。下次执行会自动新建会话。",
                                TerminalLine.Kind.NOTICE,
                            ),
                        )
                        close()
                        CommandOutcome.TimedOut(timeoutSeconds)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            onLine(
                TerminalLine(
                    "命令超过 ${timeoutSeconds} 秒仍未返回，已终止该会话。",
                    TerminalLine.Kind.NOTICE,
                ),
            )
            close()
            CommandOutcome.TimedOut(timeoutSeconds)
        } catch (e: IOException) {
            CommandOutcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runningCommand = false
        }
    }

    /**
     * Stop the session.
     *
     * This is the only reliable way to end a running command: a pipe gives the child no terminal,
     * so there is nothing to deliver an interrupt to. The process tree is destroyed so a root
     * shell cannot survive the terminal.
     */
    fun stop() {
        if (runningCommand) {
            // The reader is blocked on readLine(); closing the streams unblocks it.
            runCatching { reader?.close() }
        }
        close()
    }

    /** Terminate the process and drop every resource it held. */
    fun close() {
        ready = false
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        process?.let { running ->
            runCatching { running.destroy() }
            runCatching {
                if (!running.waitFor(1, TimeUnit.SECONDS)) running.destroyForcibly()
            }
        }
        writer = null
        reader = null
        process = null
        runningCommand = false
    }

    // ------------------------------------------------------------------ internals

    private sealed interface ReadResult {
        data class Sentinel(val exitCode: Int?, val payload: String) : ReadResult
        data object TimedOut : ReadResult
        data class Failed(val reason: String) : ReadResult
    }

    private fun writeRaw(text: String) {
        val target = writer ?: throw IOException("会话已关闭")
        target.write(text)
        target.flush()
    }

    /**
     * Read lines until the sentinel appears.
     *
     * The sentinel line carries the exit status and `pwd`, so output, status and location all come
     * from one read. A cap on lines and characters stops a runaway command (a `cat` of a huge file,
     * say) from consuming unbounded memory.
     */
    private suspend fun readUntilSentinel(
        marker: String,
        timeoutMillis: Long,
        onLine: ((TerminalLine) -> Unit)?,
    ): ReadResult {
        val source = reader ?: return ReadResult.Failed("会话已关闭")
        var lines = 0
        var characters = 0

        return try {
            withTimeout(timeoutMillis) {
                var outcome: ReadResult = ReadResult.Failed("读取结束")
                while (true) {
                    val line = source.readLine()
                    if (line == null) {
                        outcome = ReadResult.Failed("Shell 已退出")
                        break
                    }

                    // The status line is `__DSH_STATUS=<rc>|<pwd>`; pwd may be empty.
                    if (line.contains(marker)) {
                        val payload = line.substringAfter(marker).trim()
                        val exitCode = payload.substringBefore('|').trim().toIntOrNull()
                        outcome = ReadResult.Sentinel(exitCode, payload)
                        break
                    }

                    lines++
                    characters += line.length
                    if (lines > MAX_OUTPUT_LINES || characters > MAX_OUTPUT_CHARS) {
                        outcome = ReadResult.Failed(
                            "输出过多（超过 $MAX_OUTPUT_LINES 行），已停止读取。请缩小命令范围。",
                        )
                        break
                    }
                    onLine?.invoke(TerminalLine(line, TerminalLine.Kind.OUTPUT))
                }
                outcome
            }
        } catch (e: TimeoutCancellationException) {
            ReadResult.TimedOut
        } catch (e: IOException) {
            ReadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Take the `pwd` half of the status payload, ignoring an empty or stale value. */
    private fun applyStatusPayload(payload: String) {
        val directory = parseStatusPayload(payload).directory
        if (directory != null) workingDirectory = directory
    }

    companion object {
        /**
         * Prefix of the status line the framed command prints.
         *
         * Deliberately unlikely to occur in real output, so a command that echoes arbitrary text
         * cannot be mistaken for the end of the frame.
         */
        const val SENTINEL_PREFIX = "__DSH_STATUS="

        private const val READY_MARKER = "__DSH_READY__"
        private const val READY_TIMEOUT_MS = 30_000L

        /** Long enough for a real command, short enough that a hang is noticed. */
        const val DEFAULT_TIMEOUT_SECONDS = 60

        /** Slack on top of the command timeout so the read cannot outlive the frame. */
        private const val FRAME_GRACE_MS = 2_000L

        /** Bounds one command's output so the transcript cannot exhaust memory. */
        const val MAX_OUTPUT_LINES = 5_000
        const val MAX_OUTPUT_CHARS = 2_000_000

        val suggestions: List<String> = listOf(
            "pwd",
            "ls -la",
            "id",
            "df -h",
            "getprop ro.product.model",
        )

        /** The exit status and directory carried by a status line. */
        data class StatusPayload(val exitCode: Int?, val directory: String?)

        /**
         * Parse what follows [SENTINEL_PREFIX] on the status line.
         *
         * The shell prints `<exit code>|<pwd>`. A missing or unparsable exit code stays null, and
         * a directory that is not absolute (or is empty, which happens if `pwd` failed) is dropped
         * rather than shown as if it were the session's location.
         *
         * Pure and static so the framing rules can be tested without spawning a shell.
         */
        fun parseStatusPayload(payload: String): StatusPayload {
            val trimmed = payload.trim()
            val codePart = trimmed.substringBefore('|').trim()
            val directoryPart = trimmed.substringAfter('|', "").trim()
            return StatusPayload(
                exitCode = codePart.toIntOrNull(),
                directory = directoryPart.takeIf { it.startsWith("/") },
            )
        }

        /**
         * Frame [command] so its output can be recognised and its status recovered.
         *
         * The marker is emitted by `printf` with the halves passed as arguments, and the literal
         * text `__DSH_STATUS` appears in the frame **exactly once** — inside the marker itself.
         *
         * That matters: an earlier version read the exit status into a shell variable also named
         * `__DSH_STATUS`, so the word occurred twice in the command text. Since the marker is a
         * Kotlin `const val`, it is inlined, which made the frame ambiguous and cost two test
         * failures. Avoiding the name collision altogether is cleaner than relying on the shell to
         * keep the two apart.
         *
         * Pure and static so the escaping — exactly where a mistake is silent — is asserted.
         */
        fun frame(command: String): String = buildString {
            append(command)
            append('\n')
            append("printf '$SENTINEL_PREFIX%s|%s\\n' \"\$?\" \"\$(pwd 2>/dev/null)\"\n")
        }
    }
}
