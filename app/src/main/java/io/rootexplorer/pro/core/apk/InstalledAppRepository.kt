package io.rootexplorer.pro.core.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed application. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    /** Combined version code; on API 28+ this folds in versionCodeMajor. */
    val versionCode: Long,
    /** Install or update time in epoch millis, when the platform reports one. */
    val lastUpdateMillis: Long,
    val isSystemApp: Boolean,
    /** True when this app has a launcher entry and can therefore be opened. */
    val isLaunchable: Boolean,
    /** The base APK path. */
    val baseApkPath: String?,
    /** Split APK paths; a bundle-installed app is unusable without them. */
    val splitApkPaths: List<String>,
    /** Total size of the base APK plus every split, or null when it could not be measured. */
    val installedSizeBytes: Long?,
    /** targetSdkVersion, shown in the detail row. */
    val targetSdk: Int,
    val minSdk: Int,
) {
    /** Every APK that makes up this app. */
    val allApkPaths: List<String>
        get() = listOfNotNull(baseApkPath) + splitApkPaths

    /** True when the app is installed as an app bundle with splits. */
    val isSplit: Boolean get() = splitApkPaths.isNotEmpty()
}

/** How the list is ordered. */
enum class AppSortField(val label: String) {
    NAME("名称"),
    SIZE("大小"),
    UPDATE_TIME("更新时间"),
    PACKAGE("包名"),
}

/** Which apps are shown. */
enum class AppKindFilter(val label: String) {
    ALL("全部"),
    USER("用户应用"),
    SYSTEM("系统应用"),
}

/** The outcome of trying to export an app's APK. */
sealed interface AppExportResult {
    data class Success(val files: List<String>, val bytes: Long) : AppExportResult
    data class Failure(val reason: String) : AppExportResult
    /** The app has no readable APK, which happens for some system apps. */
    data class Unavailable(val reason: String) : AppExportResult
}

/**
 * Reads the installed application list.
 *
 * Everything here is read-only. Per the brief, the app never uninstalls, never clears data and
 * never modifies a system package: the only actions offered are opening an app, showing its
 * system details page, and copying its APK somewhere.
 *
 * Two details that are easy to get wrong:
 *
 * * **Split APKs.** An app installed from an app bundle is a base APK plus several splits. A
 *   copy of the base alone will not install, so [InstalledApp.allApkPaths] carries all of them
 *   and the size is the total rather than the base.
 * * **Unknown stays unknown.** Label resolution, install time and APK size can each fail
 *   without the app being absent, so those fields are nullable and rendered as 未知 rather than
 *   as a zero that reads like "empty app".
 */
