package io.rootexplorer.pro.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-list filtering, sorting and export naming.
 *
 * These are the parts of the installed-apps feature that are pure logic, so they are testable
 * without a device. The behaviour asserted here is the same behaviour the screen relies on.
 */
class InstalledAppLogicTest {

    private fun app(
        packageName: String,
        label: String,
        size: Long? = 1000L,
        updated: Long = 1_700_000_000_000L,
        system: Boolean = false,
        versionName: String? = "1.0",
        versionCode: Long = 1,
        baseApk: String? = "/data/app/$packageName/base.apk",
        splits: List<String> = emptyList(),
    ) = InstalledApp(
        packageName = packageName,
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        lastUpdateMillis = updated,
        isSystemApp = system,
        isLaunchable = true,
        baseApkPath = baseApk,
        splitApkPaths = splits,
        installedSizeBytes = size,
        targetSdk = 34,
        minSdk = 26,
    )

    private val sample = listOf(
        app("com.example.beta", "Beta", size = 500, updated = 300),
        app("com.example.alpha", "alpha", size = 9000, updated = 100),
        app("com.example.gamma", "Gamma", size = null, updated = 0),
        app("android.system.thing", "SystemThing", size = 3000, updated = 200, system = true),
    )

    // ------------------------------------------------------------------ filtering

