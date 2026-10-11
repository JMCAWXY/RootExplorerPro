package io.rootexplorer.pro.data.repository

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.filesystem.ListingResult
import io.rootexplorer.pro.core.filesystem.StatResult
import io.rootexplorer.pro.core.filesystem.StorageResult
import io.rootexplorer.pro.core.filesystem.SystemFileSystem
import io.rootexplorer.pro.core.filesystem.WellKnownPaths

/**
 * The single entry point the UI uses to read the filesystem.
 *
 * It exists so no screen talks to [SystemFileSystem] or the Android storage APIs directly:
 * the access rules (which engine, which permission, which fallback home directory) live in
 * one place and can be unit tested.
 */
class FileRepository(
    private val context: Context,
    private val fs: SystemFileSystem,
) {

    suspend fun list(path: String): ListingResult = fs.list(path)

    suspend fun stat(path: String): StatResult = fs.stat(path)

    suspend fun storage(path: String) = fs.storage(path)

    /**
     * Whether the app has the broad shared-storage access this Android version requires.
     *
     * * API 30+: `MANAGE_EXTERNAL_STORAGE`, which only the user can grant, on a dedicated
     *   Settings screen. We never assume it.
     * * API 26..29: `READ_EXTERNAL_STORAGE` as a normal runtime permission.
     */
    fun hasSharedStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * The directory to open when the app starts.
     *
     * If shared storage is not accessible yet, opening `/storage/emulated/0` would just show
     * a permission error on first launch, so we fall back to the app's own external
     * directory, which is always readable, and the UI explains how to get full access.
     */
    fun resolveStartPath(preferred: String): String {
        val candidate = FsEngine.normalise(preferred)
        if (hasSharedStorageAccess()) return candidate

        val appDir = context.getExternalFilesDir(null)?.absolutePath
        val fallback = WellKnownPaths.defaultHome
        return when {
            candidate.startsWith("/storage") || candidate.startsWith("/sdcard") ->
                appDir ?: fallback
            else -> candidate
        }
    }

    /** True when [path] is inside shared storage and therefore needs the broad permission. */
    fun needsSharedStoragePermission(path: String): Boolean {
        val normalised = FsEngine.normalise(path)
        return SHARED_STORAGE_PREFIXES.any { normalised == it || normalised.startsWith("$it/") }
    }

    /** A short explanation of the current access state, for the banner in the UI. */
    fun accessStateText(path: String): String? = when {
        hasSharedStorageAccess() -> null
        needsSharedStoragePermission(path) -> "当前没有「所有文件访问权限」，无法完整浏览共享存储。"
        else -> null
    }

    private companion object {
        val SHARED_STORAGE_PREFIXES = listOf("/storage", "/sdcard", "/mnt/sdcard")
    }
}
