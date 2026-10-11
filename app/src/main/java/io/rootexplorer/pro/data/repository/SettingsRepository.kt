package io.rootexplorer.pro.data.repository

import android.content.Context
import androidx.core.content.edit
import io.rootexplorer.pro.core.filesystem.FileSortField
import io.rootexplorer.pro.core.filesystem.SortDirection
import io.rootexplorer.pro.ui.theme.ThemeMode

/** Data the app remembers between launches. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val showHiddenFiles: Boolean = false,
    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val homePath: String = "/storage/emulated/0",
    val bookmarks: List<String> = emptyList(),
)

/**
 * Settings storage.
 *
 * P0 uses [android.content.SharedPreferences] rather than DataStore: it needs no extra
 * dependency, is synchronous enough for the handful of values here, and lets P0 stay
 * focused. The brief allows either; moving to DataStore is listed as P4 work.
 *
 * Every read is defensive — a corrupted or partially written preference must not crash the
 * app at launch, so enum lookups fall back to defaults.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("root_explorer_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        themeMode = enumOrDefault(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
        showHiddenFiles = prefs.getBoolean(KEY_SHOW_HIDDEN, false),
        sortField = enumOrDefault(prefs.getString(KEY_SORT_FIELD, null), FileSortField.NAME),
        sortDirection = enumOrDefault(prefs.getString(KEY_SORT_DIR, null), SortDirection.ASCENDING),
        homePath = prefs.getString(KEY_HOME, null)?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0",
        bookmarks = prefs.getStringSet(KEY_BOOKMARKS, emptySet())?.sorted() ?: emptyList(),
    )

    fun saveTheme(mode: ThemeMode) = prefs.edit { putString(KEY_THEME, mode.name) }

    fun saveShowHidden(show: Boolean) = prefs.edit { putBoolean(KEY_SHOW_HIDDEN, show) }

    fun saveSort(field: FileSortField, direction: SortDirection) = prefs.edit {
        putString(KEY_SORT_FIELD, field.name)
        putString(KEY_SORT_DIR, direction.name)
    }

    fun saveHome(path: String) = prefs.edit { putString(KEY_HOME, path) }

    /** Add a bookmark. Returns the resulting set so callers can update their state. */
    fun addBookmark(path: String): List<String> {
        val current = prefs.getStringSet(KEY_BOOKMARKS, emptySet()).orEmpty().toMutableSet()
        current += path
        prefs.edit { putStringSet(KEY_BOOKMARKS, current) }
        return current.sorted()
    }

    /** Remove a bookmark. */
    fun removeBookmark(path: String): List<String> {
        val current = prefs.getStringSet(KEY_BOOKMARKS, emptySet()).orEmpty().toMutableSet()
        current -= path
        prefs.edit { putStringSet(KEY_BOOKMARKS, current) }
        return current.sorted()
    }

    fun isBookmarked(path: String): Boolean =
        prefs.getStringSet(KEY_BOOKMARKS, emptySet()).orEmpty().contains(path)

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, fallback: T): T {
        if (name == null) return fallback
        return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_SHOW_HIDDEN = "show_hidden"
        const val KEY_SORT_FIELD = "sort_field"
        const val KEY_SORT_DIR = "sort_direction"
        const val KEY_HOME = "home_path"
        const val KEY_BOOKMARKS = "bookmarks"
    }
}
