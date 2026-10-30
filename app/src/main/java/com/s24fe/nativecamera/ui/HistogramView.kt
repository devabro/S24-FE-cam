package com.s24fe.nativecamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Simple luminance histogram. White bars on transparent background. */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private var bins: IntArray = IntArray(256)

    fun setHistogram(histogram: IntArray) {
        bins = histogram
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val max = bins.maxOrNull()?.takeIf { it > 0 } ?: return
        val barWidth = width / 256f
        for (i in bins.indices) {
            val h = bins[i].toFloat() / max * height
            canvas.drawRect(i * barWidth, height - h, (i + 1) * barWidth, height.toFloat(), paint)
        }
    }
}
