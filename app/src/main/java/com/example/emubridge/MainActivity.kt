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

    private val romCacheDir: File by lazy {
        File(getExternalFilesDir(null), "rom_cache").also { it.mkdirs() }
    }

    private var userSelectedRomDirectoryUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadUserChosenDirectorySafUri()

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)


        val romUri = intent?.data
        // Try to get emulator info from Intent extras first
        val explicitEmulatorPackage = intent?.getStringExtra("TARGET_EMULATOR_PACKAGE")
        val explicitEmulatorActivity = intent?.getStringExtra("TARGET_EMULATOR_ACTIVITY")

        if (romUri != null) {
            if (userSelectedRomDirectoryUri == null) { // Check the SAF URI
                statusText.text = "ROM destination directory not configured or access not granted. Please set it in Settings and confirm access."
                // Optionally, prompt them to go to settings or trigger the SAF picker directly
                // For example, you could have a button here: "Configure Directory"
                // which calls a method to launch `openDirectoryLauncher.launch(null)`
                return
            }

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

    private fun handleRomLaunch(
        romUri: Uri, // This is the source URI of the ROM (e.g., from Downloads)
        targetEmulatorPackage: String,
        targetEmulatorActivity: String
    ) {
        val romName = getFileNameFromUri(romUri) ?: "rom.bin" // The name of the ROM to be processed

        val targetDirectorySafUri = userSelectedRomDirectoryUri
        if (targetDirectorySafUri == null) {
            statusText.text = "Error: ROM destination directory not selected in Settings."
            Toast.makeText(
                this,
                "Please select a ROM destination directory in Settings.",
                Toast.LENGTH_LONG
            ).show()
            progressBar.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.VISIBLE
                    statusText.text = "Preparing ROM..."
                }

                val parentDocumentFile =
                    DocumentFile.fromTreeUri(applicationContext, targetDirectorySafUri)

                if (parentDocumentFile == null || !parentDocumentFile.isDirectory || !parentDocumentFile.canWrite()) {
                    Log.e(
                        "MainActivity",
                        "Cannot access or write to selected directory URI: $targetDirectorySafUri"
                    )
                    withContext(Dispatchers.Main) {
                        statusText.text =
                            "Error: Cannot access/write to selected ROM directory. Check Settings."
                        progressBar.visibility = View.GONE
                    }
                    return@launch
                }

                // --- Step 1: Cleanup User-Selected SAF Directory ---
                Log.d(
                    "MainActivity",
                    "Cleaning up user-selected SAF directory: ${parentDocumentFile.uri}, excluding '$romName'"
                )
                var cleanupErrorOccurred = false
                withContext(Dispatchers.IO) { // Perform DocumentFile operations on IO dispatcher
                    try {
                        val filesInSafDir = parentDocumentFile.listFiles()
                        for (fileInSaf in filesInSafDir) {
                            // Only operate on files, and ensure 'name' is not null
                            if (fileInSaf.isFile == true && fileInSaf.name != null) {
                                if (fileInSaf.name != romName) { // Check if the file name is different from the current ROM
                                    // Attempt to delete and check the return value
                                    if (fileInSaf.delete()) {
                                        Log.d(
                                            "MainActivity",
                                            "Deleted from SAF dir: ${fileInSaf.name}"
                                        )
                                    } else {
                                        // If delete() returns false, it means it couldn't be deleted.
                                        // This could be due to various reasons, including underlying file system issues
                                        // or if the DocumentProvider doesn't fully support it for a specific item.
                                        Log.w(
                                            "MainActivity",
                                            "Failed to delete from SAF dir (delete returned false): ${fileInSaf.name}"
                                        )
                                        cleanupErrorOccurred =
                                            true // Mark that a non-critical error occurred
                                    }
                                } else {
                                    Log.d(
                                        "MainActivity",
                                        "Keeping existing target ROM in SAF dir: ${fileInSaf.name}"
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "MainActivity",
                            "Exception during SAF directory cleanup: ${e.message}",
                            e
                        )
                        cleanupErrorOccurred = true // Mark that an error occurred
                    }
                }
                Log.d(
                    "MainActivity",
                    "User-selected SAF directory cleanup finished.${if (cleanupErrorOccurred) " (with some errors)" else ""}"
                )
                // --- End of SAF Directory Cleanup ---


                // --- Step 2: Check/Copy ROM to User-Selected SAF Directory ---
                withContext(Dispatchers.Main) {
                    statusText.text =
                        if (cleanupErrorOccurred) "Processing ROM (cleanup had issues)..." else "Processing ROM for selected directory..."
                }

                var targetRomDocumentFile = parentDocumentFile.findFile(romName)

                if (targetRomDocumentFile != null && targetRomDocumentFile.exists()) {
                    Log.d(
                        "MainActivity",
                        "ROM '$romName' already exists in selected SAF directory (or was kept during cleanup)."
                    )
                    withContext(Dispatchers.Main) {
                        statusText.text = "ROM already in selected directory. Launching..."
                    }
                } else {
                    Log.d(
                        "MainActivity",
                        "ROM '$romName' not found (or was cleaned up) in selected SAF directory. Starting copy..."
                    )
                    withContext(Dispatchers.Main) {
                        statusText.text = "Copying ROM to selected directory..."
                    }
                    val mimeType = contentResolver.getType(romUri) ?: "application/octet-stream"
                    targetRomDocumentFile = parentDocumentFile.createFile(mimeType, romName)

                    if (targetRomDocumentFile == null) {
                        Log.e(
                            "MainActivity",
                            "Failed to create file '$romName' in SAF directory $targetDirectorySafUri after cleanup attempt."
                        )
                        withContext(Dispatchers.Main) {
                            statusText.text =
                                "Error: Could not create ROM file in selected directory."
                            progressBar.visibility = View.GONE
                        }
                        return@launch
                    }

                    val copiedToSaf = copyToDocumentFileWithProgress(romUri, targetRomDocumentFile)

                    if (!copiedToSaf) {
                        Log.e(
                            "MainActivity",
                            "Failed to copy ROM '$romName' to selected SAF directory."
                        )
                        withContext(Dispatchers.Main) {
                            statusText.text = "ROM copy to selected directory failed."
                            progressBar.visibility = View.GONE
                        }
                        targetRomDocumentFile.delete()
                        return@launch
                    }
                    Log.d(
                        "MainActivity",
                        "ROM '$romName' copied successfully to selected SAF directory."
                    )
                }

                // --- Step 3: Launch Emulator ---
                if (targetRomDocumentFile != null && targetRomDocumentFile.exists()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Copy complete. Launching emulator..."
                    }
                    launchEmulatorWithDocumentUri(
                        targetRomDocumentFile.uri,
                        targetEmulatorPackage,
                        targetEmulatorActivity
                    )
                } else {
                    Log.e(
                        "MainActivity",
                        "Target ROM DocumentFile is null or does not exist after SAF processing."
                    )
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
        romContentUri: Uri, // This will be the URI from the DocumentFile (content://...)
        targetEmulatorPackage: String,
        targetEmulatorActivity: String
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(targetEmulatorPackage, targetEmulatorActivity)
            setDataAndType(
                romContentUri,
                contentResolver.getType(romContentUri) ?: "application/octet-stream"
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra("bootPath", romContentUri.toString())
            // Add any other flags or extras the emulator might need
        }

        try {
            Log.d("MainActivity", "Launching ROM $romContentUri with $targetEmulatorPackage / $targetEmulatorActivity")
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to launch emulator $targetEmulatorPackage: ${e.message}", e)
            Toast.makeText(this, "Failed to launch $targetEmulatorPackage: ${e.message}", Toast.LENGTH_LONG).show()
            statusText.text = "Failed to launch emulator. Ensure it's installed and supports this ROM type."
            progressBar.visibility = View.GONE // Hide progress if launch fails
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
