package io.rootexplorer.pro.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission conversions the properties dialog and the permission editor both depend on.
 *
 * These are the tests the brief asks for by name: 644 -> rw-r--r--, 755 -> rwxr-xr-x,
 * 600 -> rw-------, 777 -> rwxrwxrwx, and the special bits.
 */
class LinuxPermissionTest {

    @Test
    fun `octal 644 renders as rw-r--r--`() {
        val permission = LinuxPermission.fromOctal("644")!!
        assertEquals("rw-r--r--", permission.symbolic)
        assertEquals("644", permission.octalSimple)
    }

    @Test
    fun `octal 755 renders as rwxr-xr-x`() {
        val permission = LinuxPermission.fromOctal("755")!!
        assertEquals("rwxr-xr-x", permission.symbolic)
        assertEquals("755", permission.octalSimple)
    }

    @Test
    fun `octal 600 renders as rw-------`() {
        val permission = LinuxPermission.fromOctal("600")!!
        assertEquals("rw-------", permission.symbolic)
        assertEquals("600", permission.octalSimple)
    }

    @Test
    fun `octal 777 renders as rwxrwxrwx`() {
        val permission = LinuxPermission.fromOctal("777")!!
        assertEquals("rwxrwxrwx", permission.symbolic)
        assertEquals("777", permission.octalSimple)
    }

    @Test
    fun `octal 000 renders as ---------`() {
        val permission = LinuxPermission.fromOctal("000")!!
        assertEquals("---------", permission.symbolic)
        assertEquals("000", permission.octalSimple)
        assertEquals(0, permission.modeBits)
    }

    @Test
    fun `mode bits round trip through octal and back`() {
        for (octal in listOf("000", "111", "644", "700", "755", "777", "400", "060", "006")) {
            val permission = LinuxPermission.fromOctal(octal)!!
            assertEquals(octal, permission.octalSimple)
            assertEquals(permission.modeBits, LinuxPermission.fromMode(permission.modeBits).modeBits)
        }
    }

    @Test
    fun `display includes the file type character`() {
        val permission = LinuxPermission.fromOctal("644")!!
        assertEquals("-rw-r--r--", permission.display('-'))
        assertEquals("drwxr-xr-x", LinuxPermission.fromOctal("755")!!.display('d'))
        assertEquals("lrwxrwxrwx", LinuxPermission.fromOctal("777")!!.display('l'))
    }

    @Test
    fun `setuid is displayed as s when execute is set`() {
        // 4755 = setuid + rwxr-xr-x
        val permission = LinuxPermission.fromOctal("4755")!!
        assertEquals("rwsr-xr-x", permission.symbolic)
        assertTrue(permission.setUid)
        assertTrue(permission.hasSpecialBits)
    }

    @Test
    fun `setuid is displayed as capital S when execute is clear`() {
        // 4644 = setuid without owner execute
        val permission = LinuxPermission.fromOctal("4644")!!
        assertEquals("rwSr--r--", permission.symbolic)
        assertTrue(permission.setUid)
    }

    @Test
    fun `setgid and sticky bits are parsed and rendered`() {
        val setgid = LinuxPermission.fromOctal("2755")!!
        assertEquals("rwxr-sr-x", setgid.symbolic)
        assertTrue(setgid.setGid)

        val sticky = LinuxPermission.fromOctal("1777")!!
        assertEquals("rwxrwxrwt", sticky.symbolic)
        assertTrue(sticky.sticky)
    }

    @Test
    fun `symbolic parsing round trips with special bits`() {
        for (octal in listOf("644", "755", "4755", "2755", "1777", "4644", "2644", "1644")) {
            val original = LinuxPermission.fromOctal(octal)!!
            val reparsed = LinuxPermission.fromSymbolic(original.symbolic)!!
            assertEquals("failed for $octal", original.modeBits, reparsed.modeBits)
        }
    }

    @Test
    fun `fromSymbolic accepts a leading file type character`() {
        val fromShort = LinuxPermission.fromSymbolic("rw-r--r--")!!
        val fromLong = LinuxPermission.fromSymbolic("-rw-r--r--")!!
        assertEquals(fromShort.modeBits, fromLong.modeBits)
        assertEquals(0b110_100_100, fromShort.modeBits)
    }

    @Test
    fun `invalid octal input returns null instead of a wrong mode`() {
        assertNull(LinuxPermission.fromOctal(""))
        assertNull(LinuxPermission.fromOctal("8"))
        assertNull(LinuxPermission.fromOctal("64a"))
        assertNull(LinuxPermission.fromOctal("64444"))
        assertNull(LinuxPermission.fromOctal("-644"))
        assertNull(LinuxPermission.fromOctal("rwx"))
    }

    @Test
    fun `invalid symbolic input returns null`() {
        assertNull(LinuxPermission.fromSymbolic("rw"))
        assertNull(LinuxPermission.fromSymbolic("rw-r--r--x"))
        assertNull(LinuxPermission.fromSymbolic(""))
    }

    @Test
    fun `modeBits masks off the file type bits`() {
        // Kotlin has no octal literals, so these st_mode values are written in decimal.
        // A regular file's st_mode is 0o100644 == 33188: type bits sit above the mode bits.
        assertEquals("644", LinuxPermission.fromMode(33188).octalSimple)
        // A directory: 0o040755 == 16877.
        assertEquals("755", LinuxPermission.fromMode(16877).octalSimple)
        // A symlink: 0o120777 == 41471.
        assertEquals("777", LinuxPermission.fromMode(41471).octalSimple)
        // With type bits set, only the low 12 permission bits are reported. 0x333 == 0b001100110011,
        // which grouped in threes is 0o1463 — the low 12 bits are 0o463.
        assertEquals("463", LinuxPermission.fromMode(0x000FF333).octalSimple)
    }

    @Test
    fun `full octal reports four digits including special bits`() {
        assertEquals("0644", LinuxPermission.fromOctal("644")!!.octal)
        assertEquals("4755", LinuxPermission.fromOctal("4755")!!.octal)
    }

    @Test
    fun `no special bits means hasSpecialBits is false`() {
        assertFalse(LinuxPermission.fromOctal("777")!!.hasSpecialBits)
    }
}
