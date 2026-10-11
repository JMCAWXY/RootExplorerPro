package io.rootexplorer.pro.core.permissions

/**
 * A Unix permission set: the nine rwx bits plus the three special bits.
 *
 * This type is intentionally free of Android dependencies so the conversions can be unit
 * tested on the JVM. All the user-visible strings in the permission editor and the
 * properties dialog are produced here rather than being assembled in the UI, so the
 * octal value, the symbolic string and the checkboxes can never disagree.
 */
data class LinuxPermission(
    val ownerRead: Boolean = false,
    val ownerWrite: Boolean = false,
    val ownerExecute: Boolean = false,
    val groupRead: Boolean = false,
    val groupWrite: Boolean = false,
    val groupExecute: Boolean = false,
    val otherRead: Boolean = false,
    val otherWrite: Boolean = false,
    val otherExecute: Boolean = false,
    /** setuid: owner-execute position shows 's' (or 'S' when execute is clear). */
    val setUid: Boolean = false,
    /** setgid: group-execute position shows 's' (or 'S' when execute is clear). */
    val setGid: Boolean = false,
    /** sticky: other-execute position shows 't' (or 'T' when execute is clear). */
    val sticky: Boolean = false,
) {

    /** Permission bits as an integer, e.g. 0o644 = 420. */
    val modeBits: Int
        get() {
            var m = 0
            if (ownerRead) m = m or 0b100_000_000
            if (ownerWrite) m = m or 0b010_000_000
            if (ownerExecute) m = m or 0b001_000_000
            if (groupRead) m = m or 0b000_100_000
            if (groupWrite) m = m or 0b000_010_000
            if (groupExecute) m = m or 0b000_001_000
            if (otherRead) m = m or 0b000_000_100
            if (otherWrite) m = m or 0b000_000_010
            if (otherExecute) m = m or 0b000_000_001
            if (setUid) m = m or 0b100_000_000_000
            if (setGid) m = m or 0b010_000_000_000
            if (sticky) m = m or 0b001_000_000_000
            return m
        }

    /** Four-digit octal string without a prefix, e.g. "0644" or "4755". */
    val octal: String
        get() = (modeBits and 0xFFF).toString(8).padStart(4, '0')

    /** Three-digit octal string of the ordinary bits only, e.g. "644". */
    val octalSimple: String
        get() = (modeBits and 0x1FF).toString(8).padStart(3, '0')

    /** The nine character permission string, e.g. "rw-r--r--" (no leading file type). */
    val symbolic: String
        get() = buildString(9) {
            append(if (ownerRead) 'r' else '-')
            append(if (ownerWrite) 'w' else '-')
            append(
                when {
                    setUid && ownerExecute -> 's'
                    setUid -> 'S'
                    ownerExecute -> 'x'
                    else -> '-'
                },
            )
            append(if (groupRead) 'r' else '-')
            append(if (groupWrite) 'w' else '-')
            append(
                when {
                    setGid && groupExecute -> 's'
                    setGid -> 'S'
                    groupExecute -> 'x'
                    else -> '-'
                },
            )
            append(if (otherRead) 'r' else '-')
            append(if (otherWrite) 'w' else '-')
            append(
                when {
                    sticky && otherExecute -> 't'
                    sticky -> 'T'
                    otherExecute -> 'x'
                    else -> '-'
                },
            )
        }

    /**
     * Full `ls -l` style permission string including the file type character.
     *
     * [typeCharacter] is 'd' for a directory, 'l' for a symbolic link, '-' for a regular
     * file — the same values `ls -l` prints.
     */
    fun display(typeCharacter: Char = '-'): String = "$typeCharacter$symbolic"

    /** True when any of the special bits is set, used to warn the user in the editor. */
    val hasSpecialBits: Boolean
        get() = setUid || setGid || sticky

    companion object {

        /** Bit masks for the special permission bits. */
        const val SET_UID_BIT = 0b100_000_000_000
        const val SET_GID_BIT = 0b010_000_000_000
        const val STICKY_BIT = 0b001_000_000_000

        /**
         * Build a permission set from an integer mode such as 0o644 or 0o4755.
         *
         * Bits above 0o7777 (file-type bits) are masked off, because callers legitimately
         * pass a full `st_mode` value that also carries the file type.
         */
        fun fromMode(mode: Int): LinuxPermission {
            val m = mode and 0xFFF
            return LinuxPermission(
                ownerRead = m and 0b100_000_000 != 0,
                ownerWrite = m and 0b010_000_000 != 0,
                ownerExecute = m and 0b001_000_000 != 0,
                groupRead = m and 0b000_100_000 != 0,
                groupWrite = m and 0b000_010_000 != 0,
                groupExecute = m and 0b000_001_000 != 0,
                otherRead = m and 0b000_000_100 != 0,
                otherWrite = m and 0b000_000_010 != 0,
                otherExecute = m and 0b000_000_001 != 0,
                setUid = m and SET_UID_BIT != 0,
                setGid = m and SET_GID_BIT != 0,
                sticky = m and STICKY_BIT != 0,
            )
        }

        /**
         * Parse an octal permission string such as "644", "0644" or "4755".
         *
         * Returns null when the input is not a valid octal permission, so callers can show
         * a validation message instead of silently applying a wrong mode.
         */
        fun fromOctal(text: String): LinuxPermission? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > 4) return null
            if (trimmed.any { it !in '0'..'7' }) return null
            return fromMode(trimmed.toInt(8))
        }

        /**
         * Parse an `ls -l` permission string such as "rw-r--r--" or "rwsr-xr-x".
         *
         * Accepts an optional leading file-type character. Returns null when the string is
         * not exactly nine valid permission characters.
         *
         * Every position is validated against the characters legal in that position; simply
         * treating "anything that is not a dash" as a set bit would accept nonsense such as
         * "rw-r--r--x" and turn it into a permission nobody asked for.
         */
        fun fromSymbolic(text: String): LinuxPermission? {
            val trimmed = text.trim()
            val body = when (trimmed.length) {
                10 -> trimmed.substring(1)
                9 -> trimmed
                else -> return null
            }

            for ((index, c) in body.withIndex()) {
                val legal = when (index) {
                    0, 3, 6 -> c == 'r' || c == '-'
                    1, 4, 7 -> c == 'w' || c == '-'
                    else -> c == 'x' || c == '-' || c == 's' || c == 'S' || c == 't' || c == 'T'
                }
                if (!legal) return null
            }

            // The setuid/setgid/sticky letters occupy the execute positions. A lowercase
            // letter means the special bit is set *and* execute is set; an uppercase letter
            // means the special bit is set while execute is clear.
            val setUid = body[2] == 's' || body[2] == 'S'
            val setGid = body[5] == 's' || body[5] == 'S'
            val sticky = body[8] == 't' || body[8] == 'T'

            return LinuxPermission(
                ownerRead = body[0] == 'r',
                ownerWrite = body[1] == 'w',
                ownerExecute = body[2] == 'x' || body[2] == 's',
                groupRead = body[3] == 'r',
                groupWrite = body[4] == 'w',
                groupExecute = body[5] == 'x' || body[5] == 's',
                otherRead = body[6] == 'r',
                otherWrite = body[7] == 'w',
                otherExecute = body[8] == 'x' || body[8] == 't',
                setUid = setUid,
                setGid = setGid,
                sticky = sticky,
            )
        }
    }
}