class InstalledAppRepository(
    private val context: Context,
) {

    private val packageManager: PackageManager get() = context.packageManager

    /**
     * Every installed package this process is allowed to see.
     *
     * The manifest requests QUERY_ALL_PACKAGES, without which a modern Android build would only
     * see this app and a handful of others.
     */
    suspend fun loadAll(): Result<List<InstalledApp>> = withContext(Dispatchers.IO) {
        try {
            val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            }

            val apps = packages.map { toInstalledApp(it) }
                .sortedBy { it.label.lowercase() }

            Result.success(apps)
        } catch (e: Exception) {
            // A SecurityException here means the query permission is missing; reporting that is
            // far more useful than an empty list that looks like "no apps installed".
            Result.failure(e)
        }
    }

    /** Read one package, or null when it is not installed or not visible. */
    suspend fun loadOne(packageName: String): InstalledApp? = withContext(Dispatchers.IO) {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
            }
            toInstalledApp(info)
        } catch (e: Exception) {
            null
        }
    }

    private fun toInstalledApp(info: PackageInfo): InstalledApp {
        val applicationInfo: ApplicationInfo? = info.applicationInfo
        val flags = applicationInfo?.flags ?: 0

        val label = try {
            applicationInfo?.let { packageManager.getApplicationLabel(it).toString() }
        } catch (e: Exception) {
            null
        } ?: info.packageName

        val basePath = applicationInfo?.sourceDir
        val splits = applicationInfo?.splitSourceDirs?.toList().orEmpty()

        val installedSize = measureTotalSize(basePath, splits)
        val launchIntent = packageManager.getLaunchIntentForPackage(info.packageName)

        return InstalledApp(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = combinedVersionCode(info),
            lastUpdateMillis = info.lastUpdateTime,
            isSystemApp = flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            isLaunchable = launchIntent != null,
            baseApkPath = basePath,
            splitApkPaths = splits,
            installedSizeBytes = installedSize,
            targetSdk = applicationInfo?.targetSdkVersion ?: 0,
            // minSdkVersion exists from API 24 and this app's minSdk is 26, so no guard is
            // needed; an absent ApplicationInfo still reports 0 rather than guessing.
            minSdk = applicationInfo?.minSdkVersion ?: 0,
        )
    }

    /**
     * Combine versionCode and versionCodeMajor.
     *
     * From API 28 a package can carry a 64-bit version code split across the two fields;
     * ignoring the major half would report two different builds as the same version.
     */
    private fun combinedVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (info.longVersionCode)
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /**
     * Sum the base APK and every split.
     *
     * An unreadable path contributes nothing rather than making the total null, so one missing
     * split does not hide the size of everything else; the caller sees a smaller-than-real total
     * which is still more useful than 未知 for the whole app.
     */
    private fun measureTotalSize(basePath: String?, splits: List<String>): Long? {
        val paths = listOfNotNull(basePath) + splits
        if (paths.isEmpty()) return null

        var total = 0L
        var anyRead = false
        for (path in paths) {
            val file = File(path)
            if (file.isFile) {
                total += file.length()
                anyRead = true
            }
        }
        return if (anyRead) total else null
    }

    /** Load an app's icon. Returns null when the platform cannot provide one. */
    fun loadIcon(packageName: String): android.graphics.drawable.Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (e: Exception) {
        null
    }

    /** The system "app info" screen for [packageName], or null when no activity handles it. */
    fun appDetailsIntent(packageName: String): android.content.Intent? {
        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
        return intent.takeIf { it.resolveActivity(packageManager) != null }
    }

    /** A launch intent for [packageName], or null when the app has no launcher entry. */
    fun launchIntent(packageName: String): android.content.Intent? =
        packageManager.getLaunchIntentForPackage(packageName)

    /**
     * Copy an app's APKs into [destinationDirectory].
     *
     * The copy itself is done by the caller through the transfer engine so it gets progress,
     * cancellation and conflict handling for free; this method only decides *what* to copy and
     * checks that the sources exist and are readable.
     */
    fun exportSources(app: InstalledApp): AppExportResult {
        val paths = app.allApkPaths
        if (paths.isEmpty()) {
            return AppExportResult.Unavailable("该系统应用没有可读取的安装包路径")
        }

        val missing = paths.filterNot { File(it).isFile }
        if (missing.size == paths.size) {
            return AppExportResult.Unavailable(
                "安装包不可读（${missing.first()}）。系统应用常常没有可访问的 APK 文件。",
            )
        }

        return AppExportResult.Success(files = paths.filter { File(it).isFile }, bytes = 0L)
    }

    /**
     * The file name an exported APK should get.
     *
     * Pattern: `label_version(base|split0).apk`, with characters that are illegal in a file name
     * replaced. The extension is always `.apk` so the result is installable.
     *
     * A member so callers do not repeat the arguments, delegating to the pure helper below,
     * which is what the unit tests exercise without needing a device.
     */
    fun exportFileName(app: InstalledApp, sourcePath: String, index: Int): String =
        buildExportFileName(app, sourcePath, index)

    /** Sort and filter, delegating to the pure helper. */
    fun applyFilterAndSort(
        apps: List<InstalledApp>,
        query: String,
        kind: AppKindFilter,
        sortField: AppSortField,
        descending: Boolean,
    ): List<InstalledApp> = filterAndSort(apps, query, kind, sortField, descending)

    companion object {

        /** Characters that cannot appear in a file name on Android or on a Windows share. */
        private val ILLEGAL_NAME_CHARS = Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]")

        /**
         * Build the export name for one APK of [app].
         *
         * Pure and static so the naming rules can be tested on the JVM: getting this wrong
         * produces files that cannot be told apart, or a name the filesystem rejects.
         */
        fun buildExportFileName(app: InstalledApp, sourcePath: String, index: Int): String {
            val safeLabel = app.label
                .replace(ILLEGAL_NAME_CHARS, "_")
                .trim()
                .ifEmpty { app.packageName }
                .take(60)

            val version = app.versionName
                ?.replace(ILLEGAL_NAME_CHARS, "_")
                ?.take(24)
                ?: app.versionCode.toString()

            // A single-APK app gets a plain name; a bundled app marks each part so the set is
            // recognisable and cannot be confused with a standalone install.
            val suffix = if (app.isSplit) {
                val part = app.allApkPaths.indexOf(sourcePath).takeIf { it >= 0 } ?: index
                if (part == 0) "_base" else "_split$part"
            } else {
                ""
            }

            return "${safeLabel}_${version}${suffix}.apk"
        }

        /**
         * Filter and sort the app list.
         *
         * Unknown sizes and install times sort last in both directions, matching the rule used
         * everywhere else in this app: missing data must not read as "zero", which would put it
         * at the top of an ascending sort.
         */
        fun filterAndSort(
            apps: List<InstalledApp>,
            query: String,
            kind: AppKindFilter,
            sortField: AppSortField,
            descending: Boolean,
        ): List<InstalledApp> {
            val trimmed = query.trim()
            val filtered = apps.filter { app ->
                val kindOk = when (kind) {
                    AppKindFilter.ALL -> true
                    AppKindFilter.USER -> !app.isSystemApp
                    AppKindFilter.SYSTEM -> app.isSystemApp
                }
                val queryOk = trimmed.isEmpty() ||
                    app.label.contains(trimmed, ignoreCase = true) ||
                    app.packageName.contains(trimmed, ignoreCase = true)
                kindOk && queryOk
            }

            return when (sortField) {
                AppSortField.NAME -> {
                    val comparator = Comparator<InstalledApp> { a, b ->
                        a.label.compareTo(b.label, ignoreCase = true)
                    }
                    filtered.sortedWith(if (descending) comparator.reversed() else comparator)
                }

                AppSortField.PACKAGE -> {
                    val comparator = Comparator<InstalledApp> { a, b ->
                        a.packageName.compareTo(b.packageName, ignoreCase = true)
                    }
                    filtered.sortedWith(if (descending) comparator.reversed() else comparator)
                }

                AppSortField.SIZE -> filtered.sortedWith { a, b ->
                    compareNullable(a.installedSizeBytes, b.installedSizeBytes, descending)
                }

                AppSortField.UPDATE_TIME -> filtered.sortedWith { a, b ->
                    compareNullable(a.lastUpdateMillis, b.lastUpdateMillis, descending)
                }
            }
        }

        private fun <T : Comparable<T>> compareNullable(a: T?, b: T?, descending: Boolean): Int = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> if (descending) b.compareTo(a) else a.compareTo(b)
        }
    }
}
