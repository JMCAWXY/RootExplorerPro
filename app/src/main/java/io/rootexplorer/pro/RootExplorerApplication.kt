package io.rootexplorer.pro

import android.app.Application
import io.rootexplorer.pro.core.filesystem.StorageVolumes
import io.rootexplorer.pro.core.filesystem.SystemFileSystem
import io.rootexplorer.pro.core.root.RootAccess
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.data.repository.FileRepository
import io.rootexplorer.pro.data.repository.SettingsRepository
import io.rootexplorer.pro.service.fileoperation.FileOperationEngine
import io.rootexplorer.pro.service.fileoperation.FileOperationsEngine
import io.rootexplorer.pro.service.taskmanager.TaskManager
import io.rootexplorer.pro.service.trash.TrashRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Manual dependency container.
 *
 * The graph is small and entirely singleton-scoped, so a hand-written container is clearer
 * than adding a DI framework at this stage: each dependency is constructed once, explicitly,
 * and the classes themselves stay constructor-injected and therefore unit-testable.
 */
class AppContainer(application: Application) {

    /** One shared shell: spawning a new `su` per call would re-trigger the root prompt. */
    val rootShell: RootShell = RootShell()

    /**
     * Declared before [rootAccess] because the root hooks below read and write it, and Kotlin
     * initialises properties in declaration order.
     */
    val settingsRepository: SettingsRepository = SettingsRepository(application)

    /**
     * Root detection and its memory.
     *
     * The two hooks are what make a grant survive the app being killed. Every probe's outcome is
     * persisted, and a launch that finds a remembered grant re-probes silently — the root manager
     * answers that without a prompt, because it stores the decision per app. Without them the
     * status lived only in memory, so every cold start began at 未检测 and the user had to tap
     * 授权 again after each full exit.
     */
    val rootAccess: RootAccess = RootAccess(
        runCommand = rootShell::runBlocking,
        wasGrantedBefore = { settingsRepository.wasRootGranted() },
        onGrantRecorded = { granted -> settingsRepository.saveRootGranted(granted) },
    )

    val fileSystem: SystemFileSystem = SystemFileSystem(rootAccess, rootShell)

    val storageVolumes: StorageVolumes = StorageVolumes(application)

    val fileRepository: FileRepository = FileRepository(application, fileSystem)

    /** `chmod` and `chown` with mandatory read-back verification. */
    val permissionChanger: io.rootexplorer.pro.core.permissions.PermissionChanger =
        io.rootexplorer.pro.core.permissions.PermissionChanger(
            rootShell = rootShell,
            rootAvailable = { rootAccess.status.isUsable },
        )

    /** Single-item mutations (create, rename, delete) that predate the transfer engine. */
    val fileOperations: FileOperationsEngine =
        FileOperationsEngine(rootShell, rootAvailable = { rootAccess.status.isUsable })

    /** Recursive copy/move with planning, progress, cancellation and conflict handling. */
    val transferEngine: FileOperationEngine =
        FileOperationEngine(rootShell, rootAvailable = { rootAccess.status.isUsable })

    /** The in-app recycle bin. */
    val trashRepository: TrashRepository =
        TrashRepository(application, rootShell, rootAvailable = { rootAccess.status.isUsable })

    /**
     * Scope for long-running file tasks.
     *
     * Deliberately application-scoped rather than tied to a screen: a copy must survive the
     * user navigating away or the activity being recreated, which is exactly the case the
     * brief calls out. A SupervisorJob keeps one failed transfer from cancelling others.
     */
    private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Foreground task state, exposed to the UI. */
    val taskManager: TaskManager = TaskManager(taskScope, transferEngine, trashRepository)

    /** Root-aware name search over one directory, optionally recursive. */
    val searchEngine: io.rootexplorer.pro.feature.search.SearchEngine =
        io.rootexplorer.pro.feature.search.SearchEngine(fileSystem)

    /** File digests (MD5 / SHA-1 / SHA-256), computed in a single pass. */
    val hashEngine: io.rootexplorer.pro.core.crypto.HashEngine =
        io.rootexplorer.pro.core.crypto.HashEngine()

    /** Archive creation and extraction, including the Zip Slip defence. */
    val archiveEngine: io.rootexplorer.pro.core.archive.ArchiveEngine =
        io.rootexplorer.pro.core.archive.ArchiveEngine()

    /** Text file loading and saving, with encoding and binary detection. */
    val textFileEngine: io.rootexplorer.pro.core.text.TextFileEngine =
        io.rootexplorer.pro.core.text.TextFileEngine()

    /** Read-only view of the installed packages. */
    val installedApps: io.rootexplorer.pro.core.apk.InstalledAppRepository =
        io.rootexplorer.pro.core.apk.InstalledAppRepository(application)

    /** Decodes images for the viewer, subsampling so a huge photo cannot exhaust the heap. */
    val imageLoader: io.rootexplorer.pro.core.image.ImageLoader =
        io.rootexplorer.pro.core.image.ImageLoader()

    /**
     * Runs a shell script the user picked, in the environment and with the privileges they chose.
     *
     * The packaged environment points at the app's own native library directory, which is what
     * makes 使用扩展包环境执行 a real difference rather than just a label.
     */
    val scriptRunner: io.rootexplorer.pro.core.script.ScriptRunner =
        io.rootexplorer.pro.core.script.ScriptRunner(
            rootShell = rootShell,
            nativeLibraryDir = application.applicationInfo.nativeLibraryDir.orEmpty(),
            filesBinDir = File(application.filesDir, "bin").absolutePath,
        )
}

/** Application entry point; owns the container for the process lifetime. */
class RootExplorerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
