package io.rootexplorer.pro.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The outcome of decoding one image. */
sealed interface ImageLoad {

    data class Ready(
        val bitmap: Bitmap,
        /** Pixel dimensions actually decoded, which may be smaller than the file's. */
        val width: Int,
        val height: Int,
        val byteSize: Long,
        /** True when the image was subsampled, so the pixels are not 1:1 with the file. */
        val downsampled: Boolean,
    ) : ImageLoad

    data class Failed(val reason: String) : ImageLoad
}

/**
 * Decodes images for the viewer.
 *
 * The decode is two passes on purpose. `inJustDecodeBounds` reads the header only, so the sample
 * size is chosen before a single pixel is allocated: a 50-megapixel photo decoded at full size is
 * 200 MB of heap and an immediate OOM, while the same photo subsampled to the screen's needs is a
 * few megabytes and visually identical.
 *
 * Nothing here guesses whether a file *is* an image. `BitmapFactory` returning null is the answer,
 * and the caller reports it rather than showing an empty screen.
 */
class ImageLoader(private val maxDimension: Int = DEFAULT_MAX_DIMENSION) {

    /**
     * Small decodes for the file list, keyed by path.
     *
     * The list re-composes rows constantly while scrolling, so decoding per composition would be
     * both slow and pointless, and returning to a directory must not decode everything again.
     * Access is synchronised because decodes happen on the IO dispatcher while the list reads on
     * the main thread.
     */
    private val thumbnails = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > MAX_THUMBNAILS
    }

    /** Decode [path], or explain why it could not be shown. Never throws. */
    suspend fun load(path: String): ImageLoad = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext ImageLoad.Failed("文件不存在：$path")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return@withContext ImageLoad.Failed("无法识别为图片格式：${file.name}")
        }

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        // OutOfMemoryError is an Error, not an Exception, so it needs its own catch: a
        // largeHeap app can still be asked for more than the heap has.
        val bitmap = try {
            BitmapFactory.decodeFile(path, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return@withContext ImageLoad.Failed(
            "图片过大，内存不足（${bounds.outWidth}×${bounds.outHeight}）",
        )

        ImageLoad.Ready(
            bitmap = bitmap,
            width = bitmap.width,
            height = bitmap.height,
            byteSize = file.length(),
            downsampled = sample > 1,
        )
    }

    /**
     * A small decode of [path] for a list row, or null when it is not an image.
     *
     * Sampled to [maxEdge] on the longest edge and cached by path. The returned bitmap is never
     * recycled: an evicted entry simply becomes garbage, because recycling one that a frame is
     * still drawing crashes the renderer.
     */
    suspend fun thumbnail(path: String, maxEdge: Int = THUMBNAIL_EDGE): Bitmap? =
        withContext(Dispatchers.IO) {
            synchronized(thumbnails) { thumbnails[path] }?.let { return@withContext it }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
                // Two bytes per pixel is plenty for a 128px thumbnail, and halves what it holds.
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = try {
                BitmapFactory.decodeFile(path, options)
            } catch (e: OutOfMemoryError) {
                null
            } ?: return@withContext null

            synchronized(thumbnails) { thumbnails[path] = bitmap }
            bitmap
        }

    companion object {
        /**
         * Longest edge the viewer decodes to.
         *
         * 2048 is deliberately generous for a phone: a 1080-wide screen cannot show more, and
         * 2048² of ARGB_8888 is 16 MB rather than the 64 MB a 4096² decode would hold.
         */
        const val DEFAULT_MAX_DIMENSION = 2048

        /** Longest edge of a list thumbnail. Comfortably above the 24dp square it is shown in. */
        const val THUMBNAIL_EDGE = 128

        /** How many thumbnails stay decoded. At ~32 KB each this is a few megabytes. */
        private const val MAX_THUMBNAILS = 192

        /**
         * The smallest power-of-two subsample that keeps the longest edge at or below
         * [maxDimension].
         *
         * `BitmapFactory` only honours powers of two, and `inSampleSize` of 0 or 1 both mean
         * "no subsampling", so the result is never below 1. Pure, so the arithmetic is testable
         * without a device.
         */
        fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
            if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
            val longest = maxOf(width, height)
            var sample = 1
            while (longest / sample > maxDimension) {
                sample *= 2
            }
            return sample
        }
    }
}
