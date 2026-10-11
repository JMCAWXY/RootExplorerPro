package io.rootexplorer.pro.core.permissions

import io.rootexplorer.pro.core.filesystem.FsEngine
import io.rootexplorer.pro.core.filesystem.StatResult
import io.rootexplorer.pro.core.root.RootShell
import io.rootexplorer.pro.core.root.ShellQuote
import java.io.File

/**
 * The outcome of a change.
 *
 * The brief is explicit that a change must be verified by re-reading the attributes rather
 * than inferred from an exit code, so every result carries the verification outcome. A command
 * that exits 0 while the filesystem still reports the old mode is reported as a failure —
 * which happens in practice on read-only partitions and on some FUSE-backed mounts.
 */
sealed interface ChangeResult {

    /** The change was applied and confirmed by re-reading the path. */
    data class Applied(
        val description: String,
        val before: String,
        val after: String,
    ) : ChangeResult

    /**
     * The command ran but the re-read shows the change did not take effect.
     *
     * This is the case the brief warns about: do not report success from the exit status alone.
     */
    data class NotApplied(
        val reason: String,
        val still: String,
    ) : ChangeResult

    /** The command failed; [reason] carries the shell's own message. */
    data class Failed(val reason: String) : ChangeResult

    /** The change needs root and root is not available. */
    data object RootRequired : ChangeResult

    /**
     * The path is on a read-only filesystem.
     *
     * Deliberately terminal: the app does not attempt to remount system partitions, per the
     * brief's stability rules.
     */
    data class ReadOnlyFileSystem(val path: String) : ChangeResult

    /** The verification itself could not be performed, so success cannot be claimed. */
    data class Unverified(val reason: String) : ChangeResult
}

/** A path the user should think twice about before changing. */
data class SensitivePath(val path: String, val reason: String)

/**
 * Applies `chmod` and `chown` through the root shell and verifies every change by re-reading.
 *
 * Safety properties this class is responsible for:
 *
 * * never infers success from an exit code — the path is always re-read;
 * * never remounts a partition, and reports a read-only filesystem as such;
 * * refuses to touch a path outside the filesystem root, and refuses an empty or relative path;
 * * flags sensitive locations (`/system`, `/vendor`, `/data/adb`, …) so the UI can require an
 *   extra confirmation instead of changing them quietly;
 * * passes every path and value through [ShellQuote].
 */
