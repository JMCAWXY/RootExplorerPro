package io.rootexplorer.pro.core.filesystem

/**
 * Why reading a path failed.
 *
 * The prompt is explicit that every failure must not be rendered as an empty directory, so
 * errors are modelled as distinct cases and each one produces its own message in the UI.
 */
sealed interface FsError {

    /** Human-readable one-line explanation, shown in the file list and in dialogs. */
    val message: String

    /** The path does not exist (any more). */
    data class NotFound(val path: String) : FsError {
        override val message: String get() = "路径不存在：$path"
    }

    /** The path exists but this process may not read it. */
    data class PermissionDenied(val path: String, val detail: String? = null) : FsError {
        override val message: String
            get() = buildString {
                append("权限不足，无法访问：").append(path)
                if (!detail.isNullOrBlank()) append("（").append(detail).append("）")
            }
    }

    /** Reading needed root and root is not available to us. */
    data class RootRequired(val path: String) : FsError {
        override val message: String get() = "访问 $path 需要 Root 权限"
    }

    /** The target is not a directory (e.g. the user navigated into a regular file). */
    data class NotADirectory(val path: String) : FsError {
        override val message: String get() = "不是目录：$path"
    }

    /** The underlying filesystem is mounted read-only. */
    data class ReadOnlyFileSystem(val path: String) : FsError {
        override val message: String get() = "文件系统为只读，无法写入：$path"
    }

    /** The filesystem reported an I/O error. */
    data class Io(val path: String, val detail: String) : FsError {
        override val message: String get() = "文件系统错误：$path（$detail）"
    }

    /** A symbolic link could not be resolved, or resolves in a loop. */
    data class SymlinkLoop(val path: String) : FsError {
        override val message: String get() = "符号链接无法解析或存在循环：$path"
    }

    /** Anything else, with the raw detail preserved. */
    data class Unknown(val path: String, val detail: String) : FsError {
        override val message: String get() = "无法读取：$path（$detail）"
    }
}

/** The successful result of listing a directory. */
data class DirectoryListing(
    /** The directory that was listed, normalised. */
    val path: String,
    /** Entries in filesystem order; sorting is applied by the caller. */
    val entries: List<FileEntry>,
    /**
     * True when the listing came from a root shell rather than the app's own process.
     * The UI shows this so the user knows why otherwise-unreadable paths are visible.
     */
    val viaRoot: Boolean,
    /** Non-fatal problems encountered while filling in entry details. */
    val warnings: List<String> = emptyList(),
)

/** Result of listing a directory: either entries or a specific failure. */
sealed interface ListingResult {
    data class Success(val listing: DirectoryListing) : ListingResult
    data class Failure(val error: FsError) : ListingResult
}

/** Capacity figures for the filesystem that holds a given path. */
data class StorageStats(
    val totalBytes: Long,
    val freeBytes: Long,
    /** Bytes available to this process; on Android this is the better "free" number. */
    val availableBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
}

/** Result of asking for storage capacity: either real numbers or a reason there are none. */
sealed interface StorageResult {
    data class Success(val stats: StorageStats) : StorageResult
    data class Failure(val error: FsError) : StorageResult
}

/** Detailed attributes of a single path, used by the properties dialog. */
data class PathStat(
    val entry: FileEntry,
    /** Owner/group names that could not be mapped from uid/gid stay null and render as "未知". */
    val ownerName: String?,
    val groupName: String?,
    /** Access time when the filesystem reports it, else null. */
    val lastAccessMillis: Long?,
    /** Creation time when the filesystem reports it, else null. */
    val creationMillis: Long?,
    /** Number of hard links, when known. */
    val linkCount: Int?,
    /** Filesystem block size in bytes, when known. */
    val blockSize: Long?,
    /** True when at least one field could not be read; the dialog marks those rows. */
    val partial: Boolean,
)

/** Result of a single-path stat request. */
sealed interface StatResult {
    data class Success(val stat: PathStat) : StatResult
    data class Failure(val error: FsError) : StatResult
}

/**
 * A device's storage volume as reported by the platform.
 *
 * [path] is null when the volume has no browsable filesystem path, in which case the UI
 * must not pretend it can open it.
 */
data class StorageVolumeInfo(
    val id: String,
    val label: String,
    val path: String?,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val isEmulated: Boolean,
    val isMounted: Boolean,
    val stats: StorageStats?,
)
