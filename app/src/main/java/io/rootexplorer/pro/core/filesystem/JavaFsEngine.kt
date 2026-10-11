package io.rootexplorer.pro.core.filesystem

import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import io.rootexplorer.pro.core.permissions.LinuxPermission
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Filesystem access using this app's own process credentials.
 *
 * This engine works for shared storage and for any system path the app itself may read. It
 * reports what the kernel tells it and marks permission information as unknown when the
 * filesystem does not expose it (Android's FUSE layer over `/sdcard` does not), rather than
 * inventing a value.
 */
class JavaFsEngine : FsEngine {

    override val requiresRoot: Boolean = false

    override suspend fun list(path: String): ListingResult = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)
        val dir = File(normalised)

        if (!dir.exists()) {
            // Distinguish "does not exist" from "cannot see it": on Android, exists() is
            // false both for a missing path and for a path inside a directory we may not
            // search. Walking up the parents tells us which one it is.
            val blockedAncestor = firstUnreadableAncestor(normalised)
            return@withContext if (blockedAncestor != null) {
                ListingResult.Failure(FsError.PermissionDenied(normalised, "无法进入上级目录 $blockedAncestor"))
            } else {
                ListingResult.Failure(FsError.NotFound(normalised))
            }
        }
        if (!dir.isDirectory) {
            return@withContext ListingResult.Failure(FsError.NotADirectory(normalised))
        }

        val names = dir.list()
        if (names == null) {
            // list() returns null when the directory cannot be read.
            return@withContext ListingResult.Failure(FsError.PermissionDenied(normalised))
        }

        val warnings = mutableListOf<String>()
        val entries = ArrayList<FileEntry>(names.size)
        for (name in names) {
            val child = File(dir, name)
            entries += describe(child, parentPath = normalised, warnings = warnings)
        }

        ListingResult.Success(
            DirectoryListing(
                path = normalised,
                entries = entries,
                viaRoot = false,
                warnings = warnings,
            ),
        )
    }

    /**
     * Walk the tree with this process's own credentials.
     *
     * Enumeration is per directory via `File.listFiles()`, which is cheaper than the root
     * engine's process-per-path approach and needs no shell. Symlinks are reported but not
     * followed; a `visited` set of canonical paths is the second line of defence against a
     * cycle that a bind mount or a hardlinked directory could otherwise create.
     */
    override suspend fun scan(
        path: String,
        visit: suspend (FileEntry) -> Boolean,
        onError: (String, FsError) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val root = FsEngine.normalise(path)
        val visited = HashSet<String>()
        scanDirectory(File(root), root, visit, onError, visited, depth = 0)
    }

    private suspend fun scanDirectory(
        directory: File,
        directoryPath: String,
        visit: suspend (FileEntry) -> Boolean,
        onError: (String, FsError) -> Unit,
        visited: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_SCAN_DEPTH) return

        val canonical = try {
            directory.canonicalPath
        } catch (e: IOException) {
            directory.absolutePath
        }
        if (!visited.add(canonical)) return

        val names = directory.list()
        if (names == null) {
            onError(directoryPath, FsError.PermissionDenied(directoryPath))
            return
        }

        val warnings = mutableListOf<String>()
        for (name in names) {
            // Cancellation is cooperative: the walker checks between entries so a cancelled
            // search stops promptly instead of after the whole tree.
            currentCoroutineContext().ensureActive()

            val child = File(directory, name)
            val entry = describe(child, parentPath = directoryPath, warnings = warnings)

            if (!visit(entry)) return

            // Reported links are not descended into: following them is how a scan leaves the
            // subtree the user chose.
            if (entry.isDirectory && !entry.isSymlink) {
                scanDirectory(child, entry.path, visit, onError, visited, depth + 1)
            }
        }
    }

    override suspend fun stat(path: String): StatResult = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)
        val file = File(normalised)
        if (!file.exists() && !isSymlink(file)) {
            val blocked = firstUnreadableAncestor(normalised)
            return@withContext if (blocked != null) {
                StatResult.Failure(FsError.PermissionDenied(normalised, "无法进入上级目录 $blocked"))
            } else {
                StatResult.Failure(FsError.NotFound(normalised))
            }
        }

        val warnings = mutableListOf<String>()
        val entry = describe(file, parentPath = FsEngine.parentOf(normalised) ?: "/", warnings = warnings)

        val (access, creation, links, blockSize, partial) = readExtraAttributes(file, warnings)

        StatResult.Success(
            PathStat(
                entry = entry,
                ownerName = entry.ownerName,
                groupName = entry.groupName,
                lastAccessMillis = access,
                creationMillis = creation,
                linkCount = links,
                blockSize = blockSize,
                partial = partial || warnings.isNotEmpty(),
            ),
        )
    }

    override suspend fun storage(path: String): StorageResult = withContext(Dispatchers.IO) {
        val normalised = FsEngine.normalise(path)
        // StatFs needs an existing path; walk up until one exists so the caller still gets
        // the capacity of the volume that would hold the path.
        var probe = File(normalised)
        var guard = 0
        while (!probe.exists() && guard++ < 32) {
            probe = probe.parentFile ?: break
        }
        if (!probe.exists()) {
            return@withContext StorageResult.Failure(FsError.NotFound(normalised))
        }
        try {
            val stat = StatFs(probe.absolutePath)
            StorageResult.Success(
                StorageStats(
                    totalBytes = stat.totalBytes,
                    freeBytes = stat.freeBytes,
                    availableBytes = stat.availableBytes,
                ),
            )
        } catch (e: IllegalArgumentException) {
            StorageResult.Failure(FsError.Io(normalised, e.message ?: "StatFs 失败"))
        } catch (e: IOException) {
            StorageResult.Failure(FsError.Io(normalised, e.message ?: "StatFs 失败"))
        }
    }

    override suspend fun readLink(path: String): String? = withContext(Dispatchers.IO) {
        val file = File(FsEngine.normalise(path))
        if (!isSymlink(file)) return@withContext null
        try {
            Os.readlink(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build a [FileEntry] for [file].
     *
     * Permission bits come from the OS when the filesystem exposes them over the POSIX
     * interface. On filesystems that do not (shared storage through FUSE), the permission
     * stays null and the UI shows "未知" instead of a made-up mode.
     */
    private fun describe(
        file: File,
        parentPath: String,
        warnings: MutableList<String>,
    ): FileEntry {
        val path = FsEngine.childOf(parentPath, file.name)
        val nioPath = file.toPath()

        val symlink = isSymlink(file)
        val kind = when {
            symlink -> FileKind.SYMLINK
            file.isDirectory -> FileKind.DIRECTORY
            file.isFile -> FileKind.REGULAR
            else -> FileKind.OTHER
        }

        var permission: LinuxPermission? = null
        var uid: Int? = null
        var gid: Int? = null
        var ownerName: String? = null
        var groupName: String? = null

        try {
            val attrs = Files.readAttributes(nioPath, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            permission = attrs.permissions().toLinuxPermission()
            ownerName = attrs.owner()?.name
            groupName = attrs.group()?.name
        } catch (e: UnsupportedOperationException) {
            // Filesystem does not expose POSIX attributes (typical for /sdcard).
        } catch (e: IOException) {
            warnings += "${file.name}：无法读取权限信息"
        } catch (e: SecurityException) {
            warnings += "${file.name}：无权读取权限信息"
        }

        val (resolvedUid, resolvedGid) = readNumericOwner(nioPath)
        if (uid == null) uid = resolvedUid
        if (gid == null) gid = resolvedGid

        var linkTarget: String? = null
        var linkTargetIsDirectory: Boolean? = null
        if (symlink) {
            linkTarget = try {
                Os.readlink(file.absolutePath)
            } catch (e: Exception) {
                null
            }
            if (linkTarget != null) {
                val resolved = File(parentPath, linkTarget)
                linkTargetIsDirectory = resolved.isDirectory
            }
        }

        val size = when {
            kind == FileKind.DIRECTORY -> null
            else -> file.length().takeIf { it >= 0 }
        }

        return FileEntry(
            name = file.name,
            path = path,
            kind = kind,
            sizeBytes = size,
            lastModifiedMillis = file.lastModified().takeIf { it > 0L },
            permission = permission,
            uid = uid,
            gid = gid,
            ownerName = ownerName,
            groupName = groupName,
            linkTarget = linkTarget,
            linkTargetIsDirectory = linkTargetIsDirectory,
        )
    }

    /** Read atime/ctime/link count when the filesystem provides them. */
    private fun readExtraAttributes(
        file: File,
        warnings: MutableList<String>,
    ): ExtraAttributes {
        val nioPath: Path = file.toPath()
        var access: Long? = null
        var creation: Long? = null
        var links: Int? = null
        var blockSize: Long? = null
        var partial = false
        try {
            val attrs = Files.readAttributes(nioPath, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            access = attrs.lastAccessTime()?.toMillis()
            creation = attrs.creationTime()?.toMillis()
        } catch (e: UnsupportedOperationException) {
            partial = true
        } catch (e: IOException) {
            partial = true
        }
        try {
            val basic = Files.readAttributes(nioPath, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (creation == null) creation = basic.creationTime()?.toMillis()
        } catch (e: IOException) {
            partial = true
        }
        return ExtraAttributes(access, creation, links, blockSize, partial)
    }

    private data class ExtraAttributes(
        val lastAccessMillis: Long?,
        val creationMillis: Long?,
        val linkCount: Int?,
        val blockSize: Long?,
        val partial: Boolean,
    )

    /** True when [file] is a symbolic link (File.isDirectory/isFile follow links). */
    private fun isSymlink(file: File): Boolean = try {
        Files.isSymbolicLink(file.toPath())
    } catch (e: Exception) {
        false
    }

    /**
     * Find the first ancestor of [path] that cannot be searched, so "not found" and
     * "permission denied" are not confused. Returns null when every ancestor is readable.
     */
    private fun firstUnreadableAncestor(path: String): String? {
        var current = File(path).parentFile
        var guard = 0
        while (current != null && guard++ < 64) {
            if (current.exists() && !current.canRead()) return current.absolutePath
            current = current.parentFile
        }
        return null
    }

    /**
     * Read numeric uid/gid.
     *
     * Android's Java NIO does not expose a numeric uid attribute, so this stays null unless
     * a future API provides it. We return nulls rather than substituting 0 (root), because
     * "unknown" and "root" are different facts.
     */
    private fun readNumericOwner(path: Path): Pair<Int?, Int?> = Pair(null, null)

    private fun Set<PosixFilePermission>.toLinuxPermission(): LinuxPermission = LinuxPermission(
        ownerRead = PosixFilePermission.OWNER_READ in this,
        ownerWrite = PosixFilePermission.OWNER_WRITE in this,
        ownerExecute = PosixFilePermission.OWNER_EXECUTE in this,
        groupRead = PosixFilePermission.GROUP_READ in this,
        groupWrite = PosixFilePermission.GROUP_WRITE in this,
        groupExecute = PosixFilePermission.GROUP_EXECUTE in this,
        otherRead = PosixFilePermission.OTHERS_READ in this,
        otherWrite = PosixFilePermission.OTHERS_WRITE in this,
        otherExecute = PosixFilePermission.OTHERS_EXECUTE in this,
    )

    private companion object {
        @Suppress("unused")
        val DIRECTORY_FLAG = OsConstants.S_IFDIR

        /**
         * Depth cap for the recursive walk.
         *
         * Android's own limit for a path is far below this, so reaching it means something
         * unexpected (a bind-mount cycle, or a directory that reports itself as its own
         * parent); stopping is better than recursing until the stack dies.
         */
        const val MAX_SCAN_DEPTH = 64
    }
}