class PermissionChanger(
    private val rootShell: RootShell,
    private val rootAvailable: () -> Boolean,
) {

    /**
     * Paths where a permission or ownership change can break the device or a security boundary.
     *
     * The app does not forbid them — a root file manager is expected to be able to work there —
     * but it makes the user confirm with the consequence spelled out.
     */
    private val sensitivePrefixes = listOf(
        SensitivePath("/system", "系统分区，修改后可能导致设备无法启动或无法接收更新"),
        SensitivePath("/system_ext", "系统扩展分区，修改后可能影响系统功能"),
        SensitivePath("/vendor", "厂商分区，修改后可能导致硬件功能异常"),
        SensitivePath("/product", "产品分区，修改后可能影响预装组件"),
        SensitivePath("/data/adb", "Root 管理器的数据目录，修改后可能导致 Root 失效"),
        SensitivePath("/data/misc", "系统服务数据目录，修改后可能导致系统服务异常"),
        SensitivePath("/metadata", "加密元数据分区，修改后可能导致数据无法解密"),
        SensitivePath("/init.rc", "系统启动脚本"),
        SensitivePath("/data/system", "系统设置与账户数据"),
    )

    /** Partitions that are normally mounted read-only on a production device. */
    private val readOnlyPrefixes = listOf(
        "/system", "/system_ext", "/vendor", "/product", "/metadata", "/odm", "/oem", "/prism",
        "/optics",
    )

    /** A warning for [path], or null when the path is not in a sensitive location. */
    fun sensitiveWarning(path: String): SensitivePath? {
        val normalised = FsEngine.normalise(path)
        return sensitivePrefixes.firstOrNull { sensitive ->
            normalised == sensitive.path || normalised.startsWith("${sensitive.path}/")
        }
    }

    /** True when [path] is normally on a read-only partition. */
    fun isTypicallyReadOnly(path: String): Boolean {
        val normalised = FsEngine.normalise(path)
        return readOnlyPrefixes.any { normalised == it || normalised.startsWith("$it/") }
    }

    /**
     * Decide the outcome of a mode change from the requested mode, the observed mode and the
     * command's exit status.
     *
     * Extracted as a pure function on purpose: this is the rule the brief cares most about —
     * an exit code of 0 is never sufficient, and a mismatch after a "successful" command is a
     * failure, not a success. Being pure, it is directly unit-testable.
     */
    internal fun decideModeOutcome(
        requested: LinuxPermission,
        observed: LinuxPermission?,
        commandExitCode: Int?,
        commandOutput: String,
    ): ChangeResult {
        if (observed == null) {
            return ChangeResult.Unverified("命令已执行，但该文件系统未提供权限信息，无法验证结果。")
        }
        // Compared on the numeric mode, which includes setuid/setgid/sticky. Comparing only the
        // three ordinary digits would report success when a requested setuid bit silently failed
        // to stick — a privilege-escalation-relevant false success.
        if (observed.modeBits == requested.modeBits) {
            return ChangeResult.Applied(
                description = "权限已改为 ${requested.modeDescription}（已重新读取验证）",
                before = "",
                after = observed.modeDescription,
            )
        }
        if (commandExitCode != null && commandExitCode != 0) {
            return ChangeResult.Failed(
                commandOutput.ifBlank {
                    "chmod 退出码 $commandExitCode，权限仍为 ${observed.modeDescription}"
                },
            )
        }
        return ChangeResult.NotApplied(
            reason = "命令报告成功，但重新读取后权限仍是 ${observed.modeDescription}。",
            still = observed.modeDescription,
        )
    }

    /**
     * The mode as shown in messages.
     *
     * The three ordinary digits, with the special bits marked when present so a setuid file is
     * never described as if it were an ordinary one.
     */
    private val LinuxPermission.modeDescription: String
        get() {
            val special = buildString {
                if (setUid) append("setuid,")
                if (setGid) append("setgid,")
                if (sticky) append("sticky,")
            }.trimEnd(',')
            return if (special.isEmpty()) octalSimple else "$octalSimple ($special)"
        }

    /**
     * The same rule for ownership: names and numeric ids are both accepted, because a device
     * may report `root` for a uid the caller asked for as `0`.
     *
     * [requestedOwnerId]/[requestedGroupId] carry the ids the caller already resolved from the
     * names, so a request by name still matches a device that reports only a number. When the
     * names cannot be resolved and the device reports no name either, the comparison falls back
     * to the requested spec itself and the change is reported as not applied rather than as
     * success.
     */
    internal fun decideOwnershipOutcome(
        requestedOwner: String,
        requestedGroup: String?,
        observedOwnerName: String?,
        observedOwnerId: Int?,
        observedGroupName: String?,
        observedGroupId: Int?,
        commandExitCode: Int?,
        commandOutput: String,
        requestedOwnerId: Int? = null,
        requestedGroupId: Int? = null,
    ): ChangeResult {
        val ownerOk = matches(requestedOwner, requestedOwnerId, observedOwnerName, observedOwnerId)
        val groupOk = requestedGroup.isNullOrBlank() ||
            matches(requestedGroup, requestedGroupId, observedGroupName, observedGroupId)

        val description = buildString {
            append(observedOwnerName ?: observedOwnerId?.toString() ?: "?")
            append(":")
            append(observedGroupName ?: observedGroupId?.toString() ?: "?")
        }

        if (ownerOk && groupOk) {
            return ChangeResult.Applied(
                description = "所有者/用户组已改为 $description（已重新读取验证）",
                before = "",
                after = description,
            )
        }
        if (commandExitCode != null && commandExitCode != 0) {
            return ChangeResult.Failed(
                commandOutput.ifBlank { "chown 退出码 $commandExitCode，当前为 $description" },
            )
        }
        return ChangeResult.NotApplied(
            reason = "命令报告成功，但重新读取后所有者/用户组仍为 $description。",
            still = description,
        )
    }

    /**
     * Reject input that can never be a valid chmod target.
     *
     * A relative path would be resolved against the shell's working directory, which is not a
     * location the user chose, so it is refused rather than guessed at.
     */
    fun validateTarget(path: String): String? {
        val trimmed = path.trim()
        return when {
            trimmed.isEmpty() -> "路径不能为空"
            !trimmed.startsWith("/") -> "必须使用绝对路径"
            trimmed.contains('\u0000') -> "路径不能包含空字符"
            FsEngine.normalise(trimmed) == "/" -> "拒绝对根目录执行该操作"
            else -> null
        }
    }

    /**
     * Change the mode of [entry], then re-read it to confirm.
     *
     * [readStat] is supplied by the caller so verification uses the same engine that produced
     * the listing; the class does not reach into the filesystem on its own.
     */
    suspend fun changeMode(
        entry: io.rootexplorer.pro.core.filesystem.FileEntry,
        permission: LinuxPermission,
        recursive: Boolean = false,
        readStat: suspend (String) -> StatResult,
    ): ChangeResult {
        // Validate the path as given, *before* normalising it. normalise() prepends a leading
        // slash, which would turn a relative path into an absolute one and silently defeat the
        // "must be absolute" rule below.
        validateTarget(entry.path)?.let { return ChangeResult.Failed(it) }
        val path = FsEngine.normalise(entry.path)

        if (!rootAvailable()) return ChangeResult.RootRequired
        if (isTypicallyReadOnly(path) && isProbablyReadOnly(path)) {
            return ChangeResult.ReadOnlyFileSystem(path)
        }

        // `octal`, not `octalSimple`: the simple form is masked with 0x1FF and therefore drops
        // setuid/setgid/sticky. The permission editor offers all three as checkboxes, so using
        // the simple form meant a user could tick setuid, be told the change was attempted, and
        // have `chmod 755` run instead of `chmod 4755` — the bit silently never landed. Verified
        // on the device that `chmod 4755` works and that `stat -c %a` reports the four digits back.
        val octal = permission.octal
        val before = describe(entry)

        // Only an explicit request recurses. A recursive chmod that follows links can walk out
        // of the directory the user pointed at, so nothing here adds -h or follows links.
        val recursiveFlag = if (recursive) "-R " else ""
        val command = "chmod $recursiveFlag$octal ${ShellQuote.quote(path)} 2>&1"
        val result = rootShell.runBlocking(command)

        if (result.launchError != null) {
            return ChangeResult.Failed("无法执行 su：${result.launchError}")
        }
        if (result.timedOut) {
            return ChangeResult.Failed("命令执行超时")
        }

        // Verification is not optional: read the path back and compare.
        return when (val stat = readStat(path)) {
            is StatResult.Failure -> ChangeResult.Unverified(
                "命令已执行，但无法重新读取属性以验证：${stat.error.message}",
            )

            is StatResult.Success -> {
                val decision = decideModeOutcome(
                    requested = permission,
                    observed = stat.stat.entry.permission,
                    commandExitCode = result.exitCode,
                    commandOutput = result.combinedOutput,
                )
                // Attach the observed before/after so the dialog can show what actually changed.
                when (decision) {
                    is ChangeResult.Applied -> decision.copy(
                        before = before,
                        after = describe(stat.stat.entry),
                    )

                    is ChangeResult.NotApplied -> if (isTypicallyReadOnly(path)) {
                        decision.copy(
                            reason = decision.reason +
                                "该路径通常位于只读分区，应用不会尝试重新挂载。",
                        )
                    } else {
                        decision
                    }

                    else -> decision
                }
            }
        }
    }

    /**
     * Change the owner and/or group of [entry], then re-read to confirm.
     *
     * [owner] and [group] may be names or numeric ids, which is what `chown` accepts; both are
     * validated before use so a typo cannot become a shell argument.
     */
    suspend fun changeOwnership(
        entry: io.rootexplorer.pro.core.filesystem.FileEntry,
        owner: String,
        group: String?,
        recursive: Boolean = false,
        readStat: suspend (String) -> StatResult,
    ): ChangeResult {
        // Validated before normalisation, for the same reason as changeMode.
        validateTarget(entry.path)?.let { return ChangeResult.Failed(it) }
        validateId(owner)?.let { return ChangeResult.Failed("所有者无效：$it") }
        if (group != null) validateId(group)?.let { return ChangeResult.Failed("用户组无效：$it") }

        val path = FsEngine.normalise(entry.path)

        if (!rootAvailable()) return ChangeResult.RootRequired
        if (isTypicallyReadOnly(path) && isProbablyReadOnly(path)) {
            return ChangeResult.ReadOnlyFileSystem(path)
        }

        val spec = if (group.isNullOrBlank()) owner else "$owner:$group"
        val recursiveFlag = if (recursive) "-R " else ""
        val command = "chown $recursiveFlag${ShellQuote.quote(spec)} ${ShellQuote.quote(path)} 2>&1"
        val result = rootShell.runBlocking(command)

        if (result.launchError != null) return ChangeResult.Failed("无法执行 su：${result.launchError}")
        if (result.timedOut) return ChangeResult.Failed("命令执行超时")

        val before = describe(entry)

        // Resolve the requested names to ids *before* verifying. Without this, asking for
        // "root" on a device whose `stat` returns no owner name would compare a name against
        // null and wrongly declare the change unapplied.
        val requestedOwnerId = owner.trim().toIntOrNull() ?: resolveUid(owner.trim())
        val requestedGroupId = group?.trim()?.takeIf { it.isNotEmpty() }?.let { spec ->
            spec.toIntOrNull() ?: resolveGid(spec)
        }

        return when (val stat = readStat(path)) {
            is StatResult.Failure -> ChangeResult.Unverified(
                "命令已执行，但无法重新读取属性以验证：${stat.error.message}",
            )

            is StatResult.Success -> {
                val decision = decideOwnershipOutcome(
                    requestedOwner = owner,
                    requestedGroup = group,
                    observedOwnerName = stat.stat.ownerName ?: stat.stat.entry.ownerName,
                    observedOwnerId = stat.stat.entry.uid,
                    observedGroupName = stat.stat.groupName ?: stat.stat.entry.groupName,
                    observedGroupId = stat.stat.entry.gid,
                    commandExitCode = result.exitCode,
                    commandOutput = result.combinedOutput,
                    requestedOwnerId = requestedOwnerId,
                    requestedGroupId = requestedGroupId,
                )
                when (decision) {
                    is ChangeResult.Applied -> decision.copy(before = before)
                    is ChangeResult.NotApplied -> if (isTypicallyReadOnly(path)) {
                        decision.copy(
                            reason = decision.reason +
                                "该路径通常位于只读分区，应用不会尝试重新挂载。",
                        )
                    } else {
                        decision
                    }

                    else -> decision
                }
            }
        }
    }

    /**
     * Resolve a user or group name to its numeric id, or null when it cannot be resolved.
     *
     * Uses the device's own account database through `id`, so an unknown name returns null and
     * the UI can say so instead of silently substituting root.
     */
    suspend fun resolveUid(name: String): Int? =
        runIdCommand("id -u ${ShellQuote.quote(name)}")

    suspend fun resolveGid(name: String): Int? =
        runIdCommand("id -g ${ShellQuote.quote(name)}")

    /** Look up the name for a numeric id, or null when the device has no such account. */
    suspend fun resolveUserName(uid: Int): String? = runIdText("id -nu $uid")

    suspend fun resolveGroupName(gid: Int): String? = runIdText("id -ng $gid")

    // ------------------------------------------------------------------ internals

    private fun runIdCommand(command: String): Int? {
        if (!rootAvailable()) return null
        val result = rootShell.runBlocking("$command 2>/dev/null")
        if (!result.isSuccess) return null
        return result.stdout.trim().lineSequence().firstOrNull()?.toIntOrNull()
    }

    private fun runIdText(command: String): String? {
        if (!rootAvailable()) return null
        val result = rootShell.runBlocking("$command 2>/dev/null")
        if (!result.isSuccess) return null
        return result.stdout.trim().lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Validate an owner or group specifier.
     *
     * Accepts a numeric id or a POSIX user/group name. The character set is deliberately
     * narrow: rejecting anything that is not clearly a valid name is safer than quoting
     * something ambiguous, and these values end up inside a command.
     */
    fun validateId(value: String): String? {
        val trimmed = value.trim()
        return when {
            trimmed.isEmpty() -> "不能为空"
            trimmed.length > 32 -> "长度不能超过 32 个字符"
            trimmed.toIntOrNull() != null -> {
                val id = trimmed.toInt()
                if (id < 0 || id > MAX_ID) "数字 ID 超出范围（0-$MAX_ID）" else null
            }
            // Names on Android are conventionally [a-z_][a-z0-9_-]*, with no dots.
            !NAME_PATTERN.matches(trimmed) ->
                "只能包含字母、数字、下划线或短横线，且以字母或下划线开头"
            else -> null
        }
    }

    /**
     * Does an observed owner match what was requested, given names and numeric ids?
     *
     * Any of the four combinations counts, because neither side is guaranteed to be a name:
     * the caller may ask for `0` where the device reports `root`, or ask for `root` where the
     * device reports only the number.
     */
    private fun matches(
        requested: String,
        requestedId: Int?,
        observedName: String?,
        observedId: Int?,
    ): Boolean {
        val trimmed = requested.trim()
        if (observedName != null && observedName.equals(trimmed, ignoreCase = true)) return true
        val wantedId = requestedId ?: trimmed.toIntOrNull()
        return wantedId != null && observedId != null && wantedId == observedId
    }

    /**
     * A cheap read-only probe.
     *
     * The authoritative answer arrives from the verification re-read; this only avoids issuing
     * a doomed command against a partition that is mounted read-only.
     */
    private fun isProbablyReadOnly(path: String): Boolean {
        val file = java.io.File(path)
        return file.exists() && !file.canWrite()
    }

    private fun describe(
        entry: io.rootexplorer.pro.core.filesystem.FileEntry,
        owner: String? = entry.ownerName,
        group: String? = entry.groupName,
    ): String = buildString {
        append(entry.permission?.octalSimple ?: "未知")
        append("  ")
        append(owner ?: "?")
        append(":")
        append(group ?: "?")
    }

    private companion object {
        const val MAX_ID = 99999
        val NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
    }
}
