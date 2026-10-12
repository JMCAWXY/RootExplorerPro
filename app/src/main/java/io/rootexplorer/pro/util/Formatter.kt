package io.rootexplorer.pro.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting helpers shared by every screen.
 *
 * They exist so that the file list, the properties dialog and the top bar cannot drift
 * apart in how they render a size or a date — the reference screenshots are consistent
 * about this, and consistency is a large part of looking professional.
 *
 * Performance matters here more than the size of this file suggests: [listDate] and [size] are
 * on the file-list row path, so they run for every visible row on every recomposition.
 *
 * * The date formatters are [DateTimeFormatter]s. They are immutable and thread-safe, and
 *   `listDate` no longer builds two `SimpleDateFormat`s per call — that was a pattern
 *   compilation plus a locale lookup for every row, every frame. It also removes the latent
 *   hazard of shared, mutable `SimpleDateFormat` instances.
 * * "Is this today?" now compares [LocalDate]s instead of formatting both instants into
 *   throwaway strings purely to compare them.
 * * [size] and [capacity] deliberately keep `String.format`: it pins the rounding mode and the
 *   locale exactly, and a hand-rolled equivalent would risk off-by-one-ulp output for no
 *   measurable gain.
 */
object Formatter {

    private const val KIB = 1024.0
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024
    private const val TIB = GIB * 1024

    /** `yyyy-MM-dd HH:mm`, the full timestamp the properties dialog shows. */
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** `HH:mm`, shown for entries modified today. */
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /**
     * Two-digit year, e.g. "26-10-02".
     *
     * Matches the reference file manager, and it matters for density: the four-digit form costs
     * two extra characters in every row of a dense list for no information anyone reads.
     */
    private val dateFormat = DateTimeFormatter.ofPattern("yy-MM-dd", Locale.US)

    /**
     * Render a byte count the way the reference UI does: two decimals above 1 GiB,
     * one decimal for MiB, and plain bytes below 1 KiB.
     */
    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "未知"
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < MIB -> String.format(Locale.US, "%.1fK", bytes / KIB)
            bytes < GIB -> String.format(Locale.US, "%.1fM", bytes / MIB)
            bytes < TIB -> String.format(Locale.US, "%.2fG", bytes / GIB)
            else -> String.format(Locale.US, "%.2fT", bytes / TIB)
        }
    }

    /** A compact used/total pair such as "35.45G/224.07G". */
    fun capacity(usedBytes: Long?, totalBytes: Long?): String {
        if (usedBytes == null || totalBytes == null) return "未知"
        return "${size(usedBytes)}/${size(totalBytes)}"
    }

    /**
     * Render a timestamp the way a file manager does: time only for today, date only for
     * older entries in a list context.
     */
    fun listDate(millis: Long?): String {
        if (millis == null || millis <= 0L) return "未知"
        // One zone lookup for the whole call: the value and "now" must be read in the same
        // zone, or the comparison could straddle a timezone change.
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(millis).atZone(zone)
        return if (moment.toLocalDate() == LocalDate.now(zone)) {
            timeFormat.format(moment)
        } else {
            dateFormat.format(moment)
        }
    }

    /** Full timestamp for the properties dialog. */
    fun fullDate(millis: Long?): String {
        if (millis == null || millis <= 0L) return "未知"
        return dateTimeFormat.format(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
        )
    }

    /** Render a numeric uid/gid, or "未知" when it could not be read. */
    fun id(value: Int?): String = value?.toString() ?: "未知"

    /** Render a name that may be unknown. */
    fun name(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "未知"

    /** Render a permission octet pair such as "-rw-r--r-- (644)". */
    fun permission(symbolic: String?, octal: String?): String {
        if (symbolic == null) return "未知"
        return if (octal == null) symbolic else "$symbolic ($octal)"
    }
}
