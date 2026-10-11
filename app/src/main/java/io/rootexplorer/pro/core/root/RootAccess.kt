package io.rootexplorer.pro.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Detects and remembers whether this device grants us root.
 *
 * Design rule taken from the brief: the presence of a `su` file proves nothing, so we run
 * a real, side-effect-free command and judge the result from the process outcome, the exit
 * code and the actual output ("uid=0").
 *
 * A second rule: probing `su` makes the root manager show its authorization prompt. We
 * therefore never probe at launch — [probe] is only called once the user asks for a
 * feature that needs root — and [markDenied] lets the UI record a refusal without
 * immediately prompting again, which is how we avoid retry storms.
 */
class RootAccess(
    private val shell: RootShell = RootShell(),
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
                val result = shell.runBlocking(probeCommand, timeoutMs)
                status = interpret(result)
                status
            }
        }

    /** Record a refusal observed elsewhere, so the UI does not immediately prompt again. */
    fun markDenied(detail: String) {
        status = RootStatus.Denied(detail)
    }

    /** Record that root is not available at all (no `su` on this device). */
    fun markUnavailable(detail: String) {
        status = RootStatus.Unavailable(detail)
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
}
