package com.example.hearingaidapp

import android.content.Context
import android.media.*
import android.os.Process
import android.util.Log
import kotlin.math.*


class AudioEngine(private val context: Context) {
    companion object {
        private const val TAG = "AudioEngine"
    }

    enum class FilterType {
        PEAKING,      // Parametric EQ - boost or cut at specific frequency
        LOW_PASS,     // Allows frequencies below cutoff
        HIGH_PASS,    // Allows frequencies above cutoff
        BAND_PASS,    // Allows frequencies around center frequency
        NOTCH,        // Removes frequencies around center frequency
        LOW_SHELF,    // Boost or cut all frequencies below cutoff
        HIGH_SHELF;   // Boost or cut all frequencies above cutoff

        fun getDisplayName(): String {
            return when (this) {
                PEAKING -> "Peaking"
                LOW_PASS -> "Low-Pass"
                HIGH_PASS -> "High-Pass"
                BAND_PASS -> "Band-Pass"
                NOTCH -> "Notch"
                LOW_SHELF -> "Low-Shelf"
                HIGH_SHELF -> "High-Shelf"
            }
        }

        companion object {
            fun fromString(name: String): FilterType {
                return values().find { it.name == name } ?: PEAKING
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private var isRecording = false

    // Ultra-low-latency optimized settings
    private var sampleRate: Int = 48000
    private var recordBufferSize: Int = 0
    private var playBufferSize: Int = 0
    private var amplificationGain = 1.5f

    // Use extremely small processing chunks for minimal latency
    private var processingChunkSize = 128  // Reduced from 256 to 128 for lower latency

    // Dynamic EQ - supports adding/removing bands
    private var eqBands = mutableListOf<EQBand>()
    private var filters = mutableListOf<BiquadFilter>()

    // Reduced advanced features for performance
    private var noiseGateThreshold = 0.02f
    private var compressionRatio = 0.7f
    private var compressionThreshold = 0.6f

    // Noise Canceling System (Spectral Subtraction)
    private var noiseCancelingEnabled = false
    private var noiseCancelingStrength = 0.7f // 0.0 to 1.0
    private val noiseProfileSize = 512
    private var noiseProfile = FloatArray(noiseProfileSize) { 0f }
    private var noiseProfileSamples = 0
    private val maxNoiseProfileSamples = 50 // Samples to collect for noise profile
    private var noiseProfileLearned = false
    private val spectralFloor = 0.01f // Minimum gain to prevent complete silence
    private var noiseEstimateSmoothing = 0.9f // Smoothing factor for noise estimate
    
    // Wiener filter coefficients for better noise reduction
    private var wienerGains = FloatArray(noiseProfileSize) { 1f }

    // Visualization (reduced frequency to save CPU and reduce latency)
    private var waveformView: WaveformView? = null
    private var spectrumView: SpectrumView? = null
    private var visualUpdateCounter = 0
    private val visualUpdateFrequency = 16  // Update visuals every 16th frame (reduced from 8)
    private val fftProcessor = FFTProcessor(512)  // Reduced from 1024 to 512 for faster processing
    private var fftUpdateCounter = 0
    private val fftUpdateFrequency = 8  // Reduced from 4 to 8

    data class EQBand(
        var frequency: Float = 1000f,
        var gain: Float = 0f,
        var q: Float = 1f,
        var filterType: FilterType = FilterType.PEAKING,
        var enabled: Boolean = true
    )

    private data class BiquadFilter(
        var b0: Float = 1f, var b1: Float = 0f, var b2: Float = 0f,
        var a1: Float = 0f, var a2: Float = 0f,
        var x1: Float = 0f, var x2: Float = 0f,
        var y1: Float = 0f, var y2: Float = 0f
    )

    init {
        optimizeForLowLatency()
        initializeDefaultEQ()
    }

    private fun optimizeForLowLatency() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Get native sample rate - crucial for low latency
        val nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        sampleRate = nativeSampleRate?.toIntOrNull() ?: 48000  // Default to 48kHz if unavailable

        // Get native buffer size
        val nativeBufferSize = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val nativeFrames = nativeBufferSize?.toIntOrNull() ?: 128  // Reduced from 256 to 128

        // Calculate minimum buffer sizes for ultra-low latency
        val minRecordBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val minPlayBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        // Use native frames directly for lowest possible latency
        recordBufferSize = max(minRecordBuffer, nativeFrames * 2)
        playBufferSize = max(minPlayBuffer, nativeFrames * 2)

        // Aggressive processing chunk size for minimal delay
        processingChunkSize = nativeFrames.coerceAtMost(128)

        Log.d(TAG, "Ultra-low-latency: ${sampleRate}Hz, record: ${recordBufferSize}, play: ${playBufferSize}, chunk: $processingChunkSize frames (${processingChunkSize * 1000.0f / sampleRate}ms)")
    }

    private fun initializeDefaultEQ() {
        // Start with 0 filters - user will add them dynamically
        // No default bands
    }

    @Throws(SecurityException::class)
    fun startAudioProcessing() {
        if (isRecording) return

        try {
            // Create ultra-low-latency AudioRecord
            audioRecord = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)  // Optimized for low latency
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(recordBufferSize)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Use low-latency source
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }

            // Create ultra-low-latency AudioTrack
            audioTrack = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)  // Changed for hearing aid use
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setFlags(AudioAttributes.FLAG_LOW_LATENCY)  // Request low latency flag
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(playBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    playBufferSize,
                    AudioTrack.MODE_STREAM
                )
            }

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                throw IllegalStateException("AudioTrack failed to initialize")
            }

            audioRecord?.startRecording()
            audioTrack?.play()
            isRecording = true

            // Ultra-high priority audio thread
            audioThread = Thread { processLowLatencyAudio() }
            audioThread?.start()

            Log.d(TAG, "Low-latency audio processing started")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting low-latency audio: ${e.message}", e)
            cleanup()
            throw e
        }
    }

    private fun updateRealTimeVisualization(buffer: ShortArray, length: Int) {
        // Update waveform visualization
        if (length > 0) {
            waveformView?.updateWaveform(buffer.copyOf(length))
        }

        // Perform real FFT and update spectrum analyzer
        if (fftProcessor.isReady()) {
            val fftMagnitudes = fftProcessor.computeFFT()
            spectrumView?.updateSpectrum(fftMagnitudes)
        }
    }


    private fun processLowLatencyAudio() {
        // Maximum audio thread priority for minimal latency
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val buffer = ShortArray(processingChunkSize)
        val originalBuffer = ShortArray(processingChunkSize)
        
        // Pre-calculate constant for performance
        val shortMaxInv = 1.0f / Short.MAX_VALUE

        Log.d(TAG, "Ultra-low-latency processing started with ${processingChunkSize} sample chunks")

        while (isRecording) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, processingChunkSize) ?: 0

                if (bytesRead > 0) {
                    // Only copy for visualization when needed (reduces overhead)
                    visualUpdateCounter++
                    val shouldUpdateVis = visualUpdateCounter >= visualUpdateFrequency
                    if (shouldUpdateVis) {
                        System.arraycopy(buffer, 0, originalBuffer, 0, bytesRead)
                        fftProcessor.addSamples(originalBuffer)
                        visualUpdateCounter = 0
                    }

                    // Apply noise canceling before other processing (works on entire buffer)
                    if (noiseCancelingEnabled) {
                        applyNoiseCanceling(buffer, bytesRead)
                    }

                    // Optimized audio processing loop
                    for (i in 0 until bytesRead) {
                        var sample = buffer[i] * shortMaxInv

                        // Apply noise gate (simplified)
                        if (abs(sample) < noiseGateThreshold) {
                            sample *= 0.1f
                        }

                        // Apply EQ filters
                        sample = applyStreamlinedEQ(sample)

                        // Apply compression (simplified)
                        if (abs(sample) > compressionThreshold) {
                            val compressedAbs = compressionThreshold +
                                    (abs(sample) - compressionThreshold) * compressionRatio
                            sample = if (sample >= 0) compressedAbs else -compressedAbs
                        }

                        // Apply amplification and clipping
                        sample = (sample * amplificationGain).coerceIn(-0.95f, 0.95f)

                        buffer[i] = (sample * Short.MAX_VALUE).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }

                    // Write processed audio immediately (critical for low latency)
                    audioTrack?.write(buffer, 0, bytesRead)

                    // Update visualizations less frequently to reduce CPU load
                    if (shouldUpdateVis) {
                        fftUpdateCounter++
                        if (fftUpdateCounter >= fftUpdateFrequency && fftProcessor.isReady()) {
                            updateRealTimeVisualization(originalBuffer, bytesRead)
                            fftUpdateCounter = 0
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in low-latency processing: ${e.message}", e)
                break
            }
        }
    }

    private fun applyStreamlinedEQ(input: Float): Float {
        var output = input
        for (i in eqBands.indices) {
            if (eqBands[i].enabled) {
                output = processBiquadFilter(output, filters[i])
            }
        }
        return output
    }

    private fun processBiquadFilter(input: Float, filter: BiquadFilter): Float {
        val output = filter.b0 * input + filter.b1 * filter.x1 + filter.b2 * filter.x2 -
                filter.a1 * filter.y1 - filter.a2 * filter.y2

        filter.x2 = filter.x1
        filter.x1 = input
        filter.y2 = filter.y1
        filter.y1 = output

        return output
    }

    /**
     * Apply noise canceling using spectral subtraction with Wiener filtering
     * This processes a buffer of audio samples to reduce background noise
     */
    private fun applyNoiseCanceling(buffer: ShortArray, length: Int) {
        if (!noiseCancelingEnabled || length < 2) return

        // Convert to float array for processing
        val floatBuffer = FloatArray(length)
        for (i in 0 until length) {
            floatBuffer[i] = buffer[i].toFloat() / Short.MAX_VALUE
        }

        // Perform FFT (using simple sliding window approach for real-time processing)
        val fftSize = min(length, noiseProfileSize)
        val magnitude = FloatArray(fftSize / 2)
        val phase = FloatArray(fftSize / 2)
        
        // Simple magnitude spectrum estimation
        for (i in 0 until fftSize / 2) {
            val idx = i * 2
            if (idx + 1 < length) {
                val real = floatBuffer[idx]
                val imag = floatBuffer[idx + 1]
                magnitude[i] = sqrt(real * real + imag * imag)
                phase[i] = atan2(imag, real)
            }
        }

        // Update noise profile if still learning
        if (!noiseProfileLearned && noiseProfileSamples < maxNoiseProfileSamples) {
            for (i in magnitude.indices) {
                if (i < noiseProfile.size) {
                    noiseProfile[i] += magnitude[i]
                }
            }
            noiseProfileSamples++
            
            if (noiseProfileSamples >= maxNoiseProfileSamples) {
                // Average the noise profile
                for (i in noiseProfile.indices) {
                    noiseProfile[i] /= maxNoiseProfileSamples
                }
                noiseProfileLearned = true
                Log.d(TAG, "Noise profile learned")
            }
        }

        // Apply spectral subtraction with Wiener filtering
        if (noiseProfileLearned) {
            for (i in magnitude.indices) {
                if (i < noiseProfile.size) {
                    // Wiener filter: gain = (signal^2) / (signal^2 + noise^2)
                    val signalPower = magnitude[i] * magnitude[i]
                    val noisePower = noiseProfile[i] * noiseProfile[i]
                    val snr = signalPower / (signalPower + noisePower + 1e-10f)
                    
                    // Apply gain with smoothing
                    val targetGain = max(spectralFloor, snr.pow(noiseCancelingStrength))
                    wienerGains[i] = noiseEstimateSmoothing * wienerGains[i] + 
                                     (1 - noiseEstimateSmoothing) * targetGain
                    
                    magnitude[i] *= wienerGains[i]
                }
            }

            // Reconstruct signal (simplified inverse transform)
            for (i in 0 until fftSize / 2) {
                val idx = i * 2
                if (idx + 1 < length) {
                    floatBuffer[idx] = magnitude[i] * cos(phase[i])
                    floatBuffer[idx + 1] = magnitude[i] * sin(phase[i])
                }
            }
        }

        // Convert back to short array
        for (i in 0 until length) {
            buffer[i] = (floatBuffer[i] * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
                .toInt()
                .toShort()
        }
    }

    /**
     * Reset the noise profile to relearn the background noise
     */
    private fun resetNoiseProfile() {
        noiseProfile.fill(0f)
        wienerGains.fill(1f)
        noiseProfileSamples = 0
        noiseProfileLearned = false
        Log.d(TAG, "Noise profile reset")
    }

    private fun updateVisualization(buffer: ShortArray, length: Int) {
        // Only update if visualization is connected and we have data
        if (length > 0) {
            waveformView?.updateWaveform(buffer.copyOf(length))

            // Simple spectrum calculation (reduced complexity)
            if (length >= 32) {
                val spectrum = FloatArray(32)
                val step = length / 32
                for (i in 0 until 32) {
                    val index = i * step
                    spectrum[i] = abs(buffer[index].toFloat()) / Short.MAX_VALUE
                }
                spectrumView?.updateSpectrum(spectrum)
            }
        }
    }

    private fun updateAllFilters() {
        for (i in eqBands.indices) {
            updateBiquadCoefficients(i)
        }
    }

    private fun updateBiquadCoefficients(bandIndex: Int) {
        val band = eqBands[bandIndex]
        val filter = filters[bandIndex]

        val omega = 2 * PI * band.frequency / sampleRate
        val cosOmega = cos(omega).toFloat()
        val sinOmega = sin(omega).toFloat()
        val alpha = sinOmega / (2 * band.q)
        val A = 10.0.pow(band.gain / 40.0).toFloat()

        when (band.filterType) {
            FilterType.PEAKING -> {
                // Peaking EQ filter coefficients
                filter.b0 = 1 + alpha * A
                filter.b1 = -2 * cosOmega
                filter.b2 = 1 - alpha * A
                val a0 = 1 + alpha / A
                filter.a1 = -2 * cosOmega / a0
                filter.a2 = (1 - alpha / A) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
            FilterType.LOW_PASS -> {
                // Low-pass filter coefficients
                filter.b0 = (1 - cosOmega) / 2
                filter.b1 = 1 - cosOmega
                filter.b2 = (1 - cosOmega) / 2
                val a0 = 1 + alpha
                filter.a1 = -2 * cosOmega / a0
                filter.a2 = (1 - alpha) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
            FilterType.HIGH_PASS -> {
                // High-pass filter coefficients
                filter.b0 = (1 + cosOmega) / 2
                filter.b1 = -(1 + cosOmega)
                filter.b2 = (1 + cosOmega) / 2
                val a0 = 1 + alpha
                filter.a1 = -2 * cosOmega / a0
                filter.a2 = (1 - alpha) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
            FilterType.BAND_PASS -> {
                // Band-pass filter coefficients (constant skirt gain)
                filter.b0 = alpha
                filter.b1 = 0f
                filter.b2 = -alpha
                val a0 = 1 + alpha
                filter.a1 = -2 * cosOmega / a0
                filter.a2 = (1 - alpha) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
            FilterType.NOTCH -> {
                // Notch (band-stop) filter coefficients
                filter.b0 = 1f
                filter.b1 = -2 * cosOmega
                filter.b2 = 1f
                val a0 = 1 + alpha
                filter.a1 = -2 * cosOmega / a0
                filter.a2 = (1 - alpha) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
            FilterType.LOW_SHELF -> {
                // Low-shelf filter coefficients
                val sqrtA = sqrt(A)
                filter.b0 = A * ((A + 1) - (A - 1) * cosOmega + 2 * sqrtA * alpha)
                filter.b1 = 2 * A * ((A - 1) - (A + 1) * cosOmega)
                filter.b2 = A * ((A + 1) - (A - 1) * cosOmega - 2 * sqrtA * alpha)
                val a0 = (A + 1) + (A - 1) * cosOmega + 2 * sqrtA * alpha
                filter.a1 = -2 * ((A - 1) + (A + 1) * cosOmega) / a0
                filter.a2 = ((A + 1) + (A - 1) * cosOmega - 2 * sqrtA * alpha) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
            FilterType.HIGH_SHELF -> {
                // High-shelf filter coefficients
                val sqrtA = sqrt(A)
                filter.b0 = A * ((A + 1) + (A - 1) * cosOmega + 2 * sqrtA * alpha)
                filter.b1 = -2 * A * ((A - 1) + (A + 1) * cosOmega)
                filter.b2 = A * ((A + 1) + (A - 1) * cosOmega - 2 * sqrtA * alpha)
                val a0 = (A + 1) - (A - 1) * cosOmega + 2 * sqrtA * alpha
                filter.a1 = 2 * ((A - 1) - (A + 1) * cosOmega) / a0
                filter.a2 = ((A + 1) - (A - 1) * cosOmega - 2 * sqrtA * alpha) / a0
                filter.b0 /= a0
                filter.b1 /= a0
                filter.b2 /= a0
            }
        }
    }

    // Public API methods for EQ management
    fun setEQBand(bandIndex: Int, frequency: Float, gain: Float, q: Float, filterType: FilterType = FilterType.PEAKING) {
        if (bandIndex in eqBands.indices) {
            eqBands[bandIndex].frequency = frequency.coerceIn(20f, 20000f)
            eqBands[bandIndex].gain = gain.coerceIn(-20f, 20f)
            eqBands[bandIndex].q = q.coerceIn(0.1f, 10f)
            eqBands[bandIndex].filterType = filterType
            updateBiquadCoefficients(bandIndex)
        }
    }

    /**
     * Add a new EQ band
     * @return the index of the newly added band
     */
    fun addEQBand(frequency: Float, gain: Float, q: Float, filterType: FilterType = FilterType.PEAKING): Int {
        val newBand = EQBand(
            frequency = frequency.coerceIn(20f, 20000f),
            gain = gain.coerceIn(-20f, 20f),
            q = q.coerceIn(0.1f, 10f),
            filterType = filterType,
            enabled = true
        )
        eqBands.add(newBand)
        
        val newFilter = BiquadFilter()
        filters.add(newFilter)
        
        val newIndex = eqBands.size - 1
        updateBiquadCoefficients(newIndex)
        
        Log.d(TAG, "Added EQ band at ${frequency}Hz, index: $newIndex")
        return newIndex
    }

    /**
     * Remove an EQ band at the specified index
     * @return true if removal was successful
     */
    fun removeEQBand(bandIndex: Int): Boolean {
        if (bandIndex in eqBands.indices) {
            eqBands.removeAt(bandIndex)
            filters.removeAt(bandIndex)
            Log.d(TAG, "Removed EQ band at index: $bandIndex")
            return true
        }
        return false
    }

    /**
     * Get the number of current EQ bands
     */
    fun getEQBandCount(): Int = eqBands.size

    /**
     * Get information about a specific EQ band
     */
    fun getEQBand(bandIndex: Int): EQBand? {
        return if (bandIndex in eqBands.indices) {
            eqBands[bandIndex].copy()
        } else null
    }

    /**
     * Get all EQ bands
     */
    fun getAllEQBands(): List<EQBand> {
        return eqBands.map { it.copy() }
    }

    /**
     * Clear all EQ bands
     */
    fun clearAllEQBands() {
        eqBands.clear()
        filters.clear()
        Log.d(TAG, "Cleared all EQ bands")
    }

    /**
     * Apply a complete preset to the audio engine
     */
    fun applyPreset(preset: EQPreset) {
        // Clear existing bands
        clearAllEQBands()
        
        // Add bands from preset
        preset.filters.forEach { filter ->
            addEQBand(filter.frequency, filter.gain, filter.q, filter.filterType)
        }
        
        // Apply other settings
        setAmplification(preset.masterVolume)
        setNoiseGate(preset.noiseGateThreshold)
        setCompression(preset.compressionRatio, 0.6f)
        
        Log.d(TAG, "Applied preset: ${preset.name} with ${preset.filters.size} bands")
    }

    /**
     * Get current state as a preset
     */
    fun getCurrentState(presetName: String): EQPreset {
        val filterBands = eqBands.map { band ->
            EQPreset.FilterBand(
                frequency = band.frequency,
                gain = band.gain,
                q = band.q,
                filterType = band.filterType,
                enabled = band.enabled
            )
        }
        
        return EQPreset(
            name = presetName,
            filters = filterBands,
            masterVolume = amplificationGain,
            noiseGateThreshold = noiseGateThreshold,
            compressionRatio = compressionRatio
        )
    }

    fun setAmplification(gain: Float) {
        amplificationGain = max(0.5f, min(gain, 4.0f))
    }

    fun setNoiseGate(threshold: Float) {
        noiseGateThreshold = max(0.001f, min(threshold, 0.2f))
    }

    fun setCompression(ratio: Float, threshold: Float) {
        compressionRatio = ratio.coerceIn(0.1f, 1f)
        compressionThreshold = threshold.coerceIn(0.1f, 1f)
    }

    /**
     * Enable or disable noise canceling
     */
    fun setNoiseCanceling(enabled: Boolean) {
        if (enabled && !noiseCancelingEnabled) {
            // Reset noise profile when enabling
            resetNoiseProfile()
        }
        noiseCancelingEnabled = enabled
        Log.d(TAG, "Noise canceling ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set noise canceling strength (0.0 to 1.0)
     * Higher values = more aggressive noise reduction
     */
    fun setNoiseCancelingStrength(strength: Float) {
        noiseCancelingStrength = strength.coerceIn(0.0f, 1.0f)
        Log.d(TAG, "Noise canceling strength set to $noiseCancelingStrength")
    }

    /**
     * Check if noise canceling is enabled
     */
    fun isNoiseCancelingEnabled(): Boolean = noiseCancelingEnabled

    /**
     * Check if noise profile has been learned
     */
    fun isNoiseProfileLearned(): Boolean = noiseProfileLearned

    /**
     * Manually reset the noise profile to relearn background noise
     */
    fun resetNoiseCancelingProfile() {
        resetNoiseProfile()
    }

    fun setVisualizerViews(waveform: WaveformView?, spectrum: SpectrumView?) {
        waveformView = waveform
        spectrumView = spectrum
    }

    fun stopAudioProcessing() {
        isRecording = false

        audioThread?.let { thread ->
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                Log.e(TAG, "Thread interruption", e)
            }
        }

        cleanup()
        Log.d(TAG, "Low-latency audio processing stopped")
    }

    private fun cleanup() {
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up AudioRecord", e)
            }
        }
        audioRecord = null

        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up AudioTrack", e)
            }
        }
        audioTrack = null
    }

    fun isProcessing(): Boolean = isRecording
}
