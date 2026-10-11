package io.rootexplorer.pro.core.root

/** The outcome of probing whether this device grants us root. */
sealed interface RootStatus {

    /** Not probed yet. We never probe automatically at launch, because probing triggers a prompt. */
    data object Unknown : RootStatus

    /** A probe is in flight. */
    data object Checking : RootStatus

    /** `su` executed and the command ran as uid 0 — root really works. */
    data class Granted(
        val uid: Int,
        /** The `su` implementation, when it identified itself, e.g. "MagiskSU". */
        val suVersion: String? = null,
    ) : RootStatus

    /**
     * The probe ran but did not yield uid 0. Typically the user dismissed the prompt, or a
     * root manager is installed but this app is not on its allow-list.
     */
    data class Denied(val detail: String) : RootStatus

    /** No usable `su` binary: the device is not rooted, or the root manager is unavailable. */
    data class Unavailable(val detail: String) : RootStatus

    /** `su` exists and started, but the command itself failed. */
    data class CommandFailed(val exitCode: Int?, val detail: String) : RootStatus

    /** True only for [Granted]; the UI uses this to decide whether root actions are offered. */
    val isUsable: Boolean get() = this is Granted

    /** A short label for the drawer's root-status row. */
    val shortLabel: String
        get() = when (this) {
            Unknown -> "未检测"
            Checking -> "检测中…"
            is Granted -> "已获得 Root"
            is Denied -> "未授权"
            is Unavailable -> "无 Root"
            is CommandFailed -> "命令失败"
        }

    /** A sentence the user can act on. */
    val detailText: String
        get() = when (this) {
            Unknown -> "尚未检测 Root 权限。首次执行需要 Root 的操作时才会请求授权。"
            Checking -> "正在尝试执行 su 命令…"
            is Granted -> buildString {
                append("su 可用，已以 uid ").append(uid).append(" 执行命令")
                if (!suVersion.isNullOrBlank()) append("（").append(suVersion).append("）")
            }
            is Denied -> "Root 授权被拒绝或未授权：$detail"
            is Unavailable -> "找不到可用的 su：$detail"
            is CommandFailed -> "Root 命令执行失败（退出码 ${exitCode ?: "未知"}）：$detail"
        }
}
