package com.s24fe.nativecamera.camera

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler

/**
 * Cheap exposure tools from a small YUV preview stream — NOT from the full-res
 * preview. The Y plane alone gives luminance, which is all a histogram and
 * zebra stripes need. Frames are throttled so this never heats the phone.
 *
 * Zebra output: a small bitmap where over-threshold pixels are white and the
 * rest transparent. The UI scales it over the preview.
 */
class PreviewAnalyzer(
    width: Int,
    height: Int,
    private val handler: Handler,
    private val onResult: (histogram: IntArray, zebra: Bitmap) -> Unit
) {
    val reader: ImageReader =
        ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, 2)

    @Volatile var threshold: Int = 235
    private var lastProcessMs = 0L

    init {
        reader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            if (now - lastProcessMs < 150) {
                // Drop the frame, but always close it — leaked images stall the stream.
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastProcessMs = now
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val w = image.width
                val h = image.height
                val yPlane = image.planes[0]
                val buffer = yPlane.buffer
                val rowStride = yPlane.rowStride
                val histogram = IntArray(256)
                // Sample every 2nd pixel: enough for a histogram and zebra at this size.
                val pixels = IntArray(w * h)
                for (y in 0 until h step 2) {
                    for (x in 0 until w step 2) {
                        val lum = buffer.get(y * rowStride + x).toInt() and 0xFF
                        histogram[lum]++
                        if (lum >= threshold) {
                            pixels[y * w + x] = 0x80FFFFFF.toInt()
                        }
                    }
                }
                val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                handler.post { onResult(histogram, bitmap) }
            } finally {
                image.close()
            }
        }, handler)
    }

    fun close() {
        try {
            reader.close()
        } catch (ignored: Exception) {
        }
    }
}
