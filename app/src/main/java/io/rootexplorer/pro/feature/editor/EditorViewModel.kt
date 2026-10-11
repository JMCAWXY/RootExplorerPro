package io.rootexplorer.pro.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.text.LineEnding
import io.rootexplorer.pro.core.text.ReplaceResult
import io.rootexplorer.pro.core.text.SaveOutcome
import io.rootexplorer.pro.core.text.TextDocument
import io.rootexplorer.pro.core.text.TextFileEngine
import io.rootexplorer.pro.core.text.TextLoadResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the editor is showing. */
sealed interface EditorContent {
    data object Loading : EditorContent
    data class Ready(val document: TextDocument) : EditorContent

    /** The file cannot be edited as text; the screen explains why and offers nothing else. */
    data class Blocked(val headline: String, val detail: String) : EditorContent

    data class Failed(val reason: String) : EditorContent
}

/** State of the editor screen. */
data class EditorUiState(
    val path: String = "",
    val content: EditorContent = EditorContent.Loading,
    val text: String = "",
    /** True when the buffer differs from what is on disk. */
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val findQuery: String = "",
    val replaceQuery: String = "",
    val caseSensitive: Boolean = false,
    val matchCount: Int = 0,
    /** Result of the last save, shown as a one-off message. */
    val lastSaveMessage: String? = null,
    val errorMessage: String? = null,
) {
    val document: TextDocument? get() = (content as? EditorContent.Ready)?.document
    val encoding: String get() = document?.encoding ?: "—"
    val lineEnding: LineEnding get() = document?.lineEnding ?: LineEnding.LF

    /** Line count of the current buffer, not of the file on disk. */
    val lineCount: Int
        get() = if (text.isEmpty()) 1 else Regex("\n").findAll(text).count() + 1

    val canEdit: Boolean get() = content is EditorContent.Ready
}

/**
 * Editor state and file operations.
 *
 * Two things here are deliberate rather than incidental:
 *
 * * **The buffer is separate from the document.** [text] is what the user is typing and
 *   [isDirty] tracks whether it differs from disk, so the screen can warn before discarding an
 *   edit instead of losing it silently on back.
 * * **Only a successful, verified save clears the dirty flag.** [TextFileEngine.save] replaces
 *   the file through a temporary and reports failure rather than throwing, so a failed save
 *   leaves the buffer marked as unsaved — which is the truthful state.
 */
class EditorViewModel(
    private val engine: TextFileEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Open [path]. */
    fun open(path: String) {
        loadJob?.cancel()
        _state.value = EditorUiState(path = path, content = EditorContent.Loading)

        loadJob = viewModelScope.launch {
            when (val result = engine.load(path)) {
                is TextLoadResult.Success -> _state.update {
                    it.copy(
                        content = EditorContent.Ready(result.document),
                        text = result.document.text,
                        isDirty = false,
                        matchCount = 0,
                    )
                }

                is TextLoadResult.Binary -> _state.update {
                    it.copy(
                        content = EditorContent.Blocked(
                            headline = "这是一个二进制文件",
                            detail = result.reason +
                                " 以文本方式打开并保存会破坏它，因此编辑器不会打开它。",
                        ),
                    )
                }

                is TextLoadResult.TooLarge -> _state.update {
                    it.copy(
                        content = EditorContent.Blocked(
                            headline = "文件过大，编辑器未打开",
                            detail = "文件大小 ${human(result.sizeBytes)}，编辑器上限为 " +
                                "${human(result.limitBytes)}。请使用其他工具处理，或先截取需要编辑的部分。",
                        ),
                    )
                }

                is TextLoadResult.NotText -> _state.update {
                    it.copy(content = EditorContent.Blocked(headline = "无法解码为文本", detail = result.reason))
                }

                is TextLoadResult.Failure -> _state.update {
                    it.copy(content = EditorContent.Failed(result.reason))
                }
            }
        }
    }

    /** Replace the buffer with user input. */
    fun onTextChange(newText: String) {
        _state.update { current ->
            current.copy(
                text = newText,
                isDirty = current.document?.text != newText,
                matchCount = if (current.findQuery.isEmpty()) {
                    0
                } else {
                    engine.countMatches(newText, current.findQuery, current.caseSensitive)
                },
            )
        }
    }

    fun setFindQuery(query: String) {
        _state.update { current ->
            current.copy(
                findQuery = query,
                matchCount = engine.countMatches(current.text, query, current.caseSensitive),
            )
        }
    }

    fun setReplaceQuery(query: String) {
        _state.update { it.copy(replaceQuery = query) }
    }

    fun setCaseSensitive(caseSensitive: Boolean) {
        _state.update { current ->
            current.copy(
                caseSensitive = caseSensitive,
                matchCount = engine.countMatches(current.text, current.findQuery, caseSensitive),
            )
        }
    }

    /** Replace every match, reporting how many were replaced. */
    fun replaceAll() {
        val current = _state.value
        if (current.findQuery.isEmpty()) {
            _state.update { it.copy(errorMessage = "请先输入要查找的内容") }
            return
        }

        val result: ReplaceResult = engine.replaceAll(
            text = current.text,
            query = current.findQuery,
            replacement = current.replaceQuery,
            caseSensitive = current.caseSensitive,
        )

        _state.update {
            it.copy(
                text = result.text,
                isDirty = it.document?.text != result.text,
                matchCount = if (result.count == 0) {
                    0
                } else {
                    engine.countMatches(result.text, it.findQuery, it.caseSensitive)
                },
                lastSaveMessage = if (result.count == 0) {
                    "没有找到匹配内容"
                } else {
                    "已替换 ${result.count} 处"
                },
            )
        }
    }

    /** Revert the buffer to the version last read from disk. */
    fun revert() {
        val document = _state.value.document ?: return
        _state.update {
            it.copy(
                text = document.text,
                isDirty = false,
                matchCount = engine.countMatches(document.text, it.findQuery, it.caseSensitive),
                lastSaveMessage = "已还原为磁盘上的内容",
            )
        }
    }

    /**
     * Write the buffer to disk.
     *
     * [createBackup] defaults to true: editing a system or config file and losing it is the
     * worst outcome this screen has, and a `.bak` next to the original costs almost nothing.
     */
    fun save(createBackup: Boolean = true) {
        val current = _state.value
        val document = current.document ?: return
        if (current.isSaving) return

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            when (val outcome = engine.save(document, current.text, createBackup)) {
                is SaveOutcome.Success -> {
                    val reloaded = document.copy(
                        text = current.text,
                        lineEnding = document.lineEnding,
                        byteSize = outcome.bytesWritten,
                    )
                    _state.update {
                        it.copy(
                            content = EditorContent.Ready(reloaded),
                            isSaving = false,
                            isDirty = false,
                            lastSaveMessage = buildString {
                                append("已保存（${outcome.encoding}，")
                                append(outcome.lineEnding.label)
                                append("）")
                                outcome.backupPath?.let { backup ->
                                    append("，备份：")
                                    append(backup.substringAfterLast('/'))
                                }
                            },
                        )
                    }
                }

                is SaveOutcome.Failure -> _state.update {
                    // The buffer stays dirty, which is the truth: the edit is not on disk.
                    it.copy(isSaving = false, errorMessage = outcome.reason)
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(lastSaveMessage = null) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun human(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MiB", bytes / 1048576.0)
    }

    override fun onCleared() {
        loadJob?.cancel()
    }
}
