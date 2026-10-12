package io.rootexplorer.pro.feature.image

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.rootexplorer.pro.core.image.ImageLoad
import io.rootexplorer.pro.core.image.ImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the image viewer. */
data class ImageUiState(
    val path: String = "",
    /** Null while the decode is still running. */
    val load: ImageLoad? = null,
)

/**
 * Holds the decoded picture for the viewer.
 *
 * The decode happens in the view model rather than in the composition so it can be cancelled when
 * the user moves to another picture, and so a large image cannot block a frame.
 *
 * The decoded bitmap is deliberately **not** recycled when it is replaced. Its pixels are native
 * memory the collector already tracks, and the only reason to call `recycle()` would be to free
 * it sooner — at the risk of recycling a bitmap a frame is still drawing, which crashes the
 * renderer. Cancelling the decode already bounds how many bitmaps can be in flight at once.
 */
class ImageViewModel(private val loader: ImageLoader) : ViewModel() {

    private val _state = MutableStateFlow(ImageUiState())
    val state: StateFlow<ImageUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Decode [path], replacing whatever was shown before. */
    fun open(path: String) {
        job?.cancel()
        _state.value = ImageUiState(path = path)

        job = viewModelScope.launch {
            val load = loader.load(path)
            _state.update { it.copy(load = load) }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
