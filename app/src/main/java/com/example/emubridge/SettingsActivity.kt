package com.example.emubridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.result.registerForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.text
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.example.emubridge.R
import kotlin.collections.remove

class SettingsActivity : AppCompatActivity() {

    private lateinit var directoryPreference: EditTextPreference // Assuming you have this
    private var pendingDirectoryPathValidation: String? = null

    val openDirectoryLauncherSettings =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                saveUserChosenDirectoryUri(uri) // Save for MainActivity

                Toast.makeText(this, "Access granted to: ${uri.toString()}", Toast.LENGTH_LONG).show()

                // Update the summary in the fragment
                val fragment = supportFragmentManager.findFragmentById(R.id.settings_container)
                if (fragment is SettingsFragment) {
                    fragment.updateRomDirectoryPreferenceSummary()
                }
                // No EditTextPreference text to set directly anymore.
            } else {
                Toast.makeText(this, "Directory access not granted.", Toast.LENGTH_SHORT).show()
            }
            // pendingDirectoryPathValidation = null // Not used with this direct picker approach
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // Or use PreferenceFragmentCompat
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment()) // Assuming a container
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // If not using PreferenceFragmentCompat, find your preference here
        // directoryPreference = findPreference("your_directory_preference_key")!!

        // If using PreferenceFragmentCompat, this logic would go into the fragment
    }

    // These methods would interact with SharedPreferences to store/clear the SAF URI
    // that MainActivity will use.
    private fun saveUserChosenDirectoryUri(uri: Uri) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit().putString("user_rom_directory_uri", uri.toString()).apply()
        Log.d("SettingsActivity", "Saved user ROM directory URI: $uri")
    }

    fun clearUserChosenDirectoryUri() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        sharedPreferences.edit().remove("user_rom_directory_uri").apply()
        Log.d("SettingsActivity", "Cleared user ROM directory URI")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
