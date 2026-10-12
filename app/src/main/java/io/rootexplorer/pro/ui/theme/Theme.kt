package io.rootexplorer.pro.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.rootexplorer.pro.core.image.ImageLoader

/** Which theme the user asked for. */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }
}

/**
 * The palette.
 *
 * The reference design is a professional file manager rather than a media app: a dark slate
 * tool bar, a near-white list surface, very small accent usage, and colour reserved for
 * meaning (which file type, what is selected, what is dangerous). These values are our own;
 * nothing is copied from the reference application's assets.
 */
private val SlateToolbar = Color(0xFF2B2F36)
private val SlateToolbarDark = Color(0xFF1B1E23)
private val AccentBlue = Color(0xFF2E7DF6)
private val AccentBlueDark = Color(0xFF6FA8FF)

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E5FF),
    onPrimaryContainer = Color(0xFF0A2E63),
    secondary = Color(0xFF4A5568),
    onSecondary = Color.White,
    background = Color(0xFFF4F5F7),
    onBackground = Color(0xFF15181C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15181C),
    surfaceVariant = Color(0xFFE8EAEE),
    onSurfaceVariant = Color(0xFF5A616B),
    outline = Color(0xFFC6CAD1),
    outlineVariant = Color(0xFFDFE2E7),
    error = Color(0xFFC0392B),
    onError = Color.White,
    errorContainer = Color(0xFFFBDDD8),
    onErrorContainer = Color(0xFF5C1A12),
)

private val DarkColors = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFA9B2C0),
    onSecondary = Color(0xFF15181C),
    background = Color(0xFF15171A),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF1C1F24),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF262A31),
    onSurfaceVariant = Color(0xFFAEB6C2),
    outline = Color(0xFF3A4049),
    outlineVariant = Color(0xFF2C313A),
    error = Color(0xFFFF8A78),
    onError = Color(0xFF3A0A04),
    errorContainer = Color(0xFF57211A),
    onErrorContainer = Color(0xFFFFD9D2),
)

/** Semantic colours the file list needs that Material 3 does not name. */
data class FileManagerColors(
    /** The dark tool bar strip at the top, which stays dark in both themes. */
    val toolbar: Color,
    val onToolbar: Color,
    val toolbarSecondary: Color,
    val folderIcon: Color,
    val fileIcon: Color,
    val hiddenIcon: Color,
    val symlinkIcon: Color,
    val selectedRow: Color,
    val divider: Color,
    val danger: Color,
    val rootBadge: Color,
    val bannerBackground: Color,
    val bannerText: Color,
)

private val LightFileManagerColors = FileManagerColors(
    toolbar = SlateToolbar,
    onToolbar = Color(0xFFF2F4F8),
    toolbarSecondary = Color(0xFFAEB6C4),
    folderIcon = Color(0xFF3C4450),
    fileIcon = Color(0xFF6B7383),
    hiddenIcon = Color(0xFF9AA1AC),
    symlinkIcon = Color(0xFF2E9E6B),
    selectedRow = Color(0xFFDCE8FF),
    divider = Color(0xFFE4E7EC),
    danger = Color(0xFFC0392B),
    rootBadge = Color(0xFF2E7DF6),
    bannerBackground = Color(0xFFFFF3D6),
    bannerText = Color(0xFF6B4A05),
)

private val DarkFileManagerColors = FileManagerColors(
    toolbar = SlateToolbarDark,
    onToolbar = Color(0xFFEDEFF3),
    toolbarSecondary = Color(0xFF98A1B0),
    folderIcon = Color(0xFFB9C3D4),
    fileIcon = Color(0xFF8D96A6),
    hiddenIcon = Color(0xFF6E7686),
    symlinkIcon = Color(0xFF4FC08D),
    selectedRow = Color(0xFF27364D),
    divider = Color(0xFF2A2F37),
    danger = Color(0xFFFF8A78),
    rootBadge = Color(0xFF6FA8FF),
    bannerBackground = Color(0xFF3A3018),
    bannerText = Color(0xFFF0D9A0),
)

val LocalFileManagerColors = staticCompositionLocalOf { LightFileManagerColors }

/**
 * A compact typography scale.
 *
 * A file manager shows a lot of rows on screen at once, so the default Material 3 sizes are
 * reduced and the list rows use a tighter line height. The hierarchy stays explicit: the
 * file name is the largest text in a row, the metadata underneath is clearly secondary.
 */
private val FileManagerTypography = Typography().run {
    copy(
        titleMedium = titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontSize = 13.5.sp, lineHeight = 17.sp),
        bodyMedium = bodyMedium.copy(fontSize = 12.5.sp, lineHeight = 16.sp),
        bodySmall = bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
        labelLarge = labelLarge.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontSize = 10.5.sp),
        labelSmall = labelSmall.copy(fontSize = 9.5.sp),
    )
}

/** Monospace style for paths, permission strings and terminal output. */
val MonospaceTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.5.sp,
    lineHeight = 15.sp,
)

/** Small text used for the secondary metadata line under a file name. */
val MetaTextStyle = TextStyle(
    fontSize = 10.5.sp,
    lineHeight = 13.sp,
)

/** Corner radius used by the compact tool bars and list surfaces. */
val CompactCorner = 8.dp

/**
 * Applies the app theme.
 *
 * [mode] is chosen by the user in settings and persisted; [darkTheme] resolves SYSTEM
 * against the platform setting.
 */
@Composable
fun RootExplorerTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * The shared image loader, so a file row can draw a thumbnail.
     *
     * Passed here rather than threaded through every screen and pane: it is app-scoped, not screen
     * state, and a thumbnail and the full-size viewer must share one cache to be worth anything.
     */
    imageLoader: ImageLoader? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) DarkColors else LightColors
    val fileManagerColors = if (darkTheme) DarkFileManagerColors else LightFileManagerColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // The top bar is painted by Compose edge to edge, so the system bar icons have to
            // follow the theme rather than the window background.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalFileManagerColors provides fileManagerColors,
        LocalImageLoader provides imageLoader,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FileManagerTypography,
            content = content,
        )
    }
}

/** The current semantic file-manager colours. */
object FileManagerTheme {
    val colors: FileManagerColors
        @Composable get() = LocalFileManagerColors.current
}
