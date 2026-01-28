package com.example.hearingaidapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val waveformPaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        strokeWidth = 2f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val centerLinePaint = Paint().apply {
        color = Color.parseColor("#666666")
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1a1a1a")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
        isAntiAlias = true
    }

    private var audioBuffer: FloatArray? = null
    private val maxDisplaySamples = 512
    private var currentIndex = 0
    private val displayBuffer = FloatArray(maxDisplaySamples)

    fun updateWaveform(buffer: ShortArray) {
        // Convert to float and add to circular buffer
        for (sample in buffer) {
            displayBuffer[currentIndex] = sample.toFloat() / Short.MAX_VALUE
            currentIndex = (currentIndex + 1) % maxDisplaySamples
        }

        // Trigger redraw on UI thread
        post { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerY = height / 2

        // Draw background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        // Draw grid lines
        val gridLines = 5
        for (i in 1 until gridLines) {
            val y = (height / gridLines) * i
            canvas.drawLine(0f, y, width, y, gridPaint)
        }

        // Draw center line
        canvas.drawLine(0f, centerY, width, centerY, centerLinePaint)

        // Draw waveform
        if (displayBuffer.isNotEmpty()) {
            val path = Path()
            var started = false

            for (i in 0 until maxDisplaySamples) {
                val bufferIndex = (currentIndex + i) % maxDisplaySamples
                val sample = displayBuffer[bufferIndex]

                val x = (i.toFloat() / maxDisplaySamples) * width
                val y = centerY - (sample * centerY * 0.8f) // Scale to 80% of height

                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(path, waveformPaint)
        }

        // Draw border
        canvas.drawRect(0f, 0f, width, height, Paint().apply {
            color = Color.parseColor("#4CAF50")
            strokeWidth = 3f
            style = Paint.Style.STROKE
        })
    }
}
