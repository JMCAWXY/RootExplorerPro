package io.rootexplorer.pro.service.taskmanager

import io.rootexplorer.pro.service.fileoperation.Conflict
import io.rootexplorer.pro.service.fileoperation.ConflictDecision
import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import io.rootexplorer.pro.service.fileoperation.FileOperationEngine
import io.rootexplorer.pro.service.fileoperation.OperationOutcome
import io.rootexplorer.pro.service.fileoperation.OperationProgress
import io.rootexplorer.pro.service.trash.TrashRepository
import io.rootexplorer.pro.service.trash.TrashResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What kind of long-running work is on the task bar. */
enum class TaskKind(val label: String) {
    COPY("复制"),
    MOVE("移动"),
    DELETE_TO_TRASH("移入回收站"),
    DELETE_PERMANENT("永久删除"),
    EMPTY_TRASH("清空回收站"),
}

/** The state of the one foreground task the task bar displays. */
sealed interface TaskState {

    /** Nothing running. */
    data object Idle : TaskState

    /** Collecting size, file count and conflicts before writing anything. */
    data class Preparing(val kind: TaskKind, val bytes: Long, val items: Int) : TaskState

    /** Waiting for the user to answer a name collision. */
    data class AskingConflict(
        val kind: TaskKind,
        val conflict: Conflict,
        val remaining: Int,
    ) : TaskState

    data class Running(
        val kind: TaskKind,
        val currentItem: String,
        val itemsDone: Int,
        val itemsTotal: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
        val failures: Int,
    ) : TaskState {
        val fraction: Float?
            get() = when {
                bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
                itemsTotal > 0 -> (itemsDone.toDouble() / itemsTotal).toFloat().coerceIn(0f, 1f)
                else -> null
            }
    }

    /** Finished, successfully or otherwise. Kept on screen until dismissed. */
    data class Finished(
        val kind: TaskKind,
        val headline: String,
        val detail: String,
        val isPartial: Boolean,
        val isFailure: Boolean,
        val failures: List<String>,
    ) : TaskState
}

/**
 * Owns the one long-running file task and everything the UI needs to show about it.
 *
 * Why a single foreground task: the brief asks for an accurate progress view with a working
 * cancel button and an honest failure summary. Running several transfers at once against the
 * same storage would make the reported throughput and the partial-failure accounting
 * meaningless, so concurrency is bounded to one and that limit is explicit.
 *
 * Cancellation is cooperative through the coroutine job; the engine checks it between chunks
 * and returns a partial result rather than claiming success.
 */
