package com.example.hearingaidapp

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val backgroundPaint = Paint().apply {
        color = Color.parseColor("#1a1a1a")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
        textSize = 20f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
        isAntiAlias = true
    }

    private val numBars = 64  // More bars for better resolution
    private val magnitudes = FloatArray(numBars)
    private val smoothedMagnitudes = FloatArray(numBars)
    private val frequencyLabels = arrayOf("60Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")

    // Color gradient for frequency spectrum
    private val colors = intArrayOf(
        Color.parseColor("#1B5E20"),  // Dark green - low frequencies
        Color.parseColor("#2E7D32"),  // Green
        Color.parseColor("#43A047"),  // Light green
        Color.parseColor("#66BB6A"),  // Lime green
        Color.parseColor("#FDD835"),  // Yellow - mid frequencies
        Color.parseColor("#FFB300"),  // Amber
        Color.parseColor("#FB8C00"),  // Orange
        Color.parseColor("#F57400"),  // Deep orange
        Color.parseColor("#E53935"),  // Red - high frequencies
        Color.parseColor("#C62828")   // Dark red
    )

    fun updateSpectrum(fftMagnitudes: FloatArray) {
        if (fftMagnitudes.isEmpty()) return

        // Map FFT bins to display bars with logarithmic frequency scaling
        for (i in 0 until numBars) {
            // Logarithmic frequency mapping for better visualization
            val logIndex = (exp(i.toDouble() / numBars * ln(fftMagnitudes.size.toDouble())) - 1).toInt()
                .coerceIn(0, fftMagnitudes.size - 1)

            // Apply logarithmic scaling to magnitude (dB scale)
            val magnitude = if (fftMagnitudes[logIndex] > 0) {
                20 * log10(fftMagnitudes[logIndex] + 1e-10) / 60.0 + 1.0  // Normalize to 0-1 range
            } else 0.0

            magnitudes[i] = magnitude.toFloat().coerceIn(0f, 1f)

            // Smooth the display for less jittery visualization
            smoothedMagnitudes[i] = smoothedMagnitudes[i] * 0.7f + magnitudes[i] * 0.3f
        }

        post { invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Draw background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)

        // Draw frequency grid lines
        val gridLines = 5
        for (i in 1 until gridLines) {
            val y = height - (height / gridLines) * i
            canvas.drawLine(0f, y, width, y, gridPaint)
        }

        // Draw spectrum bars
        val barWidth = width / numBars
        val padding = barWidth * 0.1f

        for (i in 0 until numBars) {
            val barHeight = smoothedMagnitudes[i] * height * 0.9f
            val x = i * barWidth + padding
            val y = height - barHeight

            // Choose color based on frequency (lower index = lower frequency)
            val colorIndex = (i.toFloat() / numBars * colors.size).toInt()
                .coerceIn(0, colors.size - 1)
            barPaint.color = colors[colorIndex]

            // Draw the bar with rounded top
            val rect = RectF(x, y, x + barWidth - padding, height)
            canvas.drawRoundRect(rect, 4f, 4f, barPaint)

            // Add peak dots for high values
            if (smoothedMagnitudes[i] > 0.8f) {
                barPaint.color = Color.WHITE
                canvas.drawCircle(x + (barWidth - padding) / 2, y - 5f, 3f, barPaint)
                barPaint.color = colors[colorIndex] // Reset color
            }
        }

        // Draw frequency labels
        val labelPositions = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 63)
        for (i in frequencyLabels.indices) {
            if (i < labelPositions.size) {
                val x = labelPositions[i] * barWidth
                canvas.drawText(frequencyLabels[i], x + 5f, height - 5f, textPaint)
            }
        }

        // Draw title
        textPaint.textSize = 24f
        canvas.drawText("Real-Time FFT Spectrum", 10f, 25f, textPaint)
        textPaint.textSize = 20f  // Reset

        // Draw border
        canvas.drawRect(0f, 0f, width, height, Paint().apply {
            color = Color.parseColor("#FF9800")
            strokeWidth = 3f
            style = Paint.Style.STROKE
        })
    }
}
