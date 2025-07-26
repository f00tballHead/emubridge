package com.emutools.emubridge

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object EmulatorConfigManager {

    private const val PREF_KEY_EMULATOR_CONFIGS = "emulator_configurations"
    private val gson = Gson()

    fun saveEmulatorConfigs(context: Context, configs: List<EmulatorConfig>) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val jsonString = gson.toJson(configs)
        sharedPreferences.edit().putString(PREF_KEY_EMULATOR_CONFIGS, jsonString).apply()
    }

    fun loadEmulatorConfigs(context: Context): MutableList<EmulatorConfig> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val jsonString = sharedPreferences.getString(PREF_KEY_EMULATOR_CONFIGS, null)
        return if (jsonString != null) {
            val type = object : TypeToken<MutableList<EmulatorConfig>>() {}.type
            gson.fromJson(jsonString, type) ?: mutableListOf()
        } else {
            mutableListOf()
        }
    }

    fun addEmulatorConfig(context: Context, config: EmulatorConfig) {
        val configs = loadEmulatorConfigs(context)
        configs.add(config)
        saveEmulatorConfigs(context, configs)
    }

    fun updateEmulatorConfig(context: Context, updatedConfig: EmulatorConfig) {
        val configs = loadEmulatorConfigs(context)
        val index = configs.indexOfFirst { it.id == updatedConfig.id }
        if (index != -1) {
            configs[index] = updatedConfig
            saveEmulatorConfigs(context, configs)
        }
    }

    fun deleteEmulatorConfig(context: Context, configId: String) {
        val configs = loadEmulatorConfigs(context)
        configs.removeAll { it.id == configId }
        saveEmulatorConfigs(context, configs)
    }

    fun findConfigForRomDirectory(context: Context, derivedRomDirectoryIdentifier: String?): EmulatorConfig? {
        if (derivedRomDirectoryIdentifier == null || derivedRomDirectoryIdentifier.isBlank()) {
            Log.w("EmulatorConfigManager", "findConfigForRomDirectory: received null or blank directoryIdentifier.")
            return null
        }

        val configs = loadEmulatorConfigs(context) // Load all saved EmulatorConfig objects

        // --- Direct Match Attempt (Primary Strategy) ---
        // This assumes derivedRomDirectoryIdentifier IS the exact identifier we are looking for (e.g. "primary:Games")
        val directMatch = configs.firstOrNull { config -> !config.sourceRomDirectoryUri.isNullOrBlank() &&
            derivedRomDirectoryIdentifier.contains(config.sourceRomDirectoryUri)
        }
        if (directMatch != null) {
            Log.i("EmulatorConfigManager", "Direct match found for '$derivedRomDirectoryIdentifier' -> Config: ${directMatch.name}")
            return directMatch
        }
        Log.i("EmulatorConfigManager", "No direct match for '$derivedRomDirectoryIdentifier'. Stored configs use identifiers like: ${configs.map { it.sourceRomDirectoryUri }}")


        // --- Fallback: Check if derivedRomDirectoryIdentifier is a subpath of a stored identifier ---
        // This is useful if:
        //  - derivedRomDirectoryIdentifier = "primary:Games/NES" (parent of the ROM)
        //  - stored config.sourceRomDirectoryUri = "primary:Games" (the root picked folder)
        // We want "primary:Games/NES" to match a config for "primary:Games".
        // Important: This assumes a hierarchical path structure (common with "type:path/...")
        for (config in configs) {
            val storedIdentifier = config.sourceRomDirectoryUri
            if (storedIdentifier != null && derivedRomDirectoryIdentifier.startsWith(storedIdentifier)) {
                // To avoid "primary:Game" matching "primary:Games", ensure it's a path boundary or exact match
                if (derivedRomDirectoryIdentifier.length == storedIdentifier.length ||
                    derivedRomDirectoryIdentifier.getOrNull(storedIdentifier.length) == '/' ||
                    storedIdentifier.lastOrNull() == ':') { // Handles cases like "primary:" matching "primary:somefile"
                    Log.i("EmulatorConfigManager", "Subpath match found: '$derivedRomDirectoryIdentifier' starts with stored '${storedIdentifier}' -> Config: ${config.name}")
                    return config
                }
            }
        }
        Log.w("EmulatorConfigManager", "No subpath match found for '$derivedRomDirectoryIdentifier'.")

        return null // No match found
    }
}