package io.rootexplorer.pro.core.crypto

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The digest algorithms offered. */
enum class HashAlgorithm(
    /** Name shown in the UI. */
    val label: String,
    /** The JCA algorithm name. */
    val jcaName: String,
    /** Hex characters in the result: 16 for MD5, 40 for SHA-1, 64 for SHA-256. */
    val hexLength: Int,
) {
    MD5("MD5", "MD5", 32),
    SHA1("SHA-1", "SHA-1", 40),
    SHA256("SHA-256", "SHA-256", 64),
    ;

    /**
     * True when the algorithm is broken for security purposes.
     *
     * MD5 and SHA-1 are still fine for verifying a download against a published checksum, which
     * is what this feature is for, but saying so is the difference between a tool and a trap.
     */
    val isCryptographicallyBroken: Boolean
        get() = this == MD5 || this == SHA1

    companion object {
        /** Every algorithm, in the order the UI lists them. */
        val all: List<HashAlgorithm> = entries.toList()
    }
}

/** How a hash computation is progressing. */
data class HashProgress(
    val bytesRead: Long,
    val totalBytes: Long,
) {
    /** 0f..1f, or null when the total size is not known (a stream, or a growing file). */
    val fraction: Float?
        get() = if (totalBytes > 0) {
            (bytesRead.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }
}

/** The result of hashing one file with one algorithm. */
data class HashResult(
    val path: String,
    val algorithm: HashAlgorithm,
    /** Lower-case hex digest. */
    val digest: String,
    val bytesRead: Long,
    /** Wall-clock duration in milliseconds, for the UI's "took 1.2s" line. */
    val durationMillis: Long,
) {
    /** The digest in the grouped form tools usually display, e.g. `a1b2 c3d4 …`. */
    fun groupedDigest(groupSize: Int = 8): String =
        digest.chunked(groupSize).joinToString(" ")
}

/** Why a hash could not be computed. */
sealed interface HashOutcome {
    data class Success(val result: HashResult) : HashOutcome
    data class Failure(val reason: String) : HashOutcome
    /** The user cancelled; [bytesRead] says how far it got. */
    data class Cancelled(val bytesRead: Long) : HashOutcome
}

/**
 * Computes file digests.
 *
 * The brief requires that verification reads the real file contents, that large files are done
 * in the background with progress, and that the interface never freezes. All three are handled
 * here: the read is chunked so it can report progress and stop between chunks, and the caller
 * decides which dispatcher to run on (the ViewModel already uses a background scope).
 *
 * A digest is computed from the bytes on disk, so the result can be compared against an
 * independently produced checksum and must match exactly.
 */
class HashEngine(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {

    /**
     * Hash one file.
     *
     * [onProgress] is called from the calling coroutine, roughly every [PROGRESS_INTERVAL_BYTES]
     * rather than per chunk, so a fast disk does not flood the UI with recompositions.
     */
    suspend fun hashFile(
        path: String,
        algorithm: HashAlgorithm,
        onProgress: (HashProgress) -> Unit = {},
    ): HashOutcome = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) {
            return@withContext HashOutcome.Failure("文件不存在：$path")
        }
        if (file.isDirectory) {
            return@withContext HashOutcome.Failure("不能对目录计算校验值：$path")
        }

        val digest = try {
            MessageDigest.getInstance(algorithm.jcaName)
        } catch (e: NoSuchAlgorithmException) {
            // Cannot happen on Android, but reporting it beats a crash if it ever does.
            return@withContext HashOutcome.Failure("当前运行环境不支持 ${algorithm.jcaName}")
        }

        val total = file.length()
        var read = 0L
        var lastReported = 0L
        val startedAt = System.currentTimeMillis()

        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    // Cooperative cancellation: checked between chunks so cancelling a 4 GB
                    // hash stops promptly instead of running to completion.
                    currentCoroutineContext().ensureActive()

                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    read += count

                    if (read - lastReported >= PROGRESS_INTERVAL_BYTES) {
                        lastReported = read
                        onProgress(HashProgress(read, total))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return@withContext HashOutcome.Cancelled(read)
        } catch (e: IOException) {
            return@withContext HashOutcome.Failure(
                "读取失败：${e.message ?: e.javaClass.simpleName}（已读取 $read 字节）",
            )
        } catch (e: SecurityException) {
            return@withContext HashOutcome.Failure("没有权限读取：$path")
        }

        onProgress(HashProgress(read, total))

        HashOutcome.Success(
            HashResult(
                path = path,
                algorithm = algorithm,
                digest = digest.digest().toHex(),
                bytesRead = read,
                durationMillis = System.currentTimeMillis() - startedAt,
            ),
        )
    }

    /**
     * Hash one file with several algorithms in a single pass.
     *
     * Reading the file once rather than once per algorithm matters for the large files this
     * feature exists for: three passes over 4 GB is three times the I/O for the same answer.
     */
    suspend fun hashFileMulti(
        path: String,
        algorithms: List<HashAlgorithm> = HashAlgorithm.all,
        onProgress: (HashProgress) -> Unit = {},
    ): Map<HashAlgorithm, HashOutcome> = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || file.isDirectory) {
            val reason = if (file.isDirectory) "不能对目录计算校验值：$path" else "文件不存在：$path"
            return@withContext algorithms.associateWith { HashOutcome.Failure(reason) }
        }

        val digests = mutableMapOf<HashAlgorithm, MessageDigest>()
        for (algorithm in algorithms) {
            try {
                digests[algorithm] = MessageDigest.getInstance(algorithm.jcaName)
            } catch (e: NoSuchAlgorithmException) {
                // Skipped below; the caller sees no entry for it.
            }
        }
        if (digests.isEmpty()) {
            return@withContext algorithms.associateWith {
                HashOutcome.Failure("当前运行环境不支持 ${it.jcaName}")
            }
        }

        val total = file.length()
        var read = 0L
        var lastReported = 0L
        val startedAt = System.currentTimeMillis()

        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (d in digests.values) d.update(buffer, 0, count)
                    read += count
                    if (read - lastReported >= PROGRESS_INTERVAL_BYTES) {
                        lastReported = read
                        onProgress(HashProgress(read, total))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return@withContext algorithms.associateWith { HashOutcome.Cancelled(read) }
        } catch (e: IOException) {
            val reason = "读取失败：${e.message ?: e.javaClass.simpleName}"
            return@withContext algorithms.associateWith { HashOutcome.Failure(reason) }
        }

        onProgress(HashProgress(read, total))
        val elapsed = System.currentTimeMillis() - startedAt

        algorithms.associateWith { algorithm ->
            val d = digests[algorithm]
            if (d == null) {
                HashOutcome.Failure("当前运行环境不支持 ${algorithm.jcaName}")
            } else {
                HashOutcome.Success(
                    HashResult(
                        path = path,
                        algorithm = algorithm,
                        digest = d.digest().toHex(),
                        bytesRead = read,
                        durationMillis = elapsed,
                    ),
                )
            }
        }
    }

    /**
     * Compare a file against a checksum the user pasted in.
     *
     * The comparison is case-insensitive and ignores whitespace and separators, because
     * checksums are published in many shapes (`a1b2c3…`, `A1:B2:C3:…`, `a1b2 c3d4 …`). A
     * malformed input is reported as such rather than silently reported as a mismatch.
     */
    fun verify(pasted: String, result: HashResult): HashVerification {
        val normalised = normaliseChecksum(pasted)
        if (normalised.isEmpty()) {
            return HashVerification.InvalidInput("请输入要对比的校验值")
        }
        if (normalised.length != result.algorithm.hexLength) {
            return HashVerification.InvalidInput(
                "长度不符：${result.algorithm.label} 应为 ${result.algorithm.hexLength} 个十六进制字符，" +
                    "输入为 ${normalised.length} 个",
            )
        }
        if (!normalised.all { it in '0'..'9' || it in 'a'..'f' }) {
            return HashVerification.InvalidInput("只能包含十六进制字符（0-9、a-f）")
        }
        return if (normalised == result.digest.lowercase()) {
            HashVerification.Match
        } else {
            HashVerification.Mismatch(expected = normalised, actual = result.digest.lowercase())
        }
    }

    /** Strip separators and lower-case a pasted checksum. */
    fun normaliseChecksum(pasted: String): String =
        pasted.trim().lowercase().filter { it.isLetterOrDigit() }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = CharArray(size * 2)
        for (i in indices) {
            val value = this[i].toInt() and 0xFF
            out[i * 2] = digits[value ushr 4]
            out[i * 2 + 1] = digits[value and 0x0F]
        }
        return String(out)
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 256 * 1024

        /**
         * How many bytes to read between progress callbacks.
         *
         * Large enough that the callback cost is negligible against the I/O, small enough that
         * the bar moves visibly on a multi-gigabyte file.
         */
        const val PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
    }
}

/** The outcome of comparing a pasted checksum against a computed one. */
sealed interface HashVerification {
    /** The checksum matches the file. */
    data object Match : HashVerification

    data class Mismatch(val expected: String, val actual: String) : HashVerification

    /** The pasted text is not a valid checksum for the chosen algorithm. */
    data class InvalidInput(val reason: String) : HashVerification

    /** A sentence for the UI. */
    val message: String
        get() = when (this) {
            Match -> "校验值一致，文件与校验值匹配。"
            is Mismatch -> "校验值不一致：文件可能已损坏或被修改。"
            is InvalidInput -> reason
        }
}
