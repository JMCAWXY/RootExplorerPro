package io.rootexplorer.pro.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.shell.CommandOutcome
import io.rootexplorer.pro.core.shell.ShellKind
import io.rootexplorer.pro.core.shell.ShellSession
import io.rootexplorer.pro.core.shell.TerminalLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the terminal screen. */
data class TerminalUiState(
    val kind: ShellKind = ShellKind.NORMAL,
    val transcript: List<TerminalLine> = emptyList(),
    val input: String = "",
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    /** Null until the session reports a directory. */
    val workingDirectory: String? = null,
    val error: String? = null,
    /** Commands this session actually ran successfully, offered for reuse. */
    val history: List<String> = emptyList(),
)

/**
 * Terminal state.
 *
 * The transcript is bounded: a shell can produce unlimited output and an unbounded list would
 * eventually exhaust memory. Older lines are dropped from the front and the fact is visible,
 * rather than the screen silently losing the beginning of a long session.
 *
 * Nothing is fabricated. A command that prints nothing adds nothing, and the exit status shown is
 * the shell's own.
 */
class TerminalViewModel : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var session: ShellSession? = null
    private var job: Job? = null

    /** Open a session of the given kind, replacing any existing one. */
    fun selectShell(kind: ShellKind) {
        if (kind == _state.value.kind && session?.isAlive == true) return

        job?.cancel()
        session?.close()
        session = null

        _state.update {
            it.copy(
                kind = kind,
                isRunning = false,
                isStarting = true,
                error = null,
                workingDirectory = null,
            )
        }

        job = viewModelScope.launch {
            val created = ShellSession(kind)
            session = created
            val result = created.start()

            if (result.isFailure) {
                _state.update {
                    it.copy(
                        isStarting = false,
                        error = result.exceptionOrNull()?.message ?: "会话启动失败",
                    )
                }
                appendNotice(
                    if (kind == ShellKind.ROOT) {
                        "无法进入 Root Shell。请在 Root 管理器中允许本应用，或改用普通 Shell。"
                    } else {
                        "无法启动 Shell。"
                    },
                )
                return@launch
            }

            _state.update { it.copy(isStarting = false, error = null) }
            appendNotice(
                when (kind) {
                    ShellKind.NORMAL -> "已进入普通 Shell（以应用自身权限运行，uid 非 0）。"
                    ShellKind.ROOT -> "已进入 Root Shell（命令以 root 身份运行）。"
                },
            )
            refreshDirectory()
        }
    }

    fun setInput(text: String) {
        _state.update { it.copy(input = text) }
    }

    /** Run the current input, or [command] when given. */
    fun run(command: String = _state.value.input) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.isRunning) {
            appendNotice("上一个命令仍在执行，请先停止。")
            return
        }

        _state.update {
            it.copy(
                isRunning = true,
                input = "",
                history = (listOf(trimmed) + it.history).distinct().take(MAX_HISTORY),
            )
        }

        job = viewModelScope.launch {
            val active = session
            if (active == null || !active.isAlive) {
                // A stopped or timed-out session is replaced transparently, and the transcript
                // says so, so the user is never left wondering why the new process has no state.
                val fresh = ShellSession(_state.value.kind)
                session = fresh
                val started = fresh.start()
                if (started.isFailure) {
                    _state.update { it.copy(isRunning = false) }
                    appendNotice("无法重新启动会话：${started.exceptionOrNull()?.message}")
                    return@launch
                }
                appendNotice("会话已重新建立（上一个会话已结束）。")
            }

            val target = session ?: return@launch
            val outcome = target.run(trimmed) { line -> append(line) }

            when (outcome) {
                is CommandOutcome.Completed -> {
                    // A non-zero status is reported: silence would make a failed command look
                    // like a successful one with no output.
                    outcome.exitCode?.let { code ->
                        if (code != 0) {
                            _state.update { it.copy(error = "命令退出码 $code") }
                        } else {
                            _state.update { it.copy(error = null) }
                        }
                    }
                }

                is CommandOutcome.TimedOut -> _state.update {
                    it.copy(error = "命令超时（${outcome.seconds} 秒），会话已终止")
                }

                is CommandOutcome.Failed -> _state.update { it.copy(error = outcome.reason) }
            }

            refreshDirectory()
            _state.update { it.copy(isRunning = false) }
        }
    }

    /** Stop the running command's session. */
    fun stop() {
        session?.stop()
        session = null
        _state.update { it.copy(isRunning = false) }
        appendNotice("已终止会话。下次执行命令会自动新建会话。")
    }

    fun clear() {
        _state.update { it.copy(transcript = emptyList(), error = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ internals

    private fun append(line: TerminalLine) {
        _state.update { current ->
            val next = current.transcript + line
            current.copy(
                transcript = if (next.size > MAX_TRANSCRIPT_LINES) {
                    next.takeLast(MAX_TRANSCRIPT_LINES)
                } else {
                    next
                },
            )
        }
    }

    private fun appendNotice(text: String) {
        append(TerminalLine(text, TerminalLine.Kind.NOTICE))
    }

    /** Report where the session is, read from the shell rather than guessed. */
    private suspend fun refreshDirectory() {
        val directory = session?.workingDirectory
        if (directory != null) {
            _state.update { it.copy(workingDirectory = directory) }
        }
    }

    override fun onCleared() {
        // Leaving the screen must not leave a shell — especially a root one — running.
        job?.cancel()
        session?.close()
        session = null
    }

    private companion object {
        /** Transcript lines kept in memory; older ones are dropped. */
        const val MAX_TRANSCRIPT_LINES = 2_000

        const val MAX_HISTORY = 50
    }
}
