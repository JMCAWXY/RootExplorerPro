package io.rootexplorer.pro.feature.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rootexplorer.pro.core.image.ImageLoad
import io.rootexplorer.pro.ui.theme.MetaTextStyle
import io.rootexplorer.pro.util.Formatter

/** The most the viewer will magnify, and the point at which "reset" stops being offered. */
private const val MAX_ZOOM = 8f

/**
 * A full-screen image viewer.
 *
 * Pinch to zoom and drag to pan. The pan is clamped to the container so the picture can never be
 * dragged out of view, and a reset control appears as soon as the view is not at fit — which is
 * also the way out of any transform the user did not mean to make.
 *
 * The black backdrop is deliberate: it is the neutral background a picture is judged against, and
 * it is the same in both themes.
 */
@Composable
fun ImageScreen(
    state: ImageUiState,
    displayName: String,
    onBack: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // A different picture starts at fit, not wherever the last one was left.
    LaunchedEffect(state.path) {
        scale = 1f
        offset = Offset.Zero
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                (state.load as? ImageLoad.Ready)?.let { ready ->
                    Text(
                        text = "${ready.width}×${ready.height} · ${Formatter.size(ready.byteSize)}" +
                            if (ready.downsampled) "（已按屏幕缩放）" else "",
                        style = MetaTextStyle,
                        color = Color(0xFFB0B6C0),
                    )
                }
            }
            if (scale != 1f || offset != Offset.Zero) {
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                    Text("重置", color = Color.White)
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()

            when (val load = state.load) {
                null -> CircularProgressIndicator(color = Color.White)

                is ImageLoad.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF8A78),
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = load.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "只有图片格式的文件可以在这里查看。",
                        style = MetaTextStyle,
                        color = Color(0xFFB0B6C0),
                    )
                }

                is ImageLoad.Ready -> {
                    val image = remember(load.bitmap) { load.bitmap.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.path) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                    // Generous clamp: at scale s the content can only be panned by
                                    // (s-1)/2 of the container before an edge would show.
                                    val limitX = containerWidth * (scale - 1f) / 2f
                                    val limitY = containerHeight * (scale - 1f) / 2f
                                    val next = offset + pan
                                    offset = Offset(
                                        x = if (limitX <= 0f) 0f else next.x.coerceIn(-limitX, limitX),
                                        y = if (limitY <= 0f) 0f else next.y.coerceIn(-limitY, limitY),
                                    )
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                    )
                }
            }
        }
    }
}
