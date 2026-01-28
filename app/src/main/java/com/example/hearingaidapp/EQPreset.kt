package com.example.hearingaidapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

/**
 * Represents a complete parametric equalizer preset
 * Includes all filter bands, master volume, and advanced settings
 */
data class EQPreset(
    val name: String,
    val filters: List<FilterBand>,
    val masterVolume: Float = 1.0f,
    val noiseGateThreshold: Float = 0.0f,
    val compressionRatio: Float = 0.0f,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable {

    /**
     * Represents a single filter band in the equalizer
     */
    data class FilterBand(
        val frequency: Float,
        val gain: Float,
        val q: Float,
        val filterType: AudioEngine.FilterType = AudioEngine.FilterType.PEAKING,
        val enabled: Boolean = true
    ) : Serializable {
        fun toJSON(): JSONObject {
            return JSONObject().apply {
                put("frequency", frequency)
                put("gain", gain)
                put("q", q)
                put("filterType", filterType.name)
                put("enabled", enabled)
            }
        }

        companion object {
            fun fromJSON(json: JSONObject): FilterBand {
                return FilterBand(
                    frequency = json.getDouble("frequency").toFloat(),
                    gain = json.getDouble("gain").toFloat(),
                    q = json.getDouble("q").toFloat(),
                    filterType = AudioEngine.FilterType.fromString(json.optString("filterType", "PEAKING")),
                    enabled = json.optBoolean("enabled", true)
                )
            }
        }
    }

    /**
     * Convert preset to JSON string for storage
     */
    fun toJSON(): String {
        val jsonObject = JSONObject()
        jsonObject.put("name", name)
        jsonObject.put("masterVolume", masterVolume)
        jsonObject.put("noiseGateThreshold", noiseGateThreshold)
        jsonObject.put("compressionRatio", compressionRatio)
        jsonObject.put("timestamp", timestamp)

        val filtersArray = JSONArray()
        filters.forEach { filter ->
            filtersArray.put(filter.toJSON())
        }
        jsonObject.put("filters", filtersArray)

        return jsonObject.toString()
    }

    companion object {
        /**
         * Create preset from JSON string
         */
        fun fromJSON(json: String): EQPreset {
            val jsonObject = JSONObject(json)
            val name = jsonObject.getString("name")
            val masterVolume = jsonObject.getDouble("masterVolume").toFloat()
            val noiseGateThreshold = jsonObject.optDouble("noiseGateThreshold", 0.0).toFloat()
            val compressionRatio = jsonObject.optDouble("compressionRatio", 0.0).toFloat()
            val timestamp = jsonObject.optLong("timestamp", System.currentTimeMillis())

            val filtersArray = jsonObject.getJSONArray("filters")
            val filters = mutableListOf<FilterBand>()
            for (i in 0 until filtersArray.length()) {
                filters.add(FilterBand.fromJSON(filtersArray.getJSONObject(i)))
            }

            return EQPreset(
                name = name,
                filters = filters,
                masterVolume = masterVolume,
                noiseGateThreshold = noiseGateThreshold,
                compressionRatio = compressionRatio,
                timestamp = timestamp
            )
        }

        /**
         * Create default presets
         */
        fun getDefaultPresets(): List<EQPreset> {
            return listOf(
                // Flat/Neutral preset
                EQPreset(
                    name = "Flat",
                    filters = listOf(
                        FilterBand(250f, 0f, 1.0f),
                        FilterBand(1000f, 0f, 1.0f),
                        FilterBand(3000f, 0f, 1.0f),
                        FilterBand(8000f, 0f, 1.0f)
                    ),
                    masterVolume = 1.0f,
                    noiseGateThreshold = 0.0f,
                    compressionRatio = 0.0f
                ),
                // Speech clarity preset
                EQPreset(
                    name = "Speech Clarity",
                    filters = listOf(
                        FilterBand(250f, -3f, 1.0f),
                        FilterBand(1000f, 6f, 1.5f),
                        FilterBand(2500f, 8f, 1.5f),
                        FilterBand(4000f, 6f, 1.2f),
                        FilterBand(8000f, -2f, 1.0f)
                    ),
                    masterVolume = 1.2f,
                    noiseGateThreshold = 0.02f,
                    compressionRatio = 0.5f
                ),
                // Bass Boost preset
                EQPreset(
                    name = "Bass Boost",
                    filters = listOf(
                        FilterBand(60f, 8f, 1.0f),
                        FilterBand(250f, 6f, 1.0f),
                        FilterBand(1000f, 0f, 1.0f),
                        FilterBand(4000f, 0f, 1.0f)
                    ),
                    masterVolume = 1.0f,
                    noiseGateThreshold = 0.0f,
                    compressionRatio = 0.0f
                ),
                // High Frequency Loss preset (common in aging)
                EQPreset(
                    name = "High Freq Loss",
                    filters = listOf(
                        FilterBand(250f, 0f, 1.0f),
                        FilterBand(1000f, 3f, 1.0f),
                        FilterBand(2000f, 8f, 1.2f),
                        FilterBand(4000f, 12f, 1.5f),
                        FilterBand(8000f, 15f, 1.5f)
                    ),
                    masterVolume = 1.5f,
                    noiseGateThreshold = 0.03f,
                    compressionRatio = 0.7f
                ),
                // Music preset
                EQPreset(
                    name = "Music",
                    filters = listOf(
                        FilterBand(60f, 5f, 1.0f),
                        FilterBand(250f, 2f, 1.0f),
                        FilterBand(1000f, -1f, 1.0f),
                        FilterBand(4000f, 3f, 1.2f),
                        FilterBand(12000f, 6f, 1.0f)
                    ),
                    masterVolume = 1.1f,
                    noiseGateThreshold = 0.0f,
                    compressionRatio = 0.3f
                )
            )
        }
    }
}
