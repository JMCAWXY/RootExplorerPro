package io.rootexplorer.pro.feature.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.archive.ArchiveEngine
import io.rootexplorer.pro.core.archive.ArchiveFormat
import io.rootexplorer.pro.core.archive.ArchiveOutcome
import io.rootexplorer.pro.core.archive.ArchiveProgress
import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which archive action is running. */
enum class ArchiveAction(val label: String) {
    COMPRESS("压缩"),
    EXTRACT("解压"),
}

/** Progress and result of an archive operation. */
data class ArchiveUiState(
    val action: ArchiveAction? = null,
    val progress: ArchiveProgress? = null,
    val isRunning: Boolean = false,
    /** Set when the operation finished; the dialog shows it and stays open until dismissed. */
    val result: ArchiveResultSummary? = null,
) {
    val fraction: Float? get() = progress?.fraction
}

/** A finished operation, described the way the user needs to hear it. */
data class ArchiveResultSummary(
    val headline: String,
    val detail: String,
    val isPartial: Boolean,
    val isFailure: Boolean,
)

/**
 * Compress and extract, with progress and cancellation.
 *
 * The engine already refuses unsafe entries and never overwrites unless told to; this class
 * only runs it and turns the outcome into something the user can act on. Partial results are
 * reported as partial: an extraction that skipped three entries says so rather than claiming
 * success.
 */
class ArchiveViewModel(
    private val engine: ArchiveEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** Build an archive from [sources] at [targetArchivePath]. */
    fun compress(
        sources: List<String>,
        targetArchivePath: String,
        format: ArchiveFormat,
        compressionLevel: Int,
    ) {
        if (isBusy) return
        _state.value = ArchiveUiState(action = ArchiveAction.COMPRESS, isRunning = true)

        job = viewModelScope.launch {
            val outcome = engine.compress(
                sources = sources,
                targetArchivePath = targetArchivePath,
                format = format,
                compressionLevel = compressionLevel,
                onProgress = { progress -> _state.update { it.copy(progress = progress) } },
            )
            _state.update { it.copy(isRunning = false, result = summarise(ArchiveAction.COMPRESS, outcome)) }
            job = null
        }
    }

    /** Extract [archivePath] into [targetDirectory]. */
    fun extract(
        archivePath: String,
        targetDirectory: String,
        policy: ConflictPolicy,
    ) {
        if (isBusy) return
        _state.value = ArchiveUiState(action = ArchiveAction.EXTRACT, isRunning = true)

        job = viewModelScope.launch {
            val outcome = engine.extract(
                archivePath = archivePath,
                targetDirectory = targetDirectory,
                policy = policy,
                onProgress = { progress -> _state.update { it.copy(progress = progress) } },
            )
            _state.update { it.copy(isRunning = false, result = summarise(ArchiveAction.EXTRACT, outcome)) }
            job = null
        }
    }

    /** List an archive's contents without extracting, for the confirmation step. */
    suspend fun listArchive(path: String) = engine.list(path)

    /** Stop the running operation. A cancelled compress deletes its half-written output. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                isRunning = false,
                result = ArchiveResultSummary(
                    headline = "${it.action?.label ?: "操作"}已取消",
                    detail = "未完成的输出已被删除，不会留下不完整的压缩包。",
                    isPartial = true,
                    isFailure = false,
                ),
            )
        }
    }

    /** Close the dialog, clearing the result. */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = ArchiveUiState()
    }

    /** Forget a finished result but keep the dialog open. */
    fun clearResult() {
        _state.update { it.copy(result = null) }
    }

    private fun summarise(action: ArchiveAction, outcome: ArchiveOutcome): ArchiveResultSummary =
        when (outcome) {
            is ArchiveOutcome.Failure -> ArchiveResultSummary(
                headline = "${action.label}失败",
                detail = outcome.reason,
                isPartial = false,
                isFailure = true,
            )

            is ArchiveOutcome.Cancelled -> ArchiveResultSummary(
                headline = "${action.label}已取消",
                detail = "已处理 ${outcome.entriesDone} 个条目（${outcome.bytesDone} 字节）。",
                isPartial = true,
                isFailure = false,
            )

            is ArchiveOutcome.Success -> {
                val skippedNote = if (outcome.skipped > 0) "，跳过 ${outcome.skipped} 项" else ""
                val failedNote = if (outcome.failed.isNotEmpty()) {
                    "，失败 ${outcome.failed.size} 项"
                } else {
                    ""
                }
                ArchiveResultSummary(
                    headline = when {
                        outcome.failed.isEmpty() ->
                            "${action.label}完成：${outcome.entries} 项$skippedNote"
                        outcome.entries > 0 ->
                            "${action.label}部分完成：${outcome.entries} 项$failedNote"
                        else ->
                            "${action.label}失败：没有条目被处理"
                    },
                    detail = buildString {
                        append("共 ${outcome.bytes} 字节")
                        if (outcome.failed.isNotEmpty()) {
                            append("\n")
                            append(
                                outcome.failed.take(10)
                                    .joinToString("\n") { "${it.name}：${it.reason}" },
                            )
                            if (outcome.failed.size > 10) {
                                append("\n…另有 ${outcome.failed.size - 10} 项")
                            }
                        }
                    },
                    isPartial = outcome.isPartial,
                    isFailure = outcome.failed.isNotEmpty() && outcome.entries == 0,
                )
            }
        }

    override fun onCleared() {
        job?.cancel()
        job = null
    }
}
