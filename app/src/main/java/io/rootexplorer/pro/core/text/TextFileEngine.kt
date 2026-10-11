package io.rootexplorer.pro.core.text

import java.io.File
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which line ending a file uses. */
enum class LineEnding(val label: String, val sequence: String) {
    LF("Unix (LF)", "\n"),
    CRLF("Windows (CRLF)", "\r\n"),
    CR("Classic Mac (CR)", "\r"),
    ;

    companion object {
        /** Detect the dominant line ending in [text]; defaults to LF when there is none. */
        fun detect(text: String): LineEnding {
            val crlf = Regex("\r\n").findAll(text).count()
            val lfOnly = Regex("(?<!\r)\n").findAll(text).count()
            val crOnly = Regex("\r(?!\n)").findAll(text).count()
            return when {
                crlf == 0 && lfOnly == 0 && crOnly == 0 -> LF
                crlf >= lfOnly && crlf >= crOnly -> CRLF
                crOnly > lfOnly -> CR
                else -> LF
            }
        }
    }
}

/** Why a file cannot be edited as text. */
sealed interface TextLoadResult {

    data class Success(val document: TextDocument) : TextLoadResult

    /** The file is binary; editing it as text would corrupt it. */
    data class Binary(val reason: String, val detectedBytes: Long) : TextLoadResult

    /** The file is larger than the editor will open. */
    data class TooLarge(val sizeBytes: Long, val limitBytes: Long) : TextLoadResult

    /** The bytes are not valid text in any encoding this editor supports. */
    data class NotText(val reason: String) : TextLoadResult

    data class Failure(val reason: String) : TextLoadResult
}