class TaskManager(
    private val scope: CoroutineScope,
    private val engine: FileOperationEngine,
    private val trash: TrashRepository,
) {

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private var currentJob: Job? = null

    /** Set while the engine is blocked on [ConflictPolicy.ASK]. */
    private var pendingConflict: CompletableDeferred<ConflictDecision>? = null

    /** Counts conflicts already answered, so the dialog can say "1 of 3". */
    private val conflictCounter = AtomicLong(0)

    val isBusy: Boolean get() = currentJob?.isActive == true

    /**
     * Start a copy or move of [sources] into [destination].
     *
     * Returns false when a task is already running, so the caller can tell the user instead of
     * silently queueing behind something they cannot see.
     */
    fun startTransfer(
        sources: List<String>,
        destination: String,
        move: Boolean,
        policy: ConflictPolicy,
        renameHints: Map<String, String> = emptyMap(),
    ): Boolean {
        if (isBusy) return false
        if (sources.isEmpty()) return false

        val kind = if (move) TaskKind.MOVE else TaskKind.COPY
        conflictCounter.set(0)
        _state.value = TaskState.Preparing(kind, bytes = 0L, items = 0)

        currentJob = scope.launch {
            try {
                val outcome = engine.execute(
                    sources = sources,
                    destinationDirectory = destination,
                    move = move,
                    policy = policy,
                    resolver = { conflict ->
                        conflictCounter.incrementAndGet()
                        val deferred = CompletableDeferred<ConflictDecision>()
                        pendingConflict = deferred
                        _state.update { current ->
                            if (current is TaskState.Running || current is TaskState.Preparing ||
                                current is TaskState.AskingConflict
                            ) {
                                TaskState.AskingConflict(kind, conflict, remaining = 0)
                            } else {
                                current
                            }
                        }
                        deferred.await()
                    },
                    renameHints = renameHints,
                    onProgress = { progress -> publish(kind, progress) },
                )
                _state.value = summarise(kind, outcome, sources.size)
            } catch (e: CancellationException) {
                // The engine returns a Cancelled outcome for cooperative stops; reaching here
                // means the job was cancelled from outside, which is also a cancellation.
                _state.value = TaskState.Finished(
                    kind = kind,
                    headline = "${kind.label}已取消",
                    detail = "操作被中断，已保留完成的部分。",
                    isPartial = true,
                    isFailure = false,
                    failures = emptyList(),
                )
                throw e
            } catch (e: Exception) {
                _state.value = TaskState.Finished(
                    kind = kind,
                    headline = "${kind.label}失败",
                    detail = e.message ?: e.javaClass.simpleName,
                    isPartial = false,
                    isFailure = true,
                    failures = emptyList(),
                )
            } finally {
                pendingConflict = null
                currentJob = null
            }
        }
        return true
    }

    /**
     * Move [sources] to the recycle bin instead of deleting them.
     *
     * A refusal (cross-volume, permission) is reported per item and the overall result says
     * how many actually made it in.
     */
    fun startTrash(
        sources: List<Pair<String, Long?>>,
        moveToTrash: Boolean = true,
    ): Boolean {
        if (isBusy) return false
        if (sources.isEmpty()) return false

        val kind = if (moveToTrash) TaskKind.DELETE_TO_TRASH else TaskKind.DELETE_PERMANENT
        _state.value = TaskState.Preparing(kind, bytes = sources.sumOf { it.second ?: 0L }, sources.size)

        currentJob = scope.launch {
            val failures = mutableListOf<String>()
            var done = 0
            var bytes = 0L

            try {
                for ((path, size) in sources) {
                    currentCoroutineContext().ensureActive()

                    if (moveToTrash) {
                        when (val result = trash.moveToTrash(path, size)) {
                            is TrashResult.Success -> {
                                done++
                                bytes += result.entry.sizeBytes ?: 0L
                            }

                            is TrashResult.Failure -> failures += "$path：${result.reason}"
                        }
                    } else {
                        failures += "$path：永久删除请使用回收站内的删除操作"
                    }

                    _state.value = TaskState.Running(
                        kind = kind,
                        currentItem = path,
                        itemsDone = done,
                        itemsTotal = sources.size,
                        bytesDone = bytes,
                        bytesTotal = sources.sumOf { it.second ?: 0L },
                        bytesPerSecond = 0L,
                        failures = failures.size,
                    )
                }

                _state.value = TaskState.Finished(
                    kind = kind,
                    headline = if (failures.isEmpty()) {
                        "${kind.label}完成：$done 项"
                    } else {
                        "${kind.label}部分完成：$done 项成功，${failures.size} 项失败"
                    },
                    detail = if (failures.isEmpty()) "可在回收站中还原。" else failures.joinToString("\n"),
                    isPartial = failures.isNotEmpty(),
                    isFailure = failures.isNotEmpty() && done == 0,
                    failures = failures,
                )
            } catch (e: CancellationException) {
                _state.value = TaskState.Finished(
                    kind = kind,
                    headline = "${kind.label}已取消",
                    detail = "已处理 $done 项。",
                    isPartial = true,
                    isFailure = false,
                    failures = failures,
                )
                throw e
            } finally {
                currentJob = null
            }
        }
        return true
    }

    /** Empty the recycle bin, optionally only items matching the cleanup rules. */
    fun startEmptyTrash(olderThanMillis: Long? = null, largerThanBytes: Long? = null): Boolean {
        if (isBusy) return false
        val kind = TaskKind.EMPTY_TRASH
        _state.value = TaskState.Preparing(kind, 0L, 0)

        currentJob = scope.launch {
            try {
                val result = trash.empty(olderThanMillis, largerThanBytes)
                val removed = result.getOrNull() ?: 0
                val error = result.exceptionOrNull()
                _state.value = TaskState.Finished(
                    kind = kind,
                    headline = if (error == null) {
                        "已清空回收站：$removed 项"
                    } else {
                        "清空回收站部分完成：$removed 项"
                    },
                    detail = error?.message ?: "释放了空间。",
                    isPartial = error != null,
                    isFailure = error != null && removed == 0,
                    failures = listOfNotNull(error?.message),
                )
            } catch (e: CancellationException) {
                _state.value = TaskState.Finished(
                    kind = kind,
                    headline = "清空回收站已取消",
                    detail = "",
                    isPartial = true,
                    isFailure = false,
                    failures = emptyList(),
                )
                throw e
            } finally {
                currentJob = null
            }
        }
        return true
    }

    /** Answer the conflict the engine is waiting on. */
    fun answerConflict(policy: ConflictPolicy) {
        pendingConflict?.complete(ConflictDecision.Apply(policy))
        pendingConflict = null
    }

    /** Abort the whole operation from the conflict dialog. */
    fun cancelConflict() {
        pendingConflict?.complete(ConflictDecision.CancelOperation)
        pendingConflict = null
    }

    /** Stop the running task; the engine reports what it managed to do. */
    fun cancel() {
        pendingConflict?.complete(ConflictDecision.CancelOperation)
        pendingConflict = null
        currentJob?.cancel()
    }

    /** Dismiss a finished result from the task bar. */
    fun dismiss() {
        if (!isBusy) _state.value = TaskState.Idle
    }

    // ------------------------------------------------------------------ internals

    private fun publish(kind: TaskKind, progress: OperationProgress) {
        _state.value = when (progress) {
            is OperationProgress.Preparing -> TaskState.Preparing(kind, progress.bytes, progress.itemsScanned)

            is OperationProgress.AwaitingConflict -> TaskState.AskingConflict(
                kind = kind,
                conflict = progress.conflict,
                remaining = conflictCounter.get().toInt(),
            )

            is OperationProgress.Running -> TaskState.Running(
                kind = kind,
                currentItem = progress.currentItem,
                itemsDone = progress.itemsDone,
                itemsTotal = progress.itemsTotal,
                bytesDone = progress.bytesDone,
                bytesTotal = progress.bytesTotal,
                bytesPerSecond = progress.bytesPerSecond,
                failures = progress.failures,
            )
        }
    }

    private fun summarise(kind: TaskKind, outcome: OperationOutcome, requested: Int): TaskState.Finished =
        when (outcome) {
            is OperationOutcome.Rejected -> TaskState.Finished(
                kind = kind,
                headline = "${kind.label}未执行",
                detail = outcome.reason,
                isPartial = false,
                isFailure = true,
                failures = listOf(outcome.reason),
            )

            is OperationOutcome.Cancelled -> TaskState.Finished(
                kind = kind,
                headline = "${kind.label}已取消",
                detail = "已复制 ${outcome.itemsCopied} 项、${human(outcome.bytesCopied)}。" +
                    if (outcome.failed.isEmpty()) "" else " 另有 ${outcome.failed.size} 项失败。",
                isPartial = true,
                isFailure = false,
                failures = outcome.failed.map { it.toString() },
            )

            is OperationOutcome.Completed -> {
                val skippedNote = if (outcome.itemsSkipped > 0) {
                    "，跳过 ${outcome.itemsSkipped} 项"
                } else {
                    ""
                }
                TaskState.Finished(
                    kind = kind,
                    headline = when {
                        outcome.isFullSuccess ->
                            "${kind.label}完成：${outcome.itemsCopied} 项${skippedNote}"
                        outcome.itemsCopied > 0 ->
                            "${kind.label}部分完成：成功 ${outcome.itemsCopied} 项，失败 ${outcome.failed.size} 项"
                        else ->
                            "${kind.label}失败：${outcome.failed.size} 项均未完成"
                    },
                    detail = buildString {
                        append("共 ${human(outcome.bytesCopied)}")
                        if (requested > 0) append("（请求 $requested 项）")
                        if (outcome.failed.isNotEmpty()) {
                            append("\n")
                            append(outcome.failed.take(20).joinToString("\n") { it.toString() })
                            if (outcome.failed.size > 20) {
                                append("\n…另有 ${outcome.failed.size - 20} 项")
                            }
                        }
                    },
                    isPartial = outcome.isPartial,
                    isFailure = !outcome.isFullSuccess && outcome.itemsCopied == 0,
                    failures = outcome.failed.map { it.toString() },
                )
            }
        }

    private fun human(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MiB", bytes / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f GiB", bytes / 1073741824.0)
    }
}
