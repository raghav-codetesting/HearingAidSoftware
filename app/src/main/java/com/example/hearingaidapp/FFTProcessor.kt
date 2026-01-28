package com.example.hearingaidapp

import kotlin.math.*

class FFTProcessor(private val fftSize: Int = 1024) {

    // Complex number representation
    data class Complex(val real: Double, val imag: Double) {
        operator fun plus(other: Complex) = Complex(real + other.real, imag + other.imag)
        operator fun minus(other: Complex) = Complex(real - other.real, imag - other.imag)
        operator fun times(other: Complex) = Complex(
            real * other.real - imag * other.imag,
            real * other.imag + imag * other.real
        )

        fun magnitude() = sqrt(real * real + imag * imag)
    }

    private val inputBuffer = FloatArray(fftSize)
    private var bufferIndex = 0

    fun addSamples(samples: ShortArray) {
        for (sample in samples) {
            if (bufferIndex < fftSize) {
                inputBuffer[bufferIndex] = sample.toFloat() / Short.MAX_VALUE
                bufferIndex++
            }
        }
    }

    fun isReady(): Boolean = bufferIndex >= fftSize

    fun computeFFT(): FloatArray {
        if (!isReady()) return FloatArray(fftSize / 2)

        // Convert to complex numbers
        val complexInput = Array(fftSize) { i ->
            Complex(inputBuffer[i].toDouble(), 0.0)
        }

        // Perform FFT
        val fftResult = fft(complexInput)

        // Calculate magnitudes (only first half - due to symmetry)
        val magnitudes = FloatArray(fftSize / 2)
        for (i in 0 until fftSize / 2) {
            magnitudes[i] = fftResult[i].magnitude().toFloat()
        }

        // Reset buffer for next calculation
        bufferIndex = 0

        return magnitudes
    }

    // Cooley-Tukey FFT algorithm
    private fun fft(input: Array<Complex>): Array<Complex> {
        val n = input.size
        if (n <= 1) return input

        // Divide
        val even = Array(n / 2) { input[2 * it] }
        val odd = Array(n / 2) { input[2 * it + 1] }

        // Conquer
        val evenFFT = fft(even)
        val oddFFT = fft(odd)

        // Combine
        val result = Array(n) { Complex(0.0, 0.0) }
        for (k in 0 until n / 2) {
            val t = Complex(
                cos(-2.0 * PI * k / n),
                sin(-2.0 * PI * k / n)
            ) * oddFFT[k]

            result[k] = evenFFT[k] + t
            result[k + n / 2] = evenFFT[k] - t
        }

        return result
    }

    fun getFrequencyForBin(binIndex: Int, sampleRate: Int): Float {
        return (binIndex.toFloat() * sampleRate) / (2 * fftSize)
    }
}
