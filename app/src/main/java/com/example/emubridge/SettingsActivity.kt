package com.example.emubridge // Make sure this is your correct package

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import java.io.File

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // A layout for hosting the fragment
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment()) // Ensure R.id.settings_container exists in settings_activity.xml
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // Optional: for Up navigation
    }

    // Optional: for Up navigation from ActionBar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var sharedPreferences: SharedPreferences
        private var romCacheDirPref: Preference? = null

        companion object {
            const val KEY_ROM_CACHE_DIR = "rom_cache_directory_uri"
            const val DEFAULT_ROM_CACHE_SUBDIR = "rom_cache" // Default sub-directory name
        }

        // ActivityResultLauncher for the directory picker
        private val openDirectoryLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri != null) {
                    // Persist the read/write permissions for the selected directory URI
                    requireActivity().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    // Save the URI string to SharedPreferences
                    sharedPreferences.edit().putString(KEY_ROM_CACHE_DIR, uri.toString()).apply()
                    updateRomCacheDirSummary(uri)
                }
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey) // Ensure you have this XML file

            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
            romCacheDirPref = findPreference(KEY_ROM_CACHE_DIR)

            romCacheDirPref?.setOnPreferenceClickListener {
                // Launch the directory picker
                openDirectoryLauncher.launch(null) // You can optionally pass an initial URI
                true
            }

            // Initialize summary
            val currentUriString = sharedPreferences.getString(KEY_ROM_CACHE_DIR, null)
            if (currentUriString != null) {
                updateRomCacheDirSummary(Uri.parse(currentUriString))
            } else {
                // Set a default summary or indicate that no directory is selected
                romCacheDirPref?.summary = "No directory selected. Click to choose."
                // Optionally, you could set up a default directory here on first launch
                // setupDefaultCacheDirectoryIfNeeded(requireContext())
            }
        }

        private fun updateRomCacheDirSummary(uri: Uri) {
            // Attempt to get a human-readable path (can be tricky with DocumentTree URIs)
            // This often shows the "label" or last segment of the URI path
            var path = getPathFromUri(requireContext(), uri)
            if (path.isNullOrEmpty()) {
                path = uri.toString() // Fallback to the full URI string
            }
            romCacheDirPref?.summary = "Current: $path"
        }

        // Helper function to try and get a displayable path (best effort)
        private fun getPathFromUri(context: Context, uri: Uri): String? {
            if (DocumentsContract.isTreeUri(uri)) {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                // You might need more complex logic here if you want to display a "fuller" path
                // For now, let's try to get the last segment if it's a typical file path structure
                val segments = documentId.split(":")
                return segments.lastOrNull() ?: documentId // Return the last segment or the whole ID
            }
            return null
        }

        // Optional: Call this to setup a default app-specific directory if none is selected
        // Make sure to call this only once or when appropriate
        private fun setupDefaultCacheDirectoryIfNeeded(context: Context) {
            if (sharedPreferences.getString(KEY_ROM_CACHE_DIR, null) == null) {
                val defaultDir = File(context.getExternalFilesDir(null), DEFAULT_ROM_CACHE_SUBDIR)
                if (!defaultDir.exists()) {
                    defaultDir.mkdirs()
                }
                // Note: For app-specific directories, you don't typically use OpenDocumentTree
                // to get a URI for saving. This is more for initial setup.
                // To make it selectable/confirmable via OpenDocumentTree would be more complex.
                // For simplicity, this example focuses on user-selected directories.
                // If you *really* want to convert a File path to a tree URI that OpenDocumentTree understands,
                // it's not straightforward as OpenDocumentTree gives you URIs from DocumentProviders.
                // So, for a default, you might just use the file path directly in MainActivity,
                // and let the user override it with the picker.
                romCacheDirPref?.summary = "Default: App-specific 'rom_cache' folder. Click to change."
            }
        }
    }
}
