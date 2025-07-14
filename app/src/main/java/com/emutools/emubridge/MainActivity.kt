package com.emutools.emubridge

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
import androidx.documentfile.provider.DocumentFile
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

    private var userSelectedRomDirectoryUri: Uri? = null

    private val additionalEmulatorExtras = Bundle()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadUserChosenDirectorySafUri()

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        val currentIntent = intent

        var romUri: Uri? = null
        var explicitEmulatorPackage: String? = null
        var explicitEmulatorActivity: String? = null

        // Clear any old additional extras from a previous launch of this activity instance
        additionalEmulatorExtras.clear()

        if (currentIntent != null) {
            romUri = currentIntent.data // Get the ROM URI

            // Process all extras
            currentIntent.extras?.let { extrasBundle ->
                Log.d("MainActivity", "--- Processing Intent Extras ---")
                for (key in extrasBundle.keySet()) {
                    when (key) {
                        "TARGET_EMULATOR_PACKAGE" -> {
                            explicitEmulatorPackage = extrasBundle.getString(key)
                            Log.i("MainActivity", "Consumed TARGET_EMULATOR_PACKAGE: $explicitEmulatorPackage")
                        }
                        "TARGET_EMULATOR_ACTIVITY" -> {
                            explicitEmulatorActivity = extrasBundle.getString(key)
                            Log.i("MainActivity", "Consumed TARGET_EMULATOR_ACTIVITY: $explicitEmulatorActivity")
                        }
                        else -> {
                            // If it's any other key, and its value is a String, add it to our additional bundle
                            val value = extrasBundle.get(key)
                            if (value is String) {
                                additionalEmulatorExtras.putString(key, value)
                                Log.d("MainActivity", "Adding to additionalEmulatorExtras: Key='$key', Value='$value'")
                            } else {
                                Log.d("MainActivity", "Ignoring extra (not a String or handled): Key='$key', Type='${value?.javaClass?.name ?: "NULL"}'")
                            }
                        }
                    }
                }
                Log.d("MainActivity", "--- End of Intent Extras Processing ---")
            }
        } else {
            Log.d("MainActivity", "Intent is null.")
        }


        // --- Your existing logic using explicitEmulatorPackage, explicitEmulatorActivity, romUri ---
        if (romUri != null) {
            if (userSelectedRomDirectoryUri == null) {
                statusText.text = "ROM destination directory not configured or access not granted. Please set it in Settings and confirm access."
                return
            }

            if (explicitEmulatorPackage != null && explicitEmulatorActivity != null) {
                statusText.text = "Launching with specified emulator..."
                // Pass additionalEmulatorExtras to handleRomLaunch
                handleRomLaunch(romUri, explicitEmulatorPackage, explicitEmulatorActivity, additionalEmulatorExtras)
            } else {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                val preferredEmulatorPackage = sharedPreferences.getString("emulator_package", null)
                val preferredEmulatorActivity = sharedPreferences.getString("emulator_activity", null)

                if (preferredEmulatorPackage != null && preferredEmulatorActivity != null) {
                    // Pass additionalEmulatorExtras here too, if you want them applied with preferred settings
                    // Or pass an empty Bundle() if these extras are only for explicit launches
                    handleRomLaunch(romUri, preferredEmulatorPackage, preferredEmulatorActivity, additionalEmulatorExtras)
                } else {
                    statusText.text = "Emulator not configured. Please go to settings or specify via Intent."
                }
            }
        } else {
            statusText.text = "No ROM provided"
        }
    }

    private fun loadUserChosenDirectorySafUri() { // Ensure this is called
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val uriString = sharedPreferences.getString("user_rom_directory_uri", null)
        if (uriString != null) {
            userSelectedRomDirectoryUri = Uri.parse(uriString)
            val persistedUriPermissions = contentResolver.persistedUriPermissions
            val hasPermission = persistedUriPermissions.any { it.uri == userSelectedRomDirectoryUri && it.isReadPermission && it.isWritePermission }
            if (hasPermission) {
                Log.d("MainActivity", "Loaded user ROM directory SAF URI: $userSelectedRomDirectoryUri")
            } else {
                Log.w("MainActivity", "Permission for SAF URI $userSelectedRomDirectoryUri no longer valid. Please re-select in Settings.")
                userSelectedRomDirectoryUri = null
                Toast.makeText(this, "Permission for the selected ROM directory was lost. Please re-select it in Settings.", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.d("MainActivity", "No user ROM directory SAF URI found in SharedPreferences.")
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

    // In MainActivity.kt

    private fun handleRomLaunch(
        romUri: Uri,
        targetEmulatorPackage: String,
        targetEmulatorActivity: String,
        currentCustomExtras: Bundle // Ensure this parameter is present
    ) {
        val romName = getFileNameFromUri(romUri) ?: "rom.bin"

        val targetDirectorySafUri = userSelectedRomDirectoryUri
        if (targetDirectorySafUri == null) {
            statusText.text = "Error: ROM destination directory not selected in Settings."
            Toast.makeText(this, "Please select a ROM destination directory in Settings.", Toast.LENGTH_LONG).show()
            progressBar.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    statusText.text = "Preparing ROM..."
                }

                val parentDocumentFile = DocumentFile.fromTreeUri(applicationContext, targetDirectorySafUri)

                if (parentDocumentFile == null || !parentDocumentFile.isDirectory || !parentDocumentFile.canWrite()) {
                    Log.e("MainActivity", "Cannot access or write to selected directory URI: $targetDirectorySafUri")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error: Cannot access/write to selected ROM directory. Check Settings."
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                // --- Step 1: Cleanup User-Selected SAF Directory (as previously defined) ---
                Log.d("MainActivity", "Cleaning up user-selected SAF directory: ${parentDocumentFile.uri}, excluding '$romName'")
                var cleanupErrorOccurred = false
                withContext(Dispatchers.IO) {
                    try {
                        val filesInSafDir = parentDocumentFile.listFiles()
                        for (fileInSaf in filesInSafDir) {
                            if (fileInSaf.isFile == true && fileInSaf.name != null) {
                                if (fileInSaf.name != romName) {
                                    if (fileInSaf.delete()) {
                                        Log.d("MainActivity", "Deleted from SAF dir: ${fileInSaf.name}")
                                    } else {
                                        Log.w("MainActivity", "Failed to delete from SAF dir (delete returned false): ${fileInSaf.name}")
                                        cleanupErrorOccurred = true
                                    }
                                } else {
                                    Log.d("MainActivity", "Keeping existing target ROM in SAF dir: ${fileInSaf.name}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Exception during SAF directory cleanup: ${e.message}", e)
                        cleanupErrorOccurred = true
                    }
                }
                Log.d("MainActivity", "User-selected SAF directory cleanup finished.${if (cleanupErrorOccurred) " (with some errors)" else ""}")
                // --- End of SAF Directory Cleanup ---


                // --- Step 2: Check/Copy ROM to User-Selected SAF Directory ---
                withContext(Dispatchers.Main) {
                    statusText.text = if (cleanupErrorOccurred) "Processing ROM (cleanup had issues)..." else "Processing ROM for selected directory..."
                }

                var targetRomDocumentFile = parentDocumentFile.findFile(romName)

                if (targetRomDocumentFile != null && targetRomDocumentFile.exists()) {
                    Log.d("MainActivity", "ROM '$romName' already exists in selected SAF directory (or was kept during cleanup).")
                    withContext(Dispatchers.Main) {
                        statusText.text = "ROM already in selected directory. Launching..."
                    }
                } else {
                    Log.d("MainActivity", "ROM '$romName' not found (or was cleaned up) in selected SAF directory. Starting copy...")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Copying ROM to selected directory..."
                    }
                    val mimeType = contentResolver.getType(romUri) ?: "application/octet-stream"
                    targetRomDocumentFile = parentDocumentFile.createFile(mimeType, romName)

                    if (targetRomDocumentFile == null) {
                        Log.e("MainActivity", "Failed to create file '$romName' in SAF directory $targetDirectorySafUri after cleanup attempt.")
                        withContext(Dispatchers.Main) {
                            statusText.text = "Error: Could not create ROM file in selected directory."
                            progressBar.visibility = View.GONE
                        }
                        return@launch
                    }

                    val copiedToSaf = copyToDocumentFileWithProgress(romUri, targetRomDocumentFile)

                    if (!copiedToSaf) {
                        Log.e("MainActivity", "Failed to copy ROM '$romName' to selected SAF directory.")
                        withContext(Dispatchers.Main) {
                            statusText.text = "ROM copy to selected directory failed."
                            progressBar.visibility = View.GONE
                        }
                        targetRomDocumentFile.delete()
                        return@launch
                    }
                    Log.d("MainActivity", "ROM '$romName' copied successfully to selected SAF directory.")
                }

                // --- Step 3: Launch Emulator ---
                if (targetRomDocumentFile != null && targetRomDocumentFile.exists()) {
                    val finalRomPathUri = targetRomDocumentFile.uri // This is the path to replace %ROMCACHE% with
                    Log.d("MainActivity", "Final ROM path for placeholder replacement: $finalRomPathUri")

                    // Create a new Bundle for the processed extras to avoid concurrent modification issues
                    // if currentCustomExtras was directly passed from intent.extras() and is immutable
                    val processedEmulatorExtras = Bundle(currentCustomExtras) // Copy original extras

                    // --- Perform placeholder replacement ---
                    val keysToModify = HashSet(processedEmulatorExtras.keySet()) // Iterate over a copy of keys
                    for (key in keysToModify) {
                        val originalValue = processedEmulatorExtras.getString(key) // We only care about String extras

                        if (originalValue != null && originalValue == "%ROMCACHE%") {
                            processedEmulatorExtras.putString(key, finalRomPathUri.toString())
                            Log.i("MainActivity", "Replaced placeholder for key '$key'. New value: '${finalRomPathUri.toString()}'")
                        } else if (originalValue != null && originalValue.contains("%ROMCACHE%")) {
                            val newValue = originalValue.replace("%ROMCACHE%", finalRomPathUri.toString())
                            processedEmulatorExtras.putString(key, newValue)
                            Log.i("MainActivity", "Replaced placeholder substring for key '$key'. Original: '$originalValue', New: '$newValue'")
                        }
                    }
                    // --- End of placeholder replacement ---


                    withContext(Dispatchers.Main) {
                        statusText.text = "Copy complete. Launching emulator..."
                    }

                    launchEmulatorWithDocumentUri(
                        finalRomPathUri, // Use the final ROM URI for the main data part of the intent
                        targetEmulatorPackage,
                        targetEmulatorActivity,
                        processedEmulatorExtras // Pass the extras bundle with placeholders replaced
                    )
                } else {
                    Log.e("MainActivity", "Target ROM DocumentFile is null or does not exist after SAF processing.")
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error: ROM file not found after SAF processing."
                        progressBar.visibility = View.GONE
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Critical error in handleRomLaunch: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Critical Error: ${e.localizedMessage}"
                    progressBar.visibility = View.GONE
                }
            }
        }
    }


    private suspend fun copyToDocumentFileWithProgress(sourceUri: Uri, targetDocumentFile: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        try {
            val inputStream = contentResolver.openInputStream(sourceUri)
            val outputStream = contentResolver.openOutputStream(targetDocumentFile.uri)

            if (inputStream == null || outputStream == null) {
                Log.e("MainActivity", "Failed to open streams for copy. Input: $inputStream, Output: $outputStream")
                return@withContext false
            }

            val buffer = ByteArray(8192) // 8KB buffer
            var totalBytesCopied = 0L
            val fileSize = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L

            inputStream.use { input ->
                outputStream.use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesCopied += bytesRead
                        if (fileSize > 0) {
                            val progress = ((totalBytesCopied * 100) / fileSize).toInt()
                            withContext(Dispatchers.Main) {
                                progressBar.progress = progress
                                statusText.text = "Copying... $progress%"
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                // Show indeterminate progress if size is unknown
                                statusText.text = "Copying... ${totalBytesCopied / 1024} KB"
                            }
                        }
                    }
                    output.flush()
                }
            }
            Log.d("MainActivity", "Copy to DocumentFile successful. Total bytes: $totalBytesCopied")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying to DocumentFile: ${e.message}", e)
            // Attempt to delete target if copy failed mid-way
            if (targetDocumentFile.exists()) {
                targetDocumentFile.delete()
                Log.d("MainActivity", "Deleted partially copied target DocumentFile.")
            }
            false
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

    private fun launchEmulatorWithDocumentUri(
        romContentUri: Uri,
        targetEmulatorPackage: String,
        targetEmulatorActivity: String,
        customExtras: Bundle
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(targetEmulatorPackage, targetEmulatorActivity)
            setDataAndType(
                romContentUri,
                contentResolver.getType(romContentUri) ?: "application/octet-stream"
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

            // Add all custom extras from the passed Bundle
            if (!customExtras.isEmpty) {
                putExtras(customExtras)
                Log.d("MainActivity", "Added custom extras to emulator intent: ${customExtras.keySet().joinToString()}")
            }
        }

        try {
            Log.d("MainActivity", "Attempting to launch ROM $romContentUri with $targetEmulatorPackage / $targetEmulatorActivity")

            // --- Corrected way to log extras ---
            val extras = intent.extras
            if (extras != null && !extras.isEmpty) {
                val extrasLog = StringBuilder("Final Intent Extras:\n")
                for (key in extras.keySet()) {
                    extrasLog.append("  Key='${key}', Value='${extras.get(key)}', Type='${extras.get(key)?.javaClass?.name ?: "NULL"}'\n")
                }
                Log.d("MainActivity", extrasLog.toString())
            } else {
                Log.d("MainActivity", "Final Intent has no extras.")
            }
            // --- End of corrected logging ---

            startActivity(intent)
            Log.d("MainActivity", "startActivity called for emulator. Finishing MainActivity.")
            finish()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch emulator $targetEmulatorPackage: ${e.message}", e)
            runOnUiThread {
                progressBar.visibility = View.GONE
                statusText.text = "Failed to launch emulator. Ensure it's installed and supports this ROM type."
                Toast.makeText(this, "Failed to launch $targetEmulatorPackage: ${e.message}", Toast.LENGTH_LONG).show()
            }
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
