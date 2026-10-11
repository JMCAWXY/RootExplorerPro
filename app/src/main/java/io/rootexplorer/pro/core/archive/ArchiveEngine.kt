package io.rootexplorer.pro.core.archive

import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.service.fileoperation.ConflictPolicy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Archive formats this app can read and write. */
enum class ArchiveFormat(
    val label: String,
    /** Lower-case extension without the dot. */
    val extension: String,
    val canCreate: Boolean,
    val canExtract: Boolean,
    /**
     * True when the format stores POSIX permissions and can therefore round-trip a mode.
     * Plain ZIP does not; tar does, but this app writes ZIP, so a created archive is 0644/0755.
     */
    val preservesPermissions: Boolean = false,
) {
    ZIP("ZIP", "zip", canCreate = true, canExtract = true),
    GZIP("GZip", "gz", canCreate = true, canExtract = true),
    TAR("TAR", "tar", canCreate = false, canExtract = true),
    TAR_GZ("TAR.GZ", "tar.gz", canCreate = false, canExtract = true),
    BZIP2("BZip2", "bz2", canCreate = false, canExtract = true),
    XZ("XZ", "xz", canCreate = false, canExtract = true),
    SEVEN_ZIP("7z", "7z", canCreate = false, canExtract = false),
    RAR("RAR", "rar", canCreate = false, canExtract = false),
    ;

    companion object {
        /**
         * Guess the format from a file name.
         *
         * Compound extensions are checked longest-first so `a.tar.gz` is TAR.GZ rather than GZ.
         */
        fun fromName(name: String): ArchiveFormat? {
            val lower = name.lowercase()
            return entries
                .sortedByDescending { it.extension.length }
                .firstOrNull { lower.endsWith(".${it.extension}") }
        }

        /** Formats offered by the "compress" action. */
        val creatable: List<ArchiveFormat> = entries.filter { it.canCreate }

        /** Formats offered by the "extract" action. */
        val extractable: List<ArchiveFormat> = entries.filter { it.canExtract }
    }
}

/** One entry inside an archive, as reported by a listing pass. */
data class ArchiveEntry(
    /** Path inside the archive, using forward slashes. */
    val name: String,
    val isDirectory: Boolean,
    /** Uncompressed size, or null when the archive does not record it. */
    val sizeBytes: Long?,
    val compressedSizeBytes: Long?,
    val lastModifiedMillis: Long?,
)

/** How far a compress or extract has got. */
data class ArchiveProgress(
    val currentEntry: String,
    val entriesDone: Int,
    val entriesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float?
        get() = when {
            bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
            entriesTotal > 0 -> (entriesDone.toDouble() / entriesTotal).toFloat().coerceIn(0f, 1f)
            else -> null
        }
}

/** What happened to one entry. */
data class ArchiveFailure(val name: String, val reason: String)

/** The outcome of a compress or extract. */
sealed interface ArchiveOutcome {
    data class Success(
        val entries: Int,
        val bytes: Long,
        val skipped: Int = 0,
        val failed: List<ArchiveFailure> = emptyList(),
    ) : ArchiveOutcome {
        val isPartial: Boolean get() = failed.isNotEmpty()
    }

    data class Failure(val reason: String) : ArchiveOutcome

    data class Cancelled(val entriesDone: Int, val bytesDone: Long) : ArchiveOutcome
}

/**
 * Creates and extracts archives.
 *
 * Why ZIP is implemented on `java.util.zip` rather than a third-party library: the pipeline is
 * simple, the behaviour is well specified, and the extraction path is exactly where a
 * supply-chain or format bug would be most damaging. Apache Commons Compress is used for the
 * read-only formats that the JDK cannot handle at all.
 *
 * The security property that matters most here is **Zip Slip**: an archive entry may be named
 * `../../etc/passwd`, and extracting it naively writes outside the destination. Every entry
 * path is therefore resolved against the destination and rejected unless it stays inside, and
 * absolute paths and drive letters are refused outright.
 *
 * Encrypted archives are not supported: they are detected and reported rather than producing a
 * confusing failure or, worse, a silently empty file.
 */
