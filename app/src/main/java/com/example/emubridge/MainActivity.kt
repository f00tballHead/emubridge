package com.example.emubridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val romCacheDir: File by lazy {
        File(getExternalFilesDir(null), "rom_cache").also { it.mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

     //   val toolbar: Toolbar = findViewById(R.id.toolbar)
     //   setSupportActionBar(toolbar)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        val romUri = intent?.data
        // Try to get emulator info from Intent extras first
        val explicitEmulatorPackage = intent?.getStringExtra("TARGET_EMULATOR_PACKAGE")
        val explicitEmulatorActivity = intent?.getStringExtra("TARGET_EMULATOR_ACTIVITY")

        if (romUri != null) {
            if (explicitEmulatorPackage != null && explicitEmulatorActivity != null) {
                // Emulator specified directly in the launch Intent
                statusText.text = "Launching with specified emulator..."
                handleRomLaunch(romUri, explicitEmulatorPackage, explicitEmulatorActivity)
            } else {
                // Fallback to SharedPreferences if not specified in Intent
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                val preferredEmulatorPackage = sharedPreferences.getString("emulator_package", null)
                val preferredEmulatorActivity = sharedPreferences.getString("emulator_activity", null)

                if (preferredEmulatorPackage != null && preferredEmulatorActivity != null) {
                    handleRomLaunch(romUri, preferredEmulatorPackage, preferredEmulatorActivity)
                } else {
                    statusText.text = "Emulator not configured. Please go to settings or specify via Intent."
                }
            }
        } else {
            statusText.text = "No ROM provided"
        }
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true // Return true to display the menu
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // Launch SettingsActivity
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true // Indicate that the event was handled
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleRomLaunch(
        romUri: Uri,
        targetEmulatorPackage: String,
        targetEmulatorActivity: String
    ) {
        val romName = getFileNameFromUri(romUri) ?: "rom.bin"
        val currentTargetFile = File(romCacheDir, romName) // File that is being launched/copied

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Step 1: Clean up old ROM files, excluding the current target file if it exists
                Log.d("MainActivity", "Cleaning up old ROMs in cache...")
                romCacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.absolutePath != currentTargetFile.absolutePath) {
                        if (file.delete()) {
                            Log.d("MainActivity", "Deleted old ROM: ${file.name}")
                        } else {
                            Log.w("MainActivity", "Failed to delete old ROM: ${file.name}")
                        }
                    }
                }
                Log.d("MainActivity", "Cache cleanup finished.")

                // Step 2: Check if the current target ROM needs to be copied or already exists
                if (currentTargetFile.exists()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "ROM already cached. Launching emulator..."
                        progressBar.visibility = View.GONE
                        launchEmulator(currentTargetFile, targetEmulatorPackage, targetEmulatorActivity)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.VISIBLE
                        statusText.text = "Copying ROM..."
                    }
                    val copied = copyWithProgress(romUri, currentTargetFile)
                    if (copied) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "Launch emulator..."
                            launchEmulator(currentTargetFile, targetEmulatorPackage, targetEmulatorActivity)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            statusText.text = "Copy failed."
                            progressBar.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in handleRomLaunch: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.localizedMessage}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun copyWithProgress(sourceUri: Uri, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        val inputStream = contentResolver.openInputStream(sourceUri) ?: return@withContext false
        val outputStream = FileOutputStream(targetFile)
        val buffer = ByteArray(8192)

        val size = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.length ?: -1
        var totalRead = 0L

        try {
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                totalRead += read
                if (size > 0) {
                    val progress = (100 * totalRead / size).toInt()
                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusText.text = "Copying... $progress%"
                    }
                }
            }
            outputStream.flush()
            true
        } finally {
            inputStream.close()
            outputStream.close()
        }
    }

    private fun launchEmulator(
        romFile: File,
        targetEmulatorPackage: String,
        targetEmulatorActivity: String
    ) {
        val romContentUri = FileProvider.getUriForFile(
            this@MainActivity,
            "${packageName}.fileprovider",
            romFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(targetEmulatorPackage, targetEmulatorActivity)
            setDataAndType(
                romContentUri,
                "application/octet-stream" // Or determine more specific MIME type if possible
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            // You might need to add specific extras if the target emulator requires them
            // e.g., putExtra("LIBRETRO_PATH", "/path/to/core.so")
            // e.g., putExtra("CONFIG_PATH", "/path/to/config.cfg")
        }

        try {
            Log.d("MainActivity", "Launching ROM $romContentUri with $targetEmulatorPackage")
            startActivity(intent)
            finish() 
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch emulator $targetEmulatorPackage: ${e.message}", e)
            Toast.makeText(this, "Failed to launch $targetEmulatorPackage: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun getFileNameFromUri(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(index)
        }
    }
}
