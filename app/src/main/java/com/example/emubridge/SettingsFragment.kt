package com.example.emubridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.launch
import androidx.compose.ui.graphics.vector.path
import androidx.documentfile.provider.DocumentFile
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.example.emubridge.R
import kotlin.io.path.name

class SettingsFragment : PreferenceFragmentCompat() {
    private var settingsActivity: SettingsActivity? = null

    // To update summary after selection
    private var romDirectoryPreference: Preference? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is SettingsActivity) {
            settingsActivity = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        settingsActivity = null
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        romDirectoryPreference = findPreference("rom_directory_picker_preference_key") // Use the new key

        romDirectoryPreference?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            settingsActivity?.let { activity ->
                // Directly launch the SAF directory picker
                // No text input first, so no 'pendingDirectoryPathValidation' is needed here.
                activity.openDirectoryLauncherSettings.launch(null) // Pass null to let user browse
            }
            true // Indicate the click was handled
        }

        // Update summary with currently selected path (if any)
        updateRomDirectoryPreferenceSummary()
    }

    // Call this from SettingsActivity after a directory is successfully chosen and saved
    fun updateRomDirectoryPreferenceSummary() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val uriString = sharedPreferences.getString("user_rom_directory_uri", null)
        if (uriString != null) {
            // Attempt to get a more user-friendly path if possible
            val displayName = getPathDisplayName(requireContext(), Uri.parse(uriString))
            romDirectoryPreference?.summary = "Selected: $displayName"
        } else {
            romDirectoryPreference?.summary = "Tap to choose your ROMs directory"
        }
    }

    // Helper to try and get a more friendly display name for the URI
    private fun getPathDisplayName(context: Context, uri: Uri): String {
        if (DocumentsContract.isTreeUri(uri)) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // For some URIs, the last segment might be useful, but SAF URIs can be opaque.
            // Example: "primary:MyFolder/Games" -> "Games"
            // For a simpler approach, just use the URI string for now, or investigate
            // how to get a reliable display name if DocumentsContract doesn't provide it easily.
            try {
                val documentFile = DocumentFile.fromTreeUri(context, uri)
                return documentFile?.name ?: uri.path ?: uri.toString()
            } catch (e: Exception) {
                // Fallback
                return uri.path ?: uri.toString()
            }
        }
        return uri.path ?: uri.toString()
    }
}