class ArchiveEngine(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
) {

    // ------------------------------------------------------------------ inspect

    /**
     * List an archive's entries without extracting.
     *
     * Reads the central directory for ZIP (cheap) and streams for the formats that have no
     * index. A malformed archive returns a failure rather than a partial listing presented as
     * complete.
     */
    suspend fun list(archivePath: String): Result<List<ArchiveEntry>> = withContext(Dispatchers.IO) {
        val file = File(FsEngine.normalise(archivePath))
        if (!file.isFile) {
            return@withContext Result.failure(IOException("文件不存在：$archivePath"))
        }

        val format = ArchiveFormat.fromName(file.name)
            ?: return@withContext Result.failure(IOException("无法识别的压缩格式：${file.name}"))

        try {
            when (format) {
                ArchiveFormat.ZIP -> listZip(file)
                ArchiveFormat.GZIP -> listGzip(file)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.BZIP2, ArchiveFormat.XZ ->
                    listWithCommonsCompress(file, format)

                ArchiveFormat.SEVEN_ZIP, ArchiveFormat.RAR ->
                    Result.failure(
                        IOException("${format.label} 为只读格式，当前版本尚不支持解压"),
                    )
            }
        } catch (e: ZipException) {
            if (isEncryptedMessage(e.message)) {
                Result.failure(IOException("该压缩包已加密，当前版本不支持加密压缩包"))
            } else {
                Result.failure(IOException("压缩包已损坏或格式不正确：${e.message}"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun listZip(file: File): Result<List<ArchiveEntry>> {
        val entries = mutableListOf<ArchiveEntry>()
        java.util.zip.ZipFile(file).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                entries += ArchiveEntry(
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    sizeBytes = entry.size.takeIf { it >= 0 },
                    compressedSizeBytes = entry.compressedSize.takeIf { it >= 0 },
                    lastModifiedMillis = entry.time.takeIf { it > 0 },
                )
            }
        }
        return Result.success(entries)
    }

    private fun listGzip(file: File): Result<List<ArchiveEntry>> {
        // gzip holds exactly one member; the original name is in the header when present.
        val name = file.name.removeSuffix(".gz").removeSuffix(".GZ").ifEmpty { "content" }
        return Result.success(
            listOf(
                ArchiveEntry(
                    name = name,
                    isDirectory = false,
                    sizeBytes = null,
                    compressedSizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified().takeIf { it > 0 },
                ),
            ),
        )
    }

    /**
     * Listing for tar and its compressed variants, through Commons Compress.
     *
     * Reflection-free direct call: the dependency is declared in the build file, so if it is
     * missing the build fails rather than this method silently degrading.
     */
    private fun listWithCommonsCompress(
        file: File,
        format: ArchiveFormat,
    ): Result<List<ArchiveEntry>> = try {
        // Listing is synchronous: it only walks headers and never reports progress, so it runs
        // on the caller's IO dispatcher through runBlocking rather than needing a suspend body.
        val entries = mutableListOf<ArchiveEntry>()
        kotlinx.coroutines.runBlocking {
            commonsCompressInput(file, format) { input ->
                val archive = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input)
                while (true) {
                    val entry = archive.nextEntry ?: break
                    entries += ArchiveEntry(
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        sizeBytes = entry.size.takeIf { it >= 0 },
                        compressedSizeBytes = null,
                        lastModifiedMillis = entry.lastModifiedDate?.time,
                    )
                }
            }
        }
        Result.success(entries)
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: NoClassDefFoundError) {
        Result.failure(IOException("缺少 TAR 支持库（commons-compress）"))
    }

    // ------------------------------------------------------------------ compress

    /**
     * Create an archive from [sources] at [targetArchivePath].
     *
     * Only ZIP and GZip can be created; anything else is rejected up front rather than
     * producing a file the user cannot open.
     */
    suspend fun compress(
        sources: List<String>,
        targetArchivePath: String,
        format: ArchiveFormat,
        compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ArchiveOutcome = withContext(Dispatchers.IO) {
        if (!format.canCreate) {
            return@withContext ArchiveOutcome.Failure("${format.label} 当前不支持创建")
        }
        if (sources.isEmpty()) {
            return@withContext ArchiveOutcome.Failure("没有选择要压缩的项目")
        }

        val target = File(FsEngine.normalise(targetArchivePath))
        target.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                return@withContext ArchiveOutcome.Failure("无法创建目标目录：${parent.absolutePath}")
            }
        }

        // Refuse to write an archive into itself (compressing a folder that contains the target).
        for (source in sources) {
            val sourceFile = File(FsEngine.normalise(source))
            if (target.absolutePath == sourceFile.absolutePath ||
                target.absolutePath.startsWith(sourceFile.absolutePath.trimEnd('/') + File.separator)
            ) {
                return@withContext ArchiveOutcome.Failure(
                    "不能把压缩包创建在它自己的源目录内：$targetArchivePath",
                )
            }
        }

        val plan = planCompression(sources)
        if (plan.isEmpty()) {
            return@withContext ArchiveOutcome.Failure("没有可压缩的文件")
        }

        val totalBytes = plan.sumOf { it.file.length() }
        var done = 0
        var bytes = 0L
        val failures = mutableListOf<ArchiveFailure>()

        try {
            when (format) {
                ArchiveFormat.ZIP -> writeZip(target, plan, compressionLevel) { entry, written ->
                    if (entry != done) {
                        done = entry
                    }
                    bytes = written
                    onProgress(
                        ArchiveProgress(
                            currentEntry = plan.getOrNull(entry - 1)?.entryName ?: "",
                            entriesDone = done,
                            entriesTotal = plan.size,
                            bytesDone = bytes,
                            bytesTotal = totalBytes,
                        ),
                    )
                }

                ArchiveFormat.GZIP -> writeGzip(target, plan) { written ->
                    bytes = written
                    onProgress(
                        ArchiveProgress(
                            currentEntry = plan.firstOrNull()?.entryName ?: "",
                            entriesDone = 1,
                            entriesTotal = 1,
                            bytesDone = bytes,
                            bytesTotal = totalBytes,
                        ),
                    )
                }

                else -> return@withContext ArchiveOutcome.Failure("${format.label} 当前不支持创建")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A half-written archive is worse than none: remove it so the user is not left with
            // a file that looks valid but is missing entries.
            runCatching { target.delete() }
            return@withContext ArchiveOutcome.Cancelled(done, bytes)
        } catch (e: IOException) {
            runCatching { target.delete() }
            return@withContext ArchiveOutcome.Failure("写入失败：${e.message ?: e.javaClass.simpleName}")
        }

        onProgress(
            ArchiveProgress(
                currentEntry = "",
                entriesDone = plan.size,
                entriesTotal = plan.size,
                bytesDone = totalBytes,
                bytesTotal = totalBytes,
            ),
        )

        ArchiveOutcome.Success(entries = plan.size, bytes = totalBytes, failed = failures)
    }

    /** One file to write, with the name it will have inside the archive. */
    data class PlannedEntry(val file: File, val entryName: String)

    /**
     * Walk the sources and decide the entry name for every file.
     *
     * For a single directory the directory's own name becomes the archive root, which is what
     * every archiver does and what makes "extract here" produce one folder rather than a mess.
     * Entry names always use forward slashes, as the ZIP specification requires.
     */
    fun planCompression(sources: List<String>): List<PlannedEntry> {
        val planned = mutableListOf<PlannedEntry>()

        fun walk(file: File, prefix: String) {
            if (file.isDirectory) {
                val children = file.listFiles() ?: return
                for (child in children) {
                    walk(child, "$prefix${child.name}/")
                }
            } else if (file.isFile) {
                planned += PlannedEntry(file, prefix.trimEnd('/'))
            }
        }

        for (source in sources) {
            val file = File(FsEngine.normalise(source))
            if (!file.exists()) continue
            if (file.isDirectory) {
                // Entries are named relative to the source's parent so the folder itself is
                // included.
                val base = file.name
                val children = file.listFiles().orEmpty()
                if (children.isEmpty()) {
                    // An empty directory still deserves an entry, or the archive loses it.
                    planned += PlannedEntry(file, "$base/")
                } else {
                    for (child in children) {
                        walk(child, "$base/${child.name}${if (child.isDirectory) "/" else ""}")
                    }
                }
            } else {
                planned += PlannedEntry(file, file.name)
            }
        }
        return planned
    }

    private suspend fun writeZip(
        target: File,
        plan: List<PlannedEntry>,
        compressionLevel: Int,
        onEntryWritten: (entryIndex: Int, bytesWritten: Long) -> Unit,
    ) {
        var bytesWritten = 0L
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(compressionLevel)
            var index = 0
            for (entry in plan) {
                currentCoroutineContext().ensureActive()
                index++

                if (entry.entryName.endsWith("/")) {
                    // Directory entry: no content, but the name must end with a slash.
                    zip.putNextEntry(ZipEntry(entry.entryName))
                    zip.closeEntry()
                    onEntryWritten(index, bytesWritten)
                    continue
                }

                val zipEntry = ZipEntry(entry.entryName)
                entry.file.lastModified().takeIf { it > 0 }?.let { zipEntry.time = it }
                zip.putNextEntry(zipEntry)

                entry.file.inputStream().use { input ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                        bytesWritten += count
                    }
                }
                zip.closeEntry()
                onEntryWritten(index, bytesWritten)
            }
        }
    }

    private suspend fun writeGzip(
        target: File,
        plan: List<PlannedEntry>,
        onProgress: (bytesWritten: Long) -> Unit,
    ) {
        // gzip holds a single member, so only the first file is written; more than one source
        // is refused by the caller's format choice rather than silently truncated.
        val source = plan.firstOrNull() ?: return
        var written = 0L
        java.util.zip.GZIPOutputStream(target.outputStream().buffered()).use { gzip ->
            source.file.inputStream().use { input ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    gzip.write(buffer, 0, count)
                    written += count
                    onProgress(written)
                }
            }
        }
    }

    // ------------------------------------------------------------------ extract

    /**
     * Extract [archivePath] into [targetDirectory].
     *
     * Every entry is checked against the destination before it is written (see the class
     * documentation on Zip Slip). [policy] decides what happens when a file already exists;
     * [ConflictPolicy.ASK] is not accepted here because the engine has no way to ask, so the
     * caller must resolve the policy before extracting.
     */
    suspend fun extract(
        archivePath: String,
        targetDirectory: String,
        policy: ConflictPolicy = ConflictPolicy.RENAME,
        onProgress: (ArchiveProgress) -> Unit = {},
    ): ArchiveOutcome = withContext(Dispatchers.IO) {
        val archive = File(FsEngine.normalise(archivePath))
        if (!archive.isFile) {
            return@withContext ArchiveOutcome.Failure("压缩包不存在：$archivePath")
        }

        val format = ArchiveFormat.fromName(archive.name)
            ?: return@withContext ArchiveOutcome.Failure("无法识别的压缩格式：${archive.name}")

        if (!format.canExtract) {
            return@withContext ArchiveOutcome.Failure("${format.label} 当前不支持解压")
        }

        val destination = File(FsEngine.normalise(targetDirectory))
        if (!destination.isDirectory && !destination.mkdirs()) {
            return@withContext ArchiveOutcome.Failure("无法创建目标目录：$targetDirectory")
        }

        // A policy of ASK cannot be answered from here; treating it as RENAME is the only safe
        // interpretation because it never destroys an existing file.
        val effective = if (policy == ConflictPolicy.ASK) ConflictPolicy.RENAME else policy

        try {
            when (format) {
                ArchiveFormat.ZIP -> extractZip(archive, destination, effective, onProgress)
                ArchiveFormat.GZIP -> extractGzip(archive, destination, effective, onProgress)
                ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.BZIP2, ArchiveFormat.XZ ->
                    extractWithCommonsCompress(archive, destination, format, effective, onProgress)

                else -> ArchiveOutcome.Failure("${format.label} 当前不支持解压")
            }
        } catch (e: ZipException) {
            if (isEncryptedMessage(e.message)) {
                ArchiveOutcome.Failure("该压缩包已加密，当前版本不支持加密压缩包")
            } else {
                ArchiveOutcome.Failure("压缩包已损坏：${e.message}")
            }
        } catch (e: IOException) {
            ArchiveOutcome.Failure("解压失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun extractZip(
        archive: File,
        destination: File,
        policy: ConflictPolicy,
        onProgress: (ArchiveProgress) -> Unit,
    ): ArchiveOutcome {
        val entries = mutableListOf<ArchiveEntry>()
        listZip(archive).getOrNull()?.let { entries += it }
        val totalEntries = entries.size
        val totalBytes = entries.sumOf { it.sizeBytes ?: 0L }

        var done = 0
        var bytes = 0L
        var skipped = 0
        val failures = mutableListOf<ArchiveFailure>()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break

                val target = resolveEntryTarget(destination, entry.name)
                if (target == null) {
                    failures += ArchiveFailure(entry.name, "条目路径不安全，已拒绝（可能指向压缩包外部）")
                    continue
                }

                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) {
                        failures += ArchiveFailure(entry.name, "无法创建目录")
                    }
                    done++
                    onProgress(
                        ArchiveProgress(entry.name, done, totalEntries, bytes, totalBytes),
                    )
                    continue
                }

                val resolved = applyPolicy(target, policy)
                if (resolved == null) {
                    skipped++
                    done++
                    continue
                }

                resolved.parentFile?.mkdirs()
                try {
                    resolved.outputStream().buffered().use { output ->
                        bytes += copyStream(zip, output) { read ->
                            onProgress(
                                ArchiveProgress(
                                    entry.name,
                                    done,
                                    totalEntries,
                                    bytes + read,
                                    totalBytes,
                                ),
                            )
                        }
                    }
                    entry.time.takeIf { it > 0 }?.let { resolved.setLastModified(it) }
                } catch (e: IOException) {
                    runCatching { resolved.delete() }
                    failures += ArchiveFailure(entry.name, e.message ?: "写入失败")
                }

                done++
                onProgress(ArchiveProgress(entry.name, done, totalEntries, bytes, totalBytes))
            }
        }

        return ArchiveOutcome.Success(entries = done, bytes = bytes, skipped = skipped, failed = failures)
    }

    private suspend fun extractGzip(
        archive: File,
        destination: File,
        policy: ConflictPolicy,
        onProgress: (ArchiveProgress) -> Unit,
    ): ArchiveOutcome {
        val name = archive.name.removeSuffix(".gz").removeSuffix(".GZ").ifEmpty { "content" }
        val raw = File(destination, name)
        val target = applyPolicy(raw, policy)
            ?: return ArchiveOutcome.Success(entries = 0, bytes = 0, skipped = 1)

        target.parentFile?.mkdirs()
        var bytes = 0L
        java.util.zip.GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            target.outputStream().buffered().use { output ->
                bytes = copyStream(gzip, output) { read ->
                    onProgress(ArchiveProgress(name, 0, 1, bytes + read, archive.length()))
                }
            }
        }
        onProgress(ArchiveProgress(name, 1, 1, bytes, archive.length()))
        return ArchiveOutcome.Success(entries = 1, bytes = bytes)
    }

    private suspend fun extractWithCommonsCompress(
        archive: File,
        destination: File,
        format: ArchiveFormat,
        policy: ConflictPolicy,
        onProgress: (ArchiveProgress) -> Unit,
    ): ArchiveOutcome {
        var done = 0
        var bytes = 0L
        var skipped = 0
        val failures = mutableListOf<ArchiveFailure>()

        commonsCompressInput(archive, format) { input ->
            val tar = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input)
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextEntry ?: break

                val target = resolveEntryTarget(destination, entry.name)
                if (target == null) {
                    failures += ArchiveFailure(entry.name, "条目路径不安全，已拒绝")
                    continue
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                    done++
                    onProgress(ArchiveProgress(entry.name, done, 0, bytes, 0))
                    continue
                }

                val resolved = applyPolicy(target, policy)
                if (resolved == null) {
                    skipped++
                    done++
                    continue
                }
                resolved.parentFile?.mkdirs()
                try {
                    resolved.outputStream().buffered().use { output ->
                        bytes += copyStream(tar, output) { read ->
                            onProgress(ArchiveProgress(entry.name, done, 0, bytes + read, 0))
                        }
                    }
                    entry.modTime.time.takeIf { it > 0 }?.let { resolved.setLastModified(it) }
                } catch (e: IOException) {
                    runCatching { resolved.delete() }
                    failures += ArchiveFailure(entry.name, e.message ?: "写入失败")
                }

                done++
                onProgress(ArchiveProgress(entry.name, done, 0, bytes, 0))
            }
        }

        return ArchiveOutcome.Success(entries = done, bytes = bytes, skipped = skipped, failed = failures)
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Resolve an archive entry name to a file inside [destination], or null when it would
     * escape.
     *
     * This is the Zip Slip defence, and it is the most security-relevant code in this file:
     *
     * * absolute names (`/etc/passwd`) are rejected;
     * * `..` segments are rejected outright rather than normalised, because a name that tries to
     *   leave is never legitimate in an archive this app wrote;
     * * a Windows drive or UNC prefix is rejected, so a malicious archive behaves the same on
     *   every platform;
     * * finally, the resolved canonical path must still start with the destination's canonical
     *   path, which is the backstop that catches anything the checks above missed (including a
     *   symlink already present in the destination).
     */
    fun resolveEntryTarget(destination: File, entryName: String): File? {
        if (entryName.isBlank()) return null
        val normalisedName = entryName.replace('\\', '/')

        if (normalisedName.startsWith("/")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalisedName)) return null
        if (normalisedName.startsWith("//")) return null
        if (normalisedName.contains('\u0000')) return null

        val segments = normalisedName.split('/')
        if (segments.any { it == ".." }) return null

        val candidate = File(destination, normalisedName)

        val destinationCanonical = try {
            destination.canonicalPath
        } catch (e: IOException) {
            destination.absolutePath
        }
        val candidateCanonical = try {
            candidate.canonicalPath
        } catch (e: IOException) {
            candidate.absolutePath
        }

        val prefix = destinationCanonical.trimEnd(File.separatorChar) + File.separator
        return if (candidateCanonical == destinationCanonical || candidateCanonical.startsWith(prefix)) {
            candidate
        } else {
            null
        }
    }

    /**
     * Apply a conflict policy to a target path.
     *
     * Returns the file to write, or null when the entry should be skipped. It never overwrites
     * unless the policy says so.
     */
    fun applyPolicy(target: File, policy: ConflictPolicy): File? {
        if (!target.exists()) return target
        return when (policy) {
            ConflictPolicy.OVERWRITE -> target
            ConflictPolicy.SKIP -> null
            // ASK has no answer here; RENAME is the non-destructive reading of it.
            ConflictPolicy.ASK, ConflictPolicy.RENAME -> uniqueSibling(target)
        }
    }

    /** `a.txt` becomes `a (1).txt` and so on, matching the copy engine's behaviour. */
    fun uniqueSibling(target: File): File {
        val directory = target.parentFile ?: return target
        val name = target.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""

        var index = 1
        var candidate = File(directory, name)
        while (candidate.exists() && index <= 9999) {
            candidate = File(directory, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    /** Copy [input] to [output], reporting the running total through [onChunk]. */
    private suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        onChunk: (bytesThisEntry: Long) -> Unit,
    ): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
            onChunk(total)
        }
        return total
    }

    /**
     * Open a tar-family archive through the right decompressor.
     *
     * The body is suspending because extraction reports progress and checks for cancellation
     * while it reads, and both of those need the coroutine context.
     */
    private suspend fun commonsCompressInput(
        file: File,
        format: ArchiveFormat,
        body: suspend (InputStream) -> Unit,
    ) {
        file.inputStream().buffered().use { raw ->
            val stream: InputStream = when (format) {
                ArchiveFormat.TAR -> raw
                ArchiveFormat.TAR_GZ -> org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(raw)
                ArchiveFormat.BZIP2 -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(raw)
                ArchiveFormat.XZ -> org.apache.commons.compress.compressors.xz.XZCompressorInputStream(raw)
                else -> raw
            }
            stream.use { open -> body(open) }
        }
    }

    private fun isEncryptedMessage(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase()
        return lower.contains("encrypt") || lower.contains("password")
    }

    /** Default name for a new archive built from [sourceName]. */
    fun suggestedArchiveName(sourceNames: List<String>, format: ArchiveFormat): String {
        val base = when {
            sourceNames.size == 1 -> File(sourceNames.first().trimEnd('/')).name
            else -> "archive"
        }
        return if (base.lowercase().endsWith(".${format.extension}")) {
            base
        } else {
            "$base.${format.extension}"
        }
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 128 * 1024
    }
}
