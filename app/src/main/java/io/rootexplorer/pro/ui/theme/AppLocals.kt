package io.rootexplorer.pro.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import io.rootexplorer.pro.core.image.ImageLoader

/**
 * The shared image loader.
 *
 * A file row needs it to draw a thumbnail, and threading it down through the pane, the list and
 * every row would add a parameter to half the UI for one leaf's benefit. Null means "no thumbnails
 * here", so a preview or a test can render the list without one and simply get icons.
 */
val LocalImageLoader = staticCompositionLocalOf<ImageLoader?> { null }
