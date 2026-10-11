package io.rootexplorer.pro.service.fileoperation

import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.ShellQuote
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The outcome of a filesystem mutation.
 *
 * Operations report what actually happened. A command exit status is never treated as proof
 * of success: every mutating call re-reads the path afterwards and reports
 * [Success.verified] accordingly, and a failure carries the reason the user needs.
 */
sealed interface OperationResult {

    /** [verified] is true when the expected end state was observed on disk afterwards. */
    data class Success(val message: String, val verified: Boolean = true) : OperationResult

    data class Failure(val message: String, val cause: Throwable? = null) : OperationResult

    /** The operation is not implemented yet. The UI must show this rather than claim success. */
    data class NotImplemented(val message: String) : OperationResult
}

/**
 * Real filesystem mutations: create, rename, delete.
 *
 * P0 deliberately covers the operations that can be implemented correctly and verified on
 * both engines. Recursive copy/move/replace is a larger, separate piece of work (progress,
 * cancellation, conflict handling, partial-failure reporting) and is scheduled for P1; those
 * entry points return [OperationResult.NotImplemented] so the UI can disable them honestly
 * instead of pretending.
 */
class FileOperationsEngine(
    private val rootShell: RootShell,
    private val rootAvailable: () -> Boolean,
) {

    /**
     * Validate a new name for a file or directory.
     *
     * Returns null when the name is acceptable, or the reason it is not. The rules come from
     * what the filesystems actually reject: '/' and NUL can never appear in a name, and a
     * name of "." or ".." would silently mean something else entirely.
     */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "名称不能为空"
            trimmed == "." || trimmed == ".." -> "名称不能是 . 或 .."
            trimmed.contains('/') -> "名称不能包含 /"
            trimmed.contains('\u0000') -> "名称不能包含空字符"
            trimmed.length > 255 -> "名称过长（最多 255 个字符）"
            else -> null
        }
    }

    /**
     * Rename [path] to [newName] within the same directory.
     *
     * Returns a failure when the source does not exist, the name is invalid, or the target
     * already exists — we never silently overwrite, because losing a file to a typo is the
     * worst thing a file manager can do.
     */
    suspend fun rename(path: String, newName: String): OperationResult = withContext(Dispatchers.IO) {
        val source = File(FsEngine.normalise(path))
        validateName(newName)?.let { return@withContext OperationResult.Failure(it) }

        if (!source.exists()) {
            return@withContext OperationResult.Failure("源文件不存在：${source.absolutePath}")
        }

        val parent = source.parentFile
            ?: return@withContext OperationResult.Failure("无法确定上级目录：${source.absolutePath}")
        val target = File(parent, newName.trim())

        if (target.absolutePath == source.absolutePath) {
            return@withContext OperationResult.Success("名称未改变", verified = true)
        }
        if (target.exists()) {
            return@withContext OperationResult.Failure("目标名称已存在：${target.name}")
        }

        if (rootAvailable()) {
            val quoted = "${ShellQuote.quote(source.absolutePath)} ${ShellQuote.quote(target.absolutePath)}"
            val result = rootShell.runBlocking("mv -n $quoted 2>&1")
            val moved = target.exists() && !source.exists()
            return@withContext if (moved) {
                OperationResult.Success("已重命名为 ${target.name}", verified = true)
            } else {
                OperationResult.Failure(
                    "重命名失败：${result.combinedOutput.ifBlank { "mv 未生效（退出码 ${result.exitCode}）" }}",
                )
            }
        }

        val renamed = try {
            source.renameTo(target)
        } catch (e: SecurityException) {
            return@withContext OperationResult.Failure("没有权限重命名：${e.message ?: source.absolutePath}", e)
        }
        if (renamed && target.exists() && !source.exists()) {
            OperationResult.Success("已重命名为 ${target.name}", verified = true)
        } else {
            OperationResult.Failure("重命名失败：没有权限或文件被占用")
        }
    }

    /**
     * Delete [path].
     *
     * Directories are only removed when empty unless [recursive] is set, and the caller is
     * responsible for having confirmed that with the user.
     */
    suspend fun delete(path: String, recursive: Boolean = false): OperationResult =
        withContext(Dispatchers.IO) {
            val target = File(FsEngine.normalise(path))
            if (!target.exists() && !isSymlink(target)) {
                return@withContext OperationResult.Failure("文件不存在：${target.absolutePath}")
            }

            if (rootAvailable()) {
                // -f keeps a missing file from being an error; -r only when the user agreed.
                val flags = if (recursive) "-rf" else "-f"
                val command = "rm $flags ${ShellQuote.quote(target.absolutePath)} 2>&1"
                val result = rootShell.runBlocking(command)
                val gone = !target.exists() && !isSymlink(target)
                return@withContext if (gone) {
                    OperationResult.Success("已删除 ${target.name}", verified = true)
                } else {
                    OperationResult.Failure(
                        "删除失败：${result.combinedOutput.ifBlank { "rm 未生效（退出码 ${result.exitCode}）" }}",
                    )
                }
            }

            val deleted = try {
                if (recursive) target.deleteRecursively() else target.delete()
            } catch (e: SecurityException) {
                return@withContext OperationResult.Failure("没有权限删除：${e.message ?: target.absolutePath}", e)
            }

            if (deleted && !target.exists()) {
                OperationResult.Success("已删除 ${target.name}", verified = true)
            } else {
                OperationResult.Failure(
                    "删除失败：可能是权限不足，或目录非空（${target.absolutePath}）",
                )
            }
        }

    /** Create a new directory named [name] inside [parentPath]. */
    suspend fun createDirectory(parentPath: String, name: String): OperationResult =
        withContext(Dispatchers.IO) {
            validateName(name)?.let { return@withContext OperationResult.Failure(it) }
            val target = File(FsEngine.childOf(parentPath, name.trim()))

            if (target.exists()) {
                return@withContext OperationResult.Failure("已存在同名文件或文件夹：${target.name}")
            }

            val created = if (rootAvailable()) {
                val result = rootShell.runBlocking("mkdir -p ${ShellQuote.quote(target.absolutePath)} 2>&1")
                if (result.isSuccess || target.isDirectory) null else result.combinedOutput
            } else {
                if (target.mkdir()) null else "创建失败：没有权限或上级目录不存在"
            }

            if (created == null && target.isDirectory) {
                OperationResult.Success("已创建文件夹 ${target.name}", verified = true)
            } else {
                OperationResult.Failure("创建文件夹失败：${created ?: "目录未出现"}")
            }
        }

    /** Create an empty file named [name] inside [parentPath]. */
    suspend fun createFile(parentPath: String, name: String): OperationResult =
        withContext(Dispatchers.IO) {
            validateName(name)?.let { return@withContext OperationResult.Failure(it) }
            val target = File(FsEngine.childOf(parentPath, name.trim()))

            if (target.exists()) {
                return@withContext OperationResult.Failure("已存在同名文件或文件夹：${target.name}")
            }

            val error = try {
                if (target.createNewFile()) null else "创建失败：没有权限或上级目录不存在"
            } catch (e: IOException) {
                e.message ?: "创建失败"
            } catch (e: SecurityException) {
                e.message ?: "没有权限创建文件"
            }

            if (error == null && target.isFile) {
                OperationResult.Success("已创建文件 ${target.name}", verified = true)
            } else {
                OperationResult.Failure("创建文件失败：${error ?: "文件未出现"}")
            }
        }

    /** Copy is implemented by [FileOperationEngine]; see that class for the planning rules. */
    fun copyNotImplemented(): OperationResult = OperationResult.NotImplemented(
        "复制应通过 FileOperationEngine 执行",
    )

    /** Move is implemented by [FileOperationEngine]. */
    fun moveNotImplemented(): OperationResult = OperationResult.NotImplemented(
        "移动应通过 FileOperationEngine 执行",
    )

    private fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }
}
