package io.rootexplorer.pro.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The subsampling arithmetic.
 *
 * This decides how many megapixels the viewer actually allocates, so it is the difference between
 * a picture opening and the app dying with an OutOfMemoryError. `BitmapFactory` only honours powers
 * of two, and both 0 and 1 mean "no subsampling", which is why the result must never drop below 1.
 */
class ImageLoaderTest {

    @Test
    fun `an image already within the limit is not subsampled`() {
        assertEquals(1, ImageLoader.sampleSizeFor(1024, 768, 2048))
        assertEquals(1, ImageLoader.sampleSizeFor(2048, 1000, 2048))
    }

    @Test
    fun `the sample is the smallest power of two that fits`() {
        assertEquals(2, ImageLoader.sampleSizeFor(4096, 3072, 2048))
        assertEquals(4, ImageLoader.sampleSizeFor(8000, 6000, 2048))
        assertEquals(8, ImageLoader.sampleSizeFor(16000, 100, 2048))
    }

    @Test
    fun `the longest edge decides, not the width`() {
        // A tall photo has to subsample on its height.
        assertEquals(4, ImageLoader.sampleSizeFor(1000, 8192, 2048))
    }

    @Test
    fun `a nonsensical dimension falls back to no subsampling rather than looping`() {
        assertEquals(1, ImageLoader.sampleSizeFor(0, 0, 2048))
        assertEquals(1, ImageLoader.sampleSizeFor(-1, 100, 2048))
        assertEquals(1, ImageLoader.sampleSizeFor(100, 100, 0))
    }
}
