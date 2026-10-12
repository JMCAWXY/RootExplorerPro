package io.rootexplorer.pro.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Detects and remembers whether this device grants us root.
 *
 * Design rule taken from the brief: the presence of a `su` file proves nothing, so we run a
 * real, side-effect-free command and judge the result from the process outcome, the exit code
 * and the actual output ("uid=0").
 *
 * A second rule: running `su` is what makes the root manager show its authorization prompt, so
 * we never probe on a device that has never granted root. [probe] is otherwise only called once
 * the user asks for a feature that needs root, and [markDenied] records a refusal without
 * immediately prompting again.
 *
 * The one exception is a grant the user already gave in an earlier run. A root manager stores
 * that decision per app, so re-running the probe then completes silently — no prompt — which is
 * what makes root survive the process being killed instead of asking for authorization again on
 * every launch. [report] records the outcome and [restoreIfPreviouslyGranted] acts on it.
 *
 * A remembered grant is never *trusted*: it only decides whether a silent probe is safe. Root is
 * still reported as granted only after the probe says so, because a grant can be revoked between
 * runs and claiming root without verifying is exactly what this class must not do.
 */
class RootAccess(
    /**
     * Runs one root command and returns its raw outcome.
     *
     * A function rather than a [RootShell] so the decision rules — when a probe may run at all,
     * and what is remembered afterwards — can be exercised on the JVM without spawning `su`.
     * Production passes the single shared shell.
     */
    private val runCommand: (command: String, timeoutMs: Long) -> ShellResult = defaultRunner(),
    /**
     * True when a previous run ended with root granted.
     *
     * Used for one thing only: deciding whether a silent re-probe is safe. It is never treated as
     * proof that root works now.
     */
    private val wasGrantedBefore: () -> Boolean = { false },
    /** Persists the outcome of a probe, so the next launch knows whether to re-probe silently. */
    private val onGrantRecorded: (Boolean) -> Unit = {},
) {

    private val mutex = Mutex()

    @Volatile
    var status: RootStatus = RootStatus.Unknown
        private set

    /** The command we use as the probe. `id -u` alone would not prove the shell ran as root. */
    private val probeCommand = buildString {
        append("id; ")
        append("echo ROOT_SU_UID=$(id -u); ")
        // Magisk and KernelSU both expose a version string; SuperSU does not.
        append("echo ROOT_SU_VER=$(su --version 2>/dev/null | head -n 1)")
    }

    /**
     * Probe root access, at most one probe at a time.
     *
     * [force] re-probes even when a previous attempt failed, but only when the user
     * explicitly asked for it; ordinary calls reuse the cached result.
     */
    suspend fun probe(force: Boolean = false, timeoutMs: Long = 25_000L): RootStatus =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!force && status is RootStatus.Granted) return@withLock status

                status = RootStatus.Checking
                val result = runCommand(probeCommand, timeoutMs)
                status = interpret(result)
                report(status.isUsable)
                status
            }
        }

    /**
     * Re-establish a grant given in an earlier run, without the user tapping anything.
     *
     * Returns null when this device has never granted root, and in that case executes **nothing**:
     * a probe there is precisely what would *cause* the root manager's prompt, and the launch
     * path must never do that. When a grant is remembered the probe is silent, because the root
     * manager already holds the decision for this app.
     *
     * The timeout is shorter than an interactive probe's: a remembered grant answers in well
     * under a second, so a long wait only means the grant is gone and the flag should be cleared
     * rather than left to re-prompt on the next launch.
     */
    suspend fun restoreIfPreviouslyGranted(timeoutMs: Long = RESTORE_TIMEOUT_MS): RootStatus? {
        if (!wasGrantedBefore()) return null
        return probe(timeoutMs = timeoutMs)
    }

    /** Record a refusal observed elsewhere, so the UI does not immediately prompt again. */
    fun markDenied(detail: String) {
        status = RootStatus.Denied(detail)
        report(granted = false)
    }

    /** Record that root is not available at all (no `su` on this device). */
    fun markUnavailable(detail: String) {
        status = RootStatus.Unavailable(detail)
        report(granted = false)
    }

    /** Persist whether root is currently known to work, so a restart can act on it. */
    private fun report(granted: Boolean) {
        onGrantRecorded(granted)
    }

    private fun interpret(result: ShellResult): RootStatus {
        if (result.launchError != null) {
            // ProcessBuilder could not even start `su`: not rooted, or no root manager.
            return RootStatus.Unavailable(result.launchError)
        }
        if (result.timedOut) {
            // The root manager showed a prompt nobody answered.
            return RootStatus.Denied("等待 Root 授权超时（授权弹窗未响应）")
        }

        val output = result.combinedOutput
        val uid = Regex("ROOT_SU_UID=(\\d+)")
            .find(result.stdout)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        if (uid == 0) {
            val version = Regex("ROOT_SU_VER=(.+)")
                .find(result.stdout)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
            return RootStatus.Granted(uid = 0, suVersion = version)
        }

        // su ran but we are not root: distinguish "no su" from "user said no".
        val lower = output.lowercase()
        val suMissing = lower.contains("not found") ||
            lower.contains("inaccessible") ||
            lower.contains("no such file") ||
            lower.contains("permission denied") && result.exitCode == 127

        if (suMissing || result.exitCode == 127) {
            return RootStatus.Unavailable(output.ifBlank { "系统中不存在可用的 su" })
        }
        if (uid != null && uid != 0) {
            return RootStatus.Denied("su 返回的 uid 为 $uid，不是 0")
        }
        if (lower.contains("denied") || lower.contains("deny")) {
            return RootStatus.Denied(output)
        }
        return RootStatus.CommandFailed(result.exitCode, output.ifBlank { "命令没有返回可解析的结果" })
    }

    private companion object {
        /**
         * How long a silent launch-time re-probe may take.
         *
         * A grant the root manager already holds is answered immediately, so anything close to
         * this bound means the grant is gone.
         */
        const val RESTORE_TIMEOUT_MS = 15_000L

        /** The production runner: one shared shell, which is what keeps `su` invocations cheap. */
        fun defaultRunner(): (String, Long) -> ShellResult {
            val shell = RootShell()
            return { command, timeoutMs -> shell.runBlocking(command, timeoutMs) }
        }
    }
}