    @Test
    fun `no query and no filter returns everything`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(4, result.size)
    }

    @Test
    fun `query matches the label case-insensitively`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "BET", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(listOf("Beta"), result.map { it.label })
    }

    @Test
    fun `query also matches the package name`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "alpha", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        // Both the label and package match here, so exactly one row.
        assertEquals(1, result.size)
        assertEquals("com.example.alpha", result.first().packageName)

        val byPackage = InstalledAppRepository.filterAndSort(
            sample, query = "android.system", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(1, byPackage.size)
        assertEquals("SystemThing", byPackage.first().label)
    }

    @Test
    fun `surrounding whitespace in the query is ignored`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "  Gamma  ", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(listOf("Gamma"), result.map { it.label })
    }

    @Test
    fun `system filter separates system from user apps`() {
        val system = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.SYSTEM,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(listOf("SystemThing"), system.map { it.label })

        val user = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.USER,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(3, user.size)
        assertFalse(user.any { it.isSystemApp })
    }

    @Test
    fun `query and filter combine`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "System", kind = AppKindFilter.USER,
            sortField = AppSortField.NAME, descending = false,
        )
        assertTrue("a system app must not pass the user filter", result.isEmpty())
    }

    @Test
    fun `a query matching nothing returns an empty list`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "zzz-no-such-app", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        assertTrue(result.isEmpty())
    }

    // ------------------------------------------------------------------ sorting

    @Test
    fun `name sort is case insensitive and reversible`() {
        val ascending = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = false,
        )
        assertEquals(listOf("alpha", "Beta", "Gamma", "SystemThing"), ascending.map { it.label })

        val descending = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.NAME, descending = true,
        )
        assertEquals(listOf("SystemThing", "Gamma", "Beta", "alpha"), descending.map { it.label })
    }

    @Test
    fun `size sort ascending puts the unknown size last`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.SIZE, descending = false,
        )
        assertEquals(listOf("Beta", "SystemThing", "alpha", "Gamma"), result.map { it.label })
        // The unknown one is last, not treated as zero at the front.
        assertEquals(null, result.last().installedSizeBytes)
    }

    @Test
    fun `size sort descending also puts the unknown size last`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.SIZE, descending = true,
        )
        assertEquals(listOf("alpha", "SystemThing", "Beta", "Gamma"), result.map { it.label })
        assertEquals(null, result.last().installedSizeBytes)
    }

    @Test
    fun `update time sort keeps unknown times last in both directions`() {
        val ascending = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.UPDATE_TIME, descending = false,
        )
        // Gamma has updated = 0, which the sort treats as the smallest real value, not unknown.
        assertEquals(listOf("Gamma", "alpha", "SystemThing", "Beta"), ascending.map { it.label })

        val descending = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.UPDATE_TIME, descending = true,
        )
        assertEquals(listOf("Beta", "SystemThing", "alpha", "Gamma"), descending.map { it.label })
    }

    @Test
    fun `package sort orders by package name`() {
        val result = InstalledAppRepository.filterAndSort(
            sample, query = "", kind = AppKindFilter.ALL,
            sortField = AppSortField.PACKAGE, descending = false,
        )
        assertEquals(
            listOf(
                "android.system.thing",
                "com.example.alpha",
                "com.example.beta",
                "com.example.gamma",
            ),
            result.map { it.packageName },
        )
    }

    // ------------------------------------------------------------------ export naming

    @Test
    fun `a single apk app gets a plain descriptive name`() {
        val single = app("com.example.solo", "Solo", versionName = "2.5")
        assertEquals(
            "Solo_2.5.apk",
            InstalledAppRepository.buildExportFileName(single, single.baseApkPath!!, 0),
        )
    }

    @Test
    fun `a bundled app marks the base and each split`() {
        val bundled = app(
            "com.example.bundle",
            "Bundle",
            versionName = "3.0",
            splits = listOf("/data/app/b/split_config.arm64.apk", "/data/app/b/split_config.xxhdpi.apk"),
        )
        val paths = bundled.allApkPaths

        assertEquals("Bundle_3.0_base.apk", InstalledAppRepository.buildExportFileName(bundled, paths[0], 0))
        assertEquals("Bundle_3.0_split1.apk", InstalledAppRepository.buildExportFileName(bundled, paths[1], 1))
        assertEquals("Bundle_3.0_split2.apk", InstalledAppRepository.buildExportFileName(bundled, paths[2], 2))
    }

    @Test
    fun `characters illegal in a file name are replaced`() {
        val nasty = app("com.example.nasty", "Bad/Name:With*Chars?", versionName = "1.0")
        val name = InstalledAppRepository.buildExportFileName(nasty, nasty.baseApkPath!!, 0)

        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertEquals("Bad_Name_With_Chars__1.0.apk", name)
    }

    @Test
    fun `an empty label falls back to the package name`() {
        val blank = app("com.example.blank", "   ", versionName = "1")
        val name = InstalledAppRepository.buildExportFileName(blank, blank.baseApkPath!!, 0)
        assertTrue("the name must identify the app somehow", name.startsWith("com.example.blank"))
    }

    @Test
    fun `a missing version name falls back to the version code`() {
        val noVersion = app("com.example.nv", "NoVersion", versionName = null, versionCode = 42)
        assertEquals(
            "NoVersion_42.apk",
            InstalledAppRepository.buildExportFileName(noVersion, noVersion.baseApkPath!!, 0),
        )
    }

    @Test
    fun `a very long label is truncated`() {
        val longLabel = app("com.example.long", "L".repeat(300), versionName = "1")
        val name = InstalledAppRepository.buildExportFileName(longLabel, longLabel.baseApkPath!!, 0)
        // 60-character label cap plus "_1.apk".
        assertEquals(60 + "_1.apk".length, name.length)
    }

    @Test
    fun `the exported name always ends in apk so it stays installable`() {
        val cases = listOf(
            app("a", "A", versionName = "1"),
            app("b", "B", versionName = null, versionCode = 7),
            app("c", "C", splits = listOf("/x/1.apk")),
        )
        for (candidate in cases) {
            val name = InstalledAppRepository.buildExportFileName(
                candidate,
                candidate.allApkPaths.first(),
                0,
            )
            assertTrue("name must end in .apk: $name", name.endsWith(".apk"))
        }
    }

    // ------------------------------------------------------------------ model helpers

    @Test
    fun `allApkPaths includes the base and every split`() {
        val bundled = app(
            "com.example.b",
            "B",
            splits = listOf("/d/s1.apk", "/d/s2.apk"),
        )
        assertEquals(3, bundled.allApkPaths.size)
        assertEquals("/d/s1.apk", bundled.allApkPaths[1])
        assertTrue(bundled.isSplit)
    }

    @Test
    fun `a single apk app is not reported as split`() {
        assertFalse(app("com.example.s", "S").isSplit)
    }

    @Test
    fun `an app with no apk path still builds a name rather than crashing`() {
        val orphan = app("com.example.o", "Orphan", baseApk = null)
        assertTrue(orphan.allApkPaths.isEmpty())
        val name = InstalledAppRepository.buildExportFileName(orphan, "/nonexistent", 0)
        assertTrue(name.endsWith(".apk"))
    }
}
