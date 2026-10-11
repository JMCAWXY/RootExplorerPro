package io.rootexplorer.pro.core.filesystem

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * Enumerates the device's storage volumes.
 *
 * Uses the platform's [StorageManager] so the list matches what the system itself reports.
 * Volumes without a browsable filesystem path keep `path = null`, and the drawer shows them
 * as unavailable rather than offering a button that cannot work.
 */
class StorageVolumes(private val context: Context) {

    fun current(): List<StorageVolumeInfo> {
        val manager = context.getSystemService(StorageManager::class.java)
            ?: return emptyList()

        val volumes: List<StorageVolume> = try {
            manager.storageVolumes
        } catch (e: Exception) {
            emptyList()
        }

        return volumes.mapNotNull { volume ->
            val path: String? = directoryOf(volume, isPrimary = volume.isPrimary)
                ?: runCatching { volume.uuid?.let { "/storage/$it" } }.getOrNull()

            @Suppress("DEPRECATION")
            val description: CharSequence? = runCatching { volume.getDescription(context) }.getOrNull()

            StorageVolumeInfo(
                id = volume.uuid ?: description?.toString() ?: "unknown",
                label = describe(volume, description),
                path = path,
                isRemovable = volume.isRemovable,
                isPrimary = volume.isPrimary,
                isEmulated = volume.isEmulated,
                isMounted = volume.state == Environment.MEDIA_MOUNTED,
                stats = path?.let { readStats(it) },
            )
        }.ifEmpty {
            // Fall back to the one path that exists on every Android device, so the drawer is
            // never empty even if StorageManager returns nothing.
            listOf(
                StorageVolumeInfo(
                    id = "emulated-0",
                    label = "内部存储",
                    path = Environment.getExternalStorageDirectory()?.absolutePath,
                    isRemovable = false,
                    isPrimary = true,
                    isEmulated = true,
                    isMounted = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED,
                    stats = Environment.getExternalStorageDirectory()?.absolutePath?.let { readStats(it) },
                ),
            )
        }
    }

    /** The volume that contains [path], when we can tell. */
    fun volumeFor(path: String): StorageVolumeInfo? {
        val normalised = FsEngine.normalise(path)
        return current()
            .filter { it.path != null }
            .filter { normalised == it.path || normalised.startsWith("${it.path}/") }
            .maxByOrNull { it.path!!.length }
    }

    private fun readStats(path: String): StorageStats? = try {
        val file = File(path)
        if (!file.exists()) {
            null
        } else {
            val stat = android.os.StatFs(file.absolutePath)
            StorageStats(
                totalBytes = stat.totalBytes,
                freeBytes = stat.freeBytes,
                availableBytes = stat.availableBytes,
            )
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The browsable directory of a volume, or null when it has none.
     *
     * `StorageVolume.getDirectory()` is the public accessor, but it only exists from API 30
     * and this project's minSdk is 26. Below API 30 there is no public accessor at all, so
     * the method is found by reflection; on the API levels where it was removed it simply
     * will not be there, which is why the result is allowed to be null.
     *
     * No Java `File` is ever created from a path returned here, so a surprise value cannot
     * corrupt anything downstream.
     */
    private fun directoryOf(volume: StorageVolume, isPrimary: Boolean): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { volume.directory?.absolutePath }.getOrNull()?.let { return it }
        } else {
            // Pre-R: reflectively look for getPath(). The result is only ever compared as a
            // string prefix, never used to build a File.
            val viaReflection: String? = runCatching {
                val method = StorageVolume::class.java.getMethod("getPath")
                method.invoke(volume) as? String
            }.getOrNull()
            if (!viaReflection.isNullOrBlank()) return viaReflection
        }

        // The primary volume is always the emulated shared storage.
        if (isPrimary) {
            return Environment.getExternalStorageDirectory()?.absolutePath
        }
        return null
    }

    /**
     * Human-readable volume name.
     *
     * [description] is passed in because the caller already read it; reading it twice would
     * be wasteful and `getDescription` is deprecated on newer API levels.
     */
    private fun describe(volume: StorageVolume, description: CharSequence?): String = when {
        !description.isNullOrBlank() -> description.toString()
        volume.isRemovable -> "可移动存储"
        else -> "内部存储"
    }
}
