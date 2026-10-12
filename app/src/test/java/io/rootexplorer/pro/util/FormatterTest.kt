package io.rootexplorer.pro.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The shared formatters.
 *
 * These run for every visible row of the file list, so they are both a correctness surface and
 * a performance one. The date functions were rewritten on top of `java.time` — the previous
 * version built two `SimpleDateFormat`s per call, which is a pattern compilation and a locale
 * lookup for every row on every frame — so these tests pin the rendered text to make sure a
 * rewrite cannot quietly change what the user sees.
 */
class FormatterTest {

    // ------------------------------------------------------------------ sizes

    @Test
    fun `an unknown or negative size is reported as unknown`() {
        assertEquals("未知", Formatter.size(null))
        assertEquals("未知", Formatter.size(-1))
    }

    @Test
    fun `sizes below one kibibyte are shown as plain bytes`() {
        assertEquals("0 B", Formatter.size(0))
        assertEquals("1023 B", Formatter.size(1023))
    }

    @Test
    fun `sizes up to one mebibyte use one decimal of K`() {
        assertEquals("1.0K", Formatter.size(1024))
        assertEquals("1.5K", Formatter.size(1536))
    }

    @Test
    fun `sizes up to one gibibyte use one decimal of M`() {
        assertEquals("1.0M", Formatter.size(1024L * 1024))
        assertEquals("2.5M", Formatter.size((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `sizes from one gibibyte up use two decimals`() {
        assertEquals("1.00G", Formatter.size(1024L * 1024 * 1024))
        assertEquals("1.50G", Formatter.size((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.00T", Formatter.size(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `capacity joins the used and total figures, and is unknown unless both exist`() {
        assertEquals("未知", Formatter.capacity(null, 1))
        assertEquals("未知", Formatter.capacity(1, null))
        assertEquals("1.0K/2.0K", Formatter.capacity(1024, 2048))
    }

    // ------------------------------------------------------------------ dates

    @Test
    fun `an unknown timestamp is reported as unknown`() {
        assertEquals("未知", Formatter.listDate(null))
        assertEquals("未知", Formatter.listDate(0L))
        assertEquals("未知", Formatter.listDate(-5L))
        assertEquals("未知", Formatter.fullDate(null))
        assertEquals("未知", Formatter.fullDate(0L))
    }

    @Test
    fun `a timestamp from today shows only the time`() {
        val now = System.currentTimeMillis()
        val expected = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

        val rendered = Formatter.listDate(now)

        assertEquals(expected, rendered)
        assertFalse("a same-day entry must not carry a date: $rendered", rendered.contains("-"))
    }

    @Test
    fun `an older timestamp shows a two-digit-year date instead of a time`() {
        val older = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        val expected = Instant.ofEpochMilli(older)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yy-MM-dd", Locale.US))

        val rendered = Formatter.listDate(older)

        assertEquals(expected, rendered)
        assertFalse("an older entry must not carry a time: $rendered", rendered.contains(":"))
    }

    @Test
    fun `the full timestamp carries the year and is minute precision`() {
        val now = System.currentTimeMillis()
        val expected = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US))

        assertEquals(expected, Formatter.fullDate(now))
    }

    // ------------------------------------------------------------------ names, ids, permissions

    @Test
    fun `an unknown name stays unknown rather than becoming empty`() {
        assertEquals("未知", Formatter.name(null))
        assertEquals("未知", Formatter.name(""))
        assertEquals("未知", Formatter.name("   "))
        assertEquals("root", Formatter.name("root"))
    }

    @Test
    fun `an unknown id is unknown while zero is a real id`() {
        assertEquals("未知", Formatter.id(null))
        assertEquals("0", Formatter.id(0))
        assertEquals("2000", Formatter.id(2000))
    }

    @Test
    fun `the permission pair degrades gracefully when the octal is missing`() {
        assertEquals("未知", Formatter.permission(null, "644"))
        assertEquals("-rw-r--r--", Formatter.permission("-rw-r--r--", null))
        assertEquals("-rw-r--r-- (644)", Formatter.permission("-rw-r--r--", "644"))
    }
}
