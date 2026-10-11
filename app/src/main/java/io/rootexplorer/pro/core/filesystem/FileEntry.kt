package io.rootexplorer.pro.core.filesystem

import io.rootexplorer.pro.core.permissions.LinuxPermission

/** What a directory entry actually is on disk. */
enum class FileKind {
    DIRECTORY,
    REGULAR,
    SYMLINK,
    BLOCK_DEVICE,
    CHARACTER_DEVICE,
    FIFO,
    SOCKET,
    OTHER,
    ;

    /** The character `ls -l` prints in the first column. */
    val typeCharacter: Char
        get() = when (this) {
            DIRECTORY -> 'd'
            SYMLINK -> 'l'
            BLOCK_DEVICE -> 'b'
            CHARACTER_DEVICE -> 'c'
            FIFO -> 'p'
            SOCKET -> 's'
            REGULAR -> '-'
            OTHER -> '?'
        }

    companion object {
        /** Map the numeric type field of a `stat` result onto a [FileKind]. */
        fun fromStatType(statType: String): FileKind = when (statType.trim().lowercase()) {
            "directory", "dir" -> DIRECTORY
            "regular file", "regular", "file" -> REGULAR
            "symbolic link", "link" -> SYMLINK
            "block special file", "block device", "block" -> BLOCK_DEVICE
            "character special file", "character device", "character" -> CHARACTER_DEVICE
            "fifo", "fifo (named pipe)" -> FIFO
            "socket" -> SOCKET
            else -> OTHER
        }
    }
}

/**
 * One entry in a directory listing.
 *
 * Fields that the platform could not actually determine are left null rather than being
 * filled with a plausible-looking default, so the UI can say "unknown" instead of lying.
 */
data class FileEntry(
    val name: String,
    /** Fully resolved path of this entry, i.e. parent + name. */
    val path: String,
    val kind: FileKind,
    val sizeBytes: Long?,
    /** Last modification time in epoch milliseconds, or null when unknown. */
    val lastModifiedMillis: Long?,
    /** Permission bits including special bits, or null when unknown. */
    val permission: LinuxPermission?,
    /** Numeric owner id, or null when unknown. */
    val uid: Int?,
    /** Numeric group id, or null when unknown. */
    val gid: Int?,
    /** Resolved user name for [uid], or null when it could not be resolved. */
    val ownerName: String?,
    /** Resolved group name for [gid], or null when it could not be resolved. */
    val groupName: String?,
    /** For symbolic links: the raw link target, or null for other kinds. */
    val linkTarget: String?,
    /** True when [linkTarget] points at a directory. Null when not a link or unknown. */
    val linkTargetIsDirectory: Boolean?,
) {

    val isDirectory: Boolean get() = kind == FileKind.DIRECTORY
    val isSymlink: Boolean get() = kind == FileKind.SYMLINK

    /** A file whose name starts with '.', which is what "hidden" means on Linux. */
    val isHidden: Boolean get() = name.startsWith(".")

    /** Lowercase extension without the dot, or "" when the name has no extension. */
    val extension: String
        get() {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return ""
            return name.substring(dot + 1).lowercase()
        }
}