/** A loaded text file. */
data class TextDocument(
    val path: String,
    val text: String,
    val encoding: String,
    val lineEnding: LineEnding,
    val byteSize: Long,
    /** True when the byte-order mark was present and has been stripped from [text]. */
    val hadBom: Boolean,
    /** True when the file's bytes were not valid UTF-8 and a fallback encoding was used. */
    val usedFallbackEncoding: Boolean,
) {
    /** Number of lines, counting a trailing newline as not starting a new line. */
    val lineCount: Int
        get() = if (text.isEmpty()) 1 else Regex("\n").findAll(text).count() + 1

    /**
     * Convert this document's text to [ending] and encode it.
     *
     * All three line-ending styles are normalised in one pass rather than by three sequential
     * replacements, which would turn a CRLF into two line breaks.
     */
    fun serialise(newText: String): ByteArray {
        val normalised = normaliseLineEndings(newText, lineEnding)
        val body = normalised.toByteArray(charsetForName(encoding))
        return if (hadBom) BOM_UTF8 + body else body
    }

    companion object {
        val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}

/**
 * Normalise every line ending in [text] to [ending].
 *
 * File-level rather than a member so [TextDocument.serialise] and [TextFileEngine] share one
 * implementation; two copies of this rule is how a CRLF ends up doubled.
 */
internal fun normaliseLineEndings(text: String, ending: LineEnding): String =
    text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", ending.sequence)

/** Resolve an encoding name recorded on a document back to a charset. */
internal fun charsetForName(name: String): java.nio.charset.Charset = when (name) {
    "GB18030" -> charset("GB18030")
    else -> StandardCharsets.UTF_8
}

/**
 * Reads and writes text files.
 *
 * The brief asks the editor to handle UTF-8, common newline formats, search and replace, large
 * files and to give a clear message for binary or unsupported files. The decisions that keep it
 * honest live here rather than in the UI:
 *
 * * **Binary detection before decoding.** A file containing NUL bytes, or a high proportion of
 *   non-text control bytes, is refused instead of being opened as mojibake and then saved back
 *   with the damage made permanent.
 * * **A size limit with a stated number.** The limit is reported with the actual size so the
 *   user knows what to do about it, rather than the file simply failing to open.
 * * **Encoding is recorded, not guessed at on save.** Bytes are decoded as strict UTF-8 first;
 *   only if that fails does it try a fallback, and the document remembers which was used so the
 *   save writes the same encoding back.
 * * **The original is backed up before an overwrite.** Editing a system file and losing it is
 *   the worst outcome this feature has, so a `.bak` is written first.
 */
class TextFileEngine(
    /** The largest file the editor will open, in bytes. */
    private val maxEditableBytes: Long = DEFAULT_MAX_EDITABLE_BYTES,
) {

    /** Read [path] as text, or explain why it cannot be edited. */
    suspend fun load(path: String): TextLoadResult = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext TextLoadResult.Failure("文件不存在：$path")
        if (file.isDirectory) return@withContext TextLoadResult.Failure("这是一个目录，无法作为文本编辑：$path")

        val size = file.length()
        if (size > maxEditableBytes) {
            return@withContext TextLoadResult.TooLarge(size, maxEditableBytes)
        }

        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return@withContext TextLoadResult.Failure("读取失败：${e.message ?: e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            return@withContext TextLoadResult.Failure("没有权限读取：$path")
        }

        // Binary check first: decoding a binary file and offering to save it would corrupt it.
        sniffBinary(bytes)?.let { reason ->
            return@withContext TextLoadResult.Binary(reason, size)
        }

        val hadBom = bytes.size >= 3 &&
            bytes[0] == TextDocument.BOM_UTF8[0] &&
            bytes[1] == TextDocument.BOM_UTF8[1] &&
            bytes[2] == TextDocument.BOM_UTF8[2]
        val body = if (hadBom) bytes.copyOfRange(3, bytes.size) else bytes

        val strictUtf8 = decodeStrict(body, StandardCharsets.UTF_8)
        val (text, encoding, usedFallback) = when {
            strictUtf8 != null -> Triple(strictUtf8, "UTF-8", false)
            else -> {
                // GB18030 covers GBK and GB2312, which is the common non-UTF-8 case for Chinese
                // text files; it never fails, so it is used as the fallback rather than a guess
                // between several code pages.
                val gb = runCatching { String(body, charset("GB18030")) }.getOrNull()
                if (gb == null) {
                    return@withContext TextLoadResult.NotText(
                        "无法以 UTF-8 或 GB18030 解码该文件，可能使用了不受支持的编码。",
                    )
                }
                Triple(gb, "GB18030", true)
            }
        }

        TextLoadResult.Success(
            TextDocument(
                path = path,
                text = text,
                encoding = encoding,
                lineEnding = LineEnding.detect(text),
                byteSize = size,
                hadBom = hadBom,
                usedFallbackEncoding = usedFallback,
            ),
        )
    }

    /**
     * Save [newText] to [document]'s path.
     *
     * A backup of the previous contents is written beside the original first, so an edit that
     * turns out to be a mistake is recoverable. The write itself goes to a temporary file in
     * the same directory and is then moved into place, so an interrupted save cannot leave a
     * truncated file where the original was.
     */
    suspend fun save(
        document: TextDocument,
        newText: String,
        createBackup: Boolean = true,
    ): SaveOutcome = withContext(Dispatchers.IO) {
        val target = File(document.path)
        val parent = target.parentFile
            ?: return@withContext SaveOutcome.Failure("无法确定上级目录：${document.path}")

        if (!parent.isDirectory) {
            return@withContext SaveOutcome.Failure("上级目录不存在：${parent.absolutePath}")
        }

        val bytes = document.serialise(newText)

        var backupPath: String? = null
        if (createBackup && target.exists()) {
            val backup = File(parent, "${target.name}.bak")
            try {
                target.copyTo(backup, overwrite = true)
                backupPath = backup.absolutePath
            } catch (e: IOException) {
                return@withContext SaveOutcome.Failure(
                    "无法创建备份 ${backup.name}，为避免覆盖失败已取消保存：${e.message}",
                )
            }
        }

        val temporary = File(parent, ".${target.name}.tmp")
        try {
            temporary.writeBytes(bytes)
        } catch (e: IOException) {
            runCatching { temporary.delete() }
            return@withContext SaveOutcome.Failure("写入临时文件失败：${e.message ?: "未知原因"}")
        }

        // Preserve the original modification semantics: replace the file rather than truncating
        // it in place, so a reader either sees the old file or the new one.
        val replaced = temporary.renameTo(target) ||
            runCatching {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)

        if (!replaced) {
            runCatching { temporary.delete() }
            return@withContext SaveOutcome.Failure("无法替换原文件（权限不足？）：${target.absolutePath}")
        }

        SaveOutcome.Success(
            bytesWritten = bytes.size.toLong(),
            backupPath = backupPath,
            encoding = document.encoding,
            lineEnding = document.lineEnding,
        )
    }

    /**
     * Convert [text]'s line endings to [ending].
     *
     * Kept as a member so callers do not have to import the file-level helper.
     */
    fun normaliseLineEndings(text: String, ending: LineEnding): String =
        io.rootexplorer.pro.core.text.normaliseLineEndings(text, ending)

    /**
     * Count matches of [query] in [text].
     *
     * Case sensitivity and whole-word matching are the caller's choice because both are offered
     * in the UI; this only does the counting, so the highlight and the "N matches" label use the
     * same rule.
     */
    fun countMatches(
        text: String,
        query: String,
        caseSensitive: Boolean = false,
    ): Int {
        if (query.isEmpty()) return 0
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(Regex.escape(query), options).findAll(text).count()
    }

    /**
     * Replace every match of [query] with [replacement].
     *
     * The replacement is inserted literally: `$1` backreferences are not interpreted, because a
     * user replacing text in a config file should not have to escape dollar signs.
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        caseSensitive: Boolean = false,
    ): ReplaceResult {
        if (query.isEmpty()) return ReplaceResult(text, 0)
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = Regex(Regex.escape(query), options)
        val count = regex.findAll(text).count()
        if (count == 0) return ReplaceResult(text, 0)
        return ReplaceResult(regex.replace(text) { replacement }, count)
    }

    /**
     * Decide whether [bytes] look binary.
     *
     * A NUL byte in the first block is decisive: no text format this editor supports contains
     * one. A high ratio of control characters is the secondary signal, which catches binaries
     * that happen to have no NUL early on.
     */
    fun sniffBinary(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val sample = bytes.copyOfRange(0, minOf(bytes.size, BINARY_SNIFF_BYTES))

        if (sample.any { it == 0.toByte() }) {
            return "文件中包含空字节（NUL），看起来是二进制文件。"
        }

        var controlCount = 0
        for (byte in sample) {
            val value = byte.toInt() and 0xFF
            // Allow tab, LF, CR, form feed and the printable ranges; anything else in the C0
            // range is a control character.
            val isAllowedControl = value == 0x09 || value == 0x0A || value == 0x0D || value == 0x0C
            if (value < 0x20 && !isAllowedControl) controlCount++
        }

        return if (controlCount * 100 / sample.size > BINARY_CONTROL_PERCENT) {
            "文件中约 ${controlCount * 100 / sample.size}% 的字节是控制字符，看起来是二进制文件。"
        } else {
            null
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String? = try {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    }

    private fun charsetFor(name: String): java.nio.charset.Charset = charsetForName(name)

    companion object {
        /** 8 MiB: large enough for a config or a log excerpt, small enough to edit smoothly. */
        const val DEFAULT_MAX_EDITABLE_BYTES = 8L * 1024 * 1024

        private const val BINARY_SNIFF_BYTES = 8192

        /** More than this share of control bytes means it is not text. */
        private const val BINARY_CONTROL_PERCENT = 10
    }
}

/** The outcome of a save. */
sealed interface SaveOutcome {
    data class Success(
        val bytesWritten: Long,
        val backupPath: String?,
        val encoding: String,
        val lineEnding: LineEnding,
    ) : SaveOutcome

    data class Failure(val reason: String) : SaveOutcome
}

/** Result of a replace-all, with how many replacements were made. */
data class ReplaceResult(val text: String, val count: Int)
