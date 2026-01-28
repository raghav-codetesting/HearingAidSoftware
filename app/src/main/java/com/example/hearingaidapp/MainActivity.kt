package com.example.hearingaidapp

import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.util.Log
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var audioEngine: AudioEngine
    private lateinit var btnToggle: Button
    private lateinit var presetManager: PresetManager

    // Master Volume
    private lateinit var seekBarVolume: SeekBar
    private lateinit var tvVolumeLevel: TextView

    // Preset Management
    private lateinit var btnLoadPreset: Button
    private lateinit var btnSavePreset: Button
    private lateinit var btnAddFilter: Button
    private lateinit var tvCurrentPresetName: TextView

    // Dynamic Filter Management
    private lateinit var filterContainer: android.widget.LinearLayout
    private lateinit var tvNoFilters: TextView

    // Noise Canceling
    private lateinit var switchNoiseCanceling: android.widget.Switch
    private lateinit var seekBarNoiseStrength: SeekBar
    private lateinit var tvNoiseStrength: TextView
    private lateinit var tvNoiseStatus: TextView
    private lateinit var btnResetNoiseProfile: Button

    // Advanced Features
    private lateinit var seekBarNoiseGate: SeekBar
    private lateinit var seekBarCompression: SeekBar
    private lateinit var tvNoiseGate: TextView
    private lateinit var tvCompression: TextView

    // Visualization views
    private lateinit var waveformView: WaveformView
    private lateinit var spectrumView: SpectrumView

    private var isRunning = false
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            initializeViews()
            setupListeners()

            // Pass context to AudioEngine for low-latency optimization
            audioEngine = AudioEngine(this)

            // Initialize preset manager
            presetManager = PresetManager(this)
            presetManager.initializeDefaultPresets()

            // Set default neutral values AFTER audioEngine is initialized
            initializeDefaultValues()

            // Initialize all slider displays
            updateAllSliderDisplays()
            updateCurrentPresetDisplay()
            updateUIBasedOnPermissions()
        } catch (e: Exception) {
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeDefaultValues() {
        // Master Volume: Set to 100% (1.0x gain, no boost/cut)
        seekBarVolume.progress = 14
        tvVolumeLevel.text = "Volume: 100%"
        if (::audioEngine.isInitialized) {
            audioEngine.setAmplification(1.0f)
        }

        // Start with 0 filters - user will add them
        updateFilterDisplay()

        // Noise Gate: Disabled by default
        seekBarNoiseGate.progress = 0
        tvNoiseGate.text = "Threshold: 0%"
        if (::audioEngine.isInitialized) {
            audioEngine.setNoiseGate(0f)
        }

        // Compression: Disabled by default
        seekBarCompression.progress = 0
        tvCompression.text = "Ratio: 0%"
        if (::audioEngine.isInitialized) {
            audioEngine.setCompression(0f, 0.6f)
        }

        Log.d("MainActivity", "Default neutral values initialized with 0 filters")
    }


    private fun initializeViews() {
        // Main controls
        btnToggle = findViewById(R.id.btnToggle)
        seekBarVolume = findViewById(R.id.seekBarVolume)
        tvVolumeLevel = findViewById(R.id.tvVolumeLevel)

        // Preset management
        btnLoadPreset = findViewById(R.id.btnLoadPreset)
        btnSavePreset = findViewById(R.id.btnSavePreset)
        btnAddFilter = findViewById(R.id.btnAddFilter)
        tvCurrentPresetName = findViewById(R.id.tvCurrentPreset)

        // Dynamic filter container
        filterContainer = findViewById(R.id.filterContainer)
        tvNoFilters = findViewById(R.id.tvNoFilters)

        // Noise Canceling controls
        switchNoiseCanceling = findViewById(R.id.switchNoiseCanceling)
        seekBarNoiseStrength = findViewById(R.id.seekBarNoiseStrength)
        tvNoiseStrength = findViewById(R.id.tvNoiseStrength)
        tvNoiseStatus = findViewById(R.id.tvNoiseStatus)
        btnResetNoiseProfile = findViewById(R.id.btnResetNoiseProfile)

        // Advanced features
        seekBarNoiseGate = findViewById(R.id.seekBarNoiseGate)
        seekBarCompression = findViewById(R.id.seekBarCompression)
        tvNoiseGate = findViewById(R.id.tvNoiseGate)
        tvCompression = findViewById(R.id.tvCompression)

        // Visualization views (if they exist in your layout)
        try {
            waveformView = findViewById(R.id.waveformView)
            spectrumView = findViewById(R.id.spectrumView)
        } catch (e: Exception) {
            // Visualization views not found in layout - that's okay
        }
    }

    private fun setupListeners() {
        // Power button
        btnToggle.setOnClickListener {
            try {
                if (hasPermissions()) {
                    toggleHearingAid()
                } else {
                    requestPermissionsWithRationale()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Preset management buttons
        btnLoadPreset.setOnClickListener {
            showLoadPresetDialog()
        }

        btnSavePreset.setOnClickListener {
            showSavePresetDialog()
        }

        btnAddFilter.setOnClickListener {
            showAddFilterDialog()
        }

        // Master Volume
        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Map 0-100 to 0.5x-4.0x gain
                    val gain = 0.5f + (progress / 100.0f) * 3.5f
                    if (::audioEngine.isInitialized) {
                        audioEngine.setAmplification(gain)
                    }
                    tvVolumeLevel.text = "Volume: ${(gain * 100).toInt()}%"
                    markAsCustomPreset()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Noise Gate
        seekBarNoiseGate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val threshold = progress / 1000.0f  // Map 0-100 to 0.0-0.1
                    if (::audioEngine.isInitialized) {
                        audioEngine.setNoiseGate(threshold)
                    }
                    tvNoiseGate.text = "Threshold: ${progress}%"
                    markAsCustomPreset()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Compression
        seekBarCompression.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val ratio = progress / 100.0f  // Map 0-100 to 0.0-1.0
                    val threshold = 0.6f  // Fixed threshold for now
                    if (::audioEngine.isInitialized) {
                        audioEngine.setCompression(ratio, threshold)
                    }
                    tvCompression.text = "Ratio: ${progress}%"
                    markAsCustomPreset()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Noise Canceling Switch
        switchNoiseCanceling.setOnCheckedChangeListener { _, isChecked ->
            if (::audioEngine.isInitialized) {
                audioEngine.setNoiseCanceling(isChecked)
                btnResetNoiseProfile.isEnabled = isChecked
                if (isChecked) {
                    tvNoiseStatus.visibility = android.view.View.VISIBLE
                    tvNoiseStatus.text = "Learning noise..."
                    // Start monitoring noise profile status
                    checkNoiseProfileStatus()
                } else {
                    tvNoiseStatus.visibility = android.view.View.GONE
                }
                markAsCustomPreset()
            }
        }

        // Noise Canceling Strength
        seekBarNoiseStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val strength = progress / 100.0f
                    if (::audioEngine.isInitialized) {
                        audioEngine.setNoiseCancelingStrength(strength)
                    }
                    tvNoiseStrength.text = "Strength: ${progress}%"
                    markAsCustomPreset()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Reset Noise Profile Button
        btnResetNoiseProfile.setOnClickListener {
            if (::audioEngine.isInitialized) {
                audioEngine.resetNoiseCancelingProfile()
                tvNoiseStatus.text = "Learning noise..."
                tvNoiseStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                Toast.makeText(this, "Noise profile reset. Stay quiet for a moment...", Toast.LENGTH_SHORT).show()
                checkNoiseProfileStatus()
            }
        }
    }

    private fun checkNoiseProfileStatus() {
        // Periodically check if noise profile has been learned
        val handler = android.os.Handler(mainLooper)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (::audioEngine.isInitialized && switchNoiseCanceling.isChecked) {
                    if (audioEngine.isNoiseProfileLearned()) {
                        tvNoiseStatus.text = "Active"
                        tvNoiseStatus.setTextColor(getColor(android.R.color.holo_green_light))
                    } else {
                        handler.postDelayed(this, 500) // Check again in 500ms
                    }
                }
            }
        }, 500)
    }

    private fun updateAllSliderDisplays() {
        // Update master volume display
        val masterGain = 0.5f + (seekBarVolume.progress / 100.0f) * 3.5f
        tvVolumeLevel.text = "Volume: ${(masterGain * 100).toInt()}%"

        // Update noise canceling display
        tvNoiseStrength.text = "Strength: ${seekBarNoiseStrength.progress}%"

        // Update advanced features displays
        tvNoiseGate.text = "Threshold: ${seekBarNoiseGate.progress}%"
        tvCompression.text = "Ratio: ${seekBarCompression.progress}%"
        
        // Update dynamic filter display
        updateFilterDisplay()
    }

    private fun toggleHearingAid() {
        if (!hasPermissions()) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isRunning) {
            startHearingAid()
        } else {
            stopHearingAid()
        }
    }

    private fun startHearingAid() {
        try {
            // Connect visualizers to AudioEngine before starting
            if (::waveformView.isInitialized && ::spectrumView.isInitialized) {
                audioEngine.setVisualizerViews(waveformView, spectrumView)
                Log.d("MainActivity", "Visualizers connected to AudioEngine")
            }

            // Start audio processing (output visualization is enabled automatically)
            audioEngine.startAudioProcessing()
            isRunning = true
            btnToggle.text = "STOP HEARING AID"
            btnToggle.backgroundTintList = getColorStateList(android.R.color.holo_red_dark)
            Toast.makeText(this, "Hearing aid with output visualization started", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission denied. Cannot start hearing aid.", Toast.LENGTH_LONG).show()
            updateUIBasedOnPermissions()
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting hearing aid: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopHearingAid() {
        try {
            audioEngine.stopAudioProcessing()
            isRunning = false
            btnToggle.text = "START HEARING AID"
            btnToggle.backgroundTintList = getColorStateList(android.R.color.holo_green_dark)
            Toast.makeText(this, "Hearing aid stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error stopping hearing aid: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsWithRationale() {
        when {
            hasPermissions() -> {
                toggleHearingAid()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO) -> {
                showPermissionRationaleDialog()
            }

            else -> {
                requestPermissions()
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Microphone Permission Required")
            .setMessage("This hearing aid app needs microphone access to amplify sounds for you. Please grant permission to continue.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Permission required for hearing aid functionality", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestPermissions() {
        pendingStart = true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
                updateUIBasedOnPermissions()

                if (pendingStart) {
                    pendingStart = false
                    toggleHearingAid()
                }
            } else {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_LONG).show()
                updateUIBasedOnPermissions()

                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    showSettingsDialog()
                }
            }
        }
        pendingStart = false
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Microphone permission is required for the hearing aid to work. Please enable it in app settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun updateUIBasedOnPermissions() {
        if (hasPermissions()) {
            btnToggle.isEnabled = true
            btnToggle.text = if (isRunning) "STOP HEARING AID" else "START HEARING AID"
            btnToggle.alpha = 1.0f
        } else {
            btnToggle.isEnabled = true
            btnToggle.text = "GRANT MICROPHONE PERMISSION"
            btnToggle.alpha = 0.7f
            if (isRunning) {
                stopHearingAid()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUIBasedOnPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioEngine.isInitialized && isRunning) {
            audioEngine.stopAudioProcessing()
        }
    }

    // ==================== Preset Management Methods ====================

    private fun markAsCustomPreset() {
        if (::presetManager.isInitialized) {
            presetManager.setCurrentPreset(null)
            updateCurrentPresetDisplay()
        }
    }

    private fun showLoadPresetDialog() {
        val presets = presetManager.getAllPresets()
        
        if (presets.isEmpty()) {
            Toast.makeText(this, "No presets available. Save one first!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_preset_list, null)
        val listView = dialogView.findViewById<ListView>(R.id.lvPresets)
        val tvCurrentPreset = dialogView.findViewById<TextView>(R.id.tvCurrentPreset)
        val tvNoPresets = dialogView.findViewById<TextView>(R.id.tvNoPresets)

        val currentPresetName = presetManager.getCurrentPresetName()
        tvCurrentPreset.text = "Current: ${currentPresetName ?: "None"}"

        val presetNames = presets.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, presetNames)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Load Preset")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ -> /* Handled below */ }
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedPreset = presets[position]
            loadPreset(selectedPreset)
            dialog.dismiss()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val selectedPreset = presets[position]
            showPresetOptionsDialog(selectedPreset)
            dialog.dismiss()
            true
        }

        dialog.show()
        
        // Handle delete button
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (presets.isNotEmpty()) {
                showDeletePresetDialog(presets)
                dialog.dismiss()
            }
        }
    }

    private fun showSavePresetDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_save_preset, null)
        val etPresetName = dialogView.findViewById<TextInputEditText>(R.id.etPresetName)

        // Suggest a name
        val currentName = presetManager.getCurrentPresetName()
        etPresetName.setText(currentName ?: "My Preset")
        etPresetName.selectAll()

        AlertDialog.Builder(this)
            .setTitle("Save Preset")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val presetName = etPresetName.text.toString().trim()
                if (presetName.isNotEmpty()) {
                    saveCurrentStateAsPreset(presetName)
                } else {
                    Toast.makeText(this, "Please enter a preset name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddFilterDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_filter, null)
        val etFrequency = dialogView.findViewById<TextInputEditText>(R.id.etFrequency)
        val seekBarGain = dialogView.findViewById<SeekBar>(R.id.seekBarGain)
        val seekBarQ = dialogView.findViewById<SeekBar>(R.id.seekBarQ)
        val tvGainLabel = dialogView.findViewById<TextView>(R.id.tvGainLabel)
        val tvQLabel = dialogView.findViewById<TextView>(R.id.tvQLabel)
        val spinnerFilterType = dialogView.findViewById<Spinner>(R.id.spinnerFilterType)

        // Setup filter type spinner
        val filterTypes = AudioEngine.FilterType.values().map { it.getDisplayName() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilterType.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Custom Filter")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val frequencyText = etFrequency.text.toString()
                val frequency = frequencyText.toFloatOrNull()
                
                if (frequency == null || frequency < 20f || frequency > 20000f) {
                    Toast.makeText(this, "Invalid frequency (20-20000 Hz)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val gain = (seekBarGain.progress - 20).toFloat() // -20 to +20 dB
                val q = (seekBarQ.progress + 1) / 10.0f // 0.1 to 10.0
                val filterType = AudioEngine.FilterType.values()[spinnerFilterType.selectedItemPosition]

                if (::audioEngine.isInitialized) {
                    audioEngine.addEQBand(frequency, gain, q, filterType)
                    updateFilterDisplay() // Refresh the UI to show the new filter
                    markAsCustomPreset()
                    Toast.makeText(this, "Filter added at ${frequency.toInt()} Hz", Toast.LENGTH_SHORT).show()
                    Log.d("MainActivity", "Filter added: ${frequency}Hz, ${gain}dB, Q=${q}, Type=${filterType.getDisplayName()}")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Update gain label dynamically
        seekBarGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gainDb = progress - 20
                tvGainLabel.text = "Gain (dB): $gainDb"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Update Q label dynamically
        seekBarQ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val q = (progress + 1) / 10.0f
                tvQLabel.text = "Q Factor: %.1f".format(q)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dialog.show()
    }

    private fun showPresetOptionsDialog(preset: EQPreset) {
        AlertDialog.Builder(this)
            .setTitle(preset.name)
            .setItems(arrayOf("Load", "Delete", "Rename")) { _, which ->
                when (which) {
                    0 -> loadPreset(preset)
                    1 -> {
                        presetManager.deletePreset(preset.name)
                        Toast.makeText(this, "Preset deleted", Toast.LENGTH_SHORT).show()
                        if (presetManager.getCurrentPresetName() == preset.name) {
                            presetManager.setCurrentPreset(null)
                            updateCurrentPresetDisplay()
                        }
                    }
                    2 -> showRenamePresetDialog(preset)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenamePresetDialog(preset: EQPreset) {
        val input = EditText(this)
        input.setText(preset.name)
        input.selectAll()

        AlertDialog.Builder(this)
            .setTitle("Rename Preset")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    if (presetManager.renamePreset(preset.name, newName)) {
                        Toast.makeText(this, "Preset renamed", Toast.LENGTH_SHORT).show()
                        updateCurrentPresetDisplay()
                    } else {
                        Toast.makeText(this, "Failed to rename preset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeletePresetDialog(presets: List<EQPreset>) {
        val presetNames = presets.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(presetNames.size) { false }

        AlertDialog.Builder(this)
            .setTitle("Delete Presets")
            .setMultiChoiceItems(presetNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Delete") { _, _ ->
                var deletedCount = 0
                checkedItems.forEachIndexed { index, checked ->
                    if (checked) {
                        presetManager.deletePreset(presets[index].name)
                        deletedCount++
                    }
                }
                if (deletedCount > 0) {
                    Toast.makeText(this, "$deletedCount preset(s) deleted", Toast.LENGTH_SHORT).show()
                    updateCurrentPresetDisplay()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPreset(preset: EQPreset) {
        if (::audioEngine.isInitialized) {
            audioEngine.applyPreset(preset)
            presetManager.setCurrentPreset(preset.name)
            
            // Update UI to reflect the loaded preset
            updateUIFromAudioEngine()
            updateCurrentPresetDisplay()
            
            Toast.makeText(this, "Loaded: ${preset.name}", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Loaded preset: ${preset.name} with ${preset.filters.size} filters")
        }
    }

    private fun saveCurrentStateAsPreset(presetName: String) {
        if (::audioEngine.isInitialized) {
            val preset = audioEngine.getCurrentState(presetName)
            
            if (presetManager.savePreset(preset)) {
                presetManager.setCurrentPreset(presetName)
                updateCurrentPresetDisplay()
                Toast.makeText(this, "Preset saved: $presetName", Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Saved preset: $presetName with ${preset.filters.size} filters")
            } else {
                Toast.makeText(this, "Failed to save preset", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateCurrentPresetDisplay() {
        val currentPreset = presetManager.getCurrentPresetName()
        tvCurrentPresetName.text = "Current: ${currentPreset ?: "Custom"}"
    }

    private fun updateUIFromAudioEngine() {
        if (!::audioEngine.isInitialized) return

        // Update master volume
        val masterVolume = audioEngine.getCurrentState("temp").masterVolume
        val volumeProgress = ((masterVolume - 0.5f) / 3.5f * 100).toInt()
        seekBarVolume.progress = volumeProgress

        // Update noise gate and compression
        val tempState = audioEngine.getCurrentState("temp")
        seekBarNoiseGate.progress = (tempState.noiseGateThreshold * 1000).toInt()
        seekBarCompression.progress = (tempState.compressionRatio * 100).toInt()

        // Update dynamic filter display
        updateFilterDisplay()
        updateAllSliderDisplays()
    }

    /**
     * Update the dynamic filter display to show all current EQ bands
     */
    private fun updateFilterDisplay() {
        if (!::audioEngine.isInitialized) return

        // Clear existing filter views
        filterContainer.removeAllViews()

        val bands = audioEngine.getAllEQBands()
        
        if (bands.isEmpty()) {
            tvNoFilters.visibility = android.view.View.VISIBLE
        } else {
            tvNoFilters.visibility = android.view.View.GONE
            
            bands.forEachIndexed { index, band ->
                addFilterView(index, band)
            }
        }
    }

    /**
     * Add a filter view for a specific EQ band
     */
    private fun addFilterView(bandIndex: Int, band: AudioEngine.EQBand) {
        val filterView = LayoutInflater.from(this).inflate(R.layout.item_filter, filterContainer, false)
        
        val tvFilterTitle = filterView.findViewById<TextView>(R.id.tvFilterTitle)
        val tvFilterType = filterView.findViewById<TextView>(R.id.tvFilterType)
        val seekBarGain = filterView.findViewById<SeekBar>(R.id.seekBarFilterGain)
        val tvFilterGain = filterView.findViewById<TextView>(R.id.tvFilterGain)
        val seekBarQ = filterView.findViewById<SeekBar>(R.id.seekBarFilterQ)
        val tvFilterQ = filterView.findViewById<TextView>(R.id.tvFilterQ)
        val btnEdit = filterView.findViewById<Button>(R.id.btnEditFilter)
        val btnRemove = filterView.findViewById<Button>(R.id.btnRemoveFilter)

        // Set title and type
        tvFilterTitle.text = "Filter ${bandIndex + 1}: ${band.frequency.toInt()} Hz"
        tvFilterType.text = band.filterType.getDisplayName()
        
        // Set initial values
        val gainProgress = (band.gain + 20).toInt().coerceIn(0, 40)
        seekBarGain.progress = gainProgress
        tvFilterGain.text = "${(gainProgress - 20)} dB"
        
        val qProgress = ((band.q - 0.1f) * 10).toInt().coerceIn(0, 99)
        seekBarQ.progress = qProgress
        tvFilterQ.text = "%.1f".format(band.q)

        // Gain slider listener
        seekBarGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val gainDb = progress - 20
                    tvFilterGain.text = "$gainDb dB"
                    audioEngine.setEQBand(bandIndex, band.frequency, gainDb.toFloat(), band.q, band.filterType)
                    markAsCustomPreset()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Q slider listener
        seekBarQ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val q = (progress + 1) / 10.0f
                    tvFilterQ.text = "%.1f".format(q)
                    audioEngine.setEQBand(bandIndex, band.frequency, band.gain, q, band.filterType)
                    markAsCustomPreset()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Edit button - allows changing frequency
        btnEdit.setOnClickListener {
            showEditFilterDialog(bandIndex, band)
        }

        // Remove button
        btnRemove.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Remove Filter")
                .setMessage("Remove filter at ${band.frequency.toInt()} Hz?")
                .setPositiveButton("Remove") { _, _ ->
                    audioEngine.removeEQBand(bandIndex)
                    updateFilterDisplay()
                    markAsCustomPreset()
                    Toast.makeText(this, "Filter removed", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        filterContainer.addView(filterView)
    }

    /**
     * Show dialog to edit filter frequency
     */
    private fun showEditFilterDialog(bandIndex: Int, band: AudioEngine.EQBand) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_filter, null)
        val etFrequency = dialogView.findViewById<TextInputEditText>(R.id.etFrequency)
        val seekBarGain = dialogView.findViewById<SeekBar>(R.id.seekBarGain)
        val seekBarQ = dialogView.findViewById<SeekBar>(R.id.seekBarQ)
        val tvGainLabel = dialogView.findViewById<TextView>(R.id.tvGainLabel)
        val tvQLabel = dialogView.findViewById<TextView>(R.id.tvQLabel)
        val spinnerFilterType = dialogView.findViewById<Spinner>(R.id.spinnerFilterType)

        // Pre-fill current values
        etFrequency.setText(band.frequency.toInt().toString())
        seekBarGain.progress = (band.gain + 20).toInt()
        seekBarQ.progress = (band.q * 10 - 1).toInt()
        tvGainLabel.text = "Gain (dB): ${band.gain.toInt()}"
        tvQLabel.text = "Q Factor: %.1f".format(band.q)

        // Setup filter type spinner
        val filterTypes = AudioEngine.FilterType.values().map { it.getDisplayName() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterTypes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilterType.adapter = adapter
        spinnerFilterType.setSelection(band.filterType.ordinal)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit Filter")
            .setView(dialogView)
            .setPositiveButton("Update") { _, _ ->
                val frequencyText = etFrequency.text.toString()
                val frequency = frequencyText.toFloatOrNull()
                
                if (frequency == null || frequency < 20f || frequency > 20000f) {
                    Toast.makeText(this, "Invalid frequency (20-20000 Hz)", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                val gain = (seekBarGain.progress - 20).toFloat()
                val q = (seekBarQ.progress + 1) / 10.0f
                val filterType = AudioEngine.FilterType.values()[spinnerFilterType.selectedItemPosition]

                audioEngine.setEQBand(bandIndex, frequency, gain, q, filterType)
                updateFilterDisplay()
                markAsCustomPreset()
                Toast.makeText(this, "Filter updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Update gain label dynamically
        seekBarGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gainDb = progress - 20
                tvGainLabel.text = "Gain (dB): $gainDb"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Update Q label dynamically
        seekBarQ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val q = (progress + 1) / 10.0f
                tvQLabel.text = "Q Factor: %.1f".format(q)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dialog.show()
    }
}
