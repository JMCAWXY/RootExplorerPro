package io.rootexplorer.pro.feature.hash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.crypto.HashAlgorithm
import io.rootexplorer.pro.core.crypto.HashEngine
import io.rootexplorer.pro.core.crypto.HashOutcome
import io.rootexplorer.pro.core.crypto.HashProgress
import io.rootexplorer.pro.core.crypto.HashResult
import io.rootexplorer.pro.core.crypto.HashVerification
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One algorithm's progress in the verification dialog. */
data class HashSlot(
    val algorithm: HashAlgorithm,
    val digest: String? = null,
    val error: String? = null,
) {
    val isDone: Boolean get() = digest != null
}

/** State of the checksum dialog. */
data class HashUiState(
    val path: String? = null,
    val slots: List<HashSlot> = HashAlgorithm.all.map { HashSlot(it) },
    val progress: HashProgress? = null,
    val isRunning: Boolean = false,
    /** True when the user stopped it; the partial hashes are discarded, not shown as valid. */
    val wasCancelled: Boolean = false,
    /** A message about the file itself, e.g. it is a directory or too large to be worth it. */
    val notice: String? = null,
    /** What the user pasted to compare against. */
    val pastedChecksum: String = "",
    val verification: HashVerification? = null,
    val fileSizeBytes: Long = 0L,
) {
    val allDone: Boolean get() = slots.all { it.isDone }

    /** The algorithm the pasted checksum is being compared against. */
    fun resultFor(algorithm: HashAlgorithm): HashResult? {
        val digest = slots.firstOrNull { it.algorithm == algorithm }?.digest ?: return null
        return HashResult(
            path = path.orEmpty(),
            algorithm = algorithm,
            digest = digest,
            bytesRead = fileSizeBytes,
            durationMillis = 0L,
        )
    }
}

/**
 * Checksum computation for one file.
 *
 * All three algorithms are computed in a **single pass** over the file. Hashing the same 4 GB
 * three times would triple the I/O for the same answer, and the brief asks for large files to
 * be handled without freezing the interface.
 *
 * Cancelling discards the in-progress hashes rather than showing partial values: a truncated
 * digest is not a checksum and displaying one would invite a bogus comparison.
 */
class HashViewModel(
    private val engine: HashEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(HashUiState())
    val state: StateFlow<HashUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Start hashing [path], replacing any file already being hashed. */
    fun start(path: String, fileSizeBytes: Long) {
        job?.cancel()

        _state.value = HashUiState(
            path = path,
            fileSizeBytes = fileSizeBytes,
            isRunning = true,
            slots = HashAlgorithm.all.map { HashSlot(it) },
        )

        job = viewModelScope.launch {
            val outcomes = engine.hashFileMulti(
                path = path,
                algorithms = HashAlgorithm.all,
                onProgress = { progress ->
                    _state.update { it.copy(progress = progress) }
                },
            )

            val slots = HashAlgorithm.all.map { algorithm ->
                when (val outcome = outcomes[algorithm]) {
                    is HashOutcome.Success -> HashSlot(algorithm, digest = outcome.result.digest)
                    is HashOutcome.Failure -> HashSlot(algorithm, error = outcome.reason)
                    is HashOutcome.Cancelled -> HashSlot(algorithm, error = "已取消")
                    null -> HashSlot(algorithm, error = "未能计算")
                }
            }

            _state.update {
                it.copy(
                    slots = slots,
                    isRunning = false,
                    // A cancelled run leaves no valid digest, so the failure text is what shows.
                    wasCancelled = slots.all { slot -> slot.error == "已取消" },
                )
            }
        }
    }

    /** Stop hashing. The results are cleared because a partial digest is meaningless. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                isRunning = false,
                wasCancelled = true,
                progress = null,
                slots = HashAlgorithm.all.map { slot -> HashSlot(slot, error = "已取消") },
            )
        }
    }

    fun setPastedChecksum(text: String) {
        _state.update { current ->
            val verification = current.slots
                .firstOrNull { it.digest != null }
                ?.let { slot ->
                    current.resultFor(slot.algorithm)?.let { result ->
                        if (text.isBlank()) null else engine.verify(text, result)
                    }
                }
            current.copy(pastedChecksum = text, verification = verification)
        }
    }

    /**
     * Compare the pasted value against a specific algorithm.
     *
     * Letting the user pick the algorithm matters: a 40-character paste is SHA-1, and comparing
     * it against SHA-256 would only ever say "wrong length".
     */
    fun verifyAgainst(algorithm: HashAlgorithm) {
        _state.update { current ->
            val result = current.resultFor(algorithm)
            val verification = when {
                result == null -> null
                current.pastedChecksum.isBlank() -> null
                else -> engine.verify(current.pastedChecksum, result)
            }
            current.copy(verification = verification)
        }
    }

    override fun onCleared() {
        job?.cancel()
        job = null
    }
}
