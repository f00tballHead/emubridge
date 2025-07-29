package com.emutools.emubridge

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
// import androidx.compose.ui.graphics.vector.path // Not used in this file
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
// import java.net.URI // Not used in this file
// import kotlin.io.path.exists // Not used in this file

// Assume EmulatorConfig is defined elsewhere and includes an intentFlags: Int field
// data class EmulatorConfig(
//    ...
//    val intentFlags: Int = 0, // IMPORTANT: Ensure this field exists
//    ...
// )

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

        additionalEmulatorExtras.clear()

        if (currentIntent != null) {
            romUri = currentIntent.data

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

        if (romUri != null) {
            if (userSelectedRomDirectoryUri == null) {
                statusText.text = "ROM destination directory not configured. Please set it in main Settings."
                return
            }

            var emulatorPackageToUse: String? = null
            var emulatorActivityToUse: String? = null
            var customExtrasForLaunch = Bundle()
            var intentFlagsForLaunch = 0 // Default to no specific flags from config

            val romSourceDirectoryString = getSourceDirectoryFromUri(this, romUri)

            statusText.text = romUri.toString() + "\r\n" + romSourceDirectoryString

            if (romSourceDirectoryString != null) {
                val matchingConfig = EmulatorConfigManager.findConfigForRomDirectory(this, romSourceDirectoryString)

                if (matchingConfig != null) {
                    Log.i("MainActivity", "Found matching emulator config: ${matchingConfig.name}")
                    emulatorPackageToUse = matchingConfig.emulatorPackageName
                    emulatorActivityToUse = matchingConfig.emulatorActivityName
                    intentFlagsForLaunch = matchingConfig.intentFlags // Get flags from config
                    customExtrasForLaunch.putAll(matchingConfig.customExtras)
                    additionalEmulatorExtras.keySet().forEach { key ->
                        val value = additionalEmulatorExtras.get(key)
                        if (value is String) {
                            customExtrasForLaunch.putString(key, value)
                            Log.d("MainActivity", "Merging intent extra (overwriting if exists): Key='$key', Value='$value'")
                        }
                    }
                } else {
                    Log.w("MainActivity", "No emulator config found for source directory: $romSourceDirectoryString")
                }
            } else {
                Log.w("MainActivity", "Could not determine source directory for ROM URI: $romUri")
            }

            if (emulatorPackageToUse == null || emulatorActivityToUse == null) {
                if (explicitEmulatorPackage != null && explicitEmulatorActivity != null) {
                    statusText.text = "Launching with explicitly specified emulator..."
                    emulatorPackageToUse = explicitEmulatorPackage
                    emulatorActivityToUse = explicitEmulatorActivity
                    customExtrasForLaunch.putAll(additionalEmulatorExtras)
                    // For explicitly specified emulators, we might not have specific flags,
                    // so intentFlagsForLaunch remains its default (0 or could be set to a sensible default like NEW_TASK).
                    // For now, it will use the default handling in launchEmulatorWithDocumentUri.
                } else {
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    val preferredEmulatorPackage = sharedPreferences.getString("emulator_package", null)
                    val preferredEmulatorActivity = sharedPreferences.getString("emulator_activity", null)
                    // Note: Preferred emulator from old settings won't have intentFlags.
                    // intentFlagsForLaunch will remain default.

                    if (preferredEmulatorPackage != null && preferredEmulatorActivity != null) {
                        statusText.text = "Launching with preferred emulator (no specific config found)..."
                        emulatorPackageToUse = preferredEmulatorPackage
                        emulatorActivityToUse = preferredEmulatorActivity
                        customExtrasForLaunch.putAll(additionalEmulatorExtras)
                    } else {
                        statusText.text = "Emulator not configured. Please set up an emulator configuration or specify via Intent."
                        return
                    }
                }
            }

            if (emulatorPackageToUse != null && emulatorActivityToUse != null) {
                handleRomLaunch(
                    romUri,
                    emulatorPackageToUse!!,
                    emulatorActivityToUse!!,
                    customExtrasForLaunch,
                    intentFlagsForLaunch // Pass the determined flags
                )
            } else {
                statusText.text = "Could not determine emulator to launch. Check configurations."
            }

        } else {
            statusText.text = "No ROM provided"
        }
    }

    fun getSourceDirectoryFromUri(context: Context, uri: Uri): String? {
        val scheme = uri.scheme
        Log.d("getSourceDirectory", "Attempting to get directory for URI: $uri, Scheme: $scheme")

        if (ContentResolver.SCHEME_FILE.equals(scheme, ignoreCase = true)) {
            val filePath = uri.path
            if (filePath.isNullOrBlank()) {
                Log.e("getSourceDirectory", "File path from file URI is null or blank.")
                return null
            }
            try {
                val file = File(filePath)
                return file.parentFile?.absolutePath ?: file.parent
            } catch (e: SecurityException) {
                Log.e("getSourceDirectory", "Security exception for file URI '$filePath': ${e.message}", e)
                return null
            } catch (e: Exception) {
                Log.e("getSourceDirectory", "Error for file URI '$filePath': ${e.message}", e)
                return null
            }
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme, ignoreCase = true)) {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val documentId = DocumentsContract.getDocumentId(uri)
                Log.d("getSourceDirectory", "Document ID: $documentId for content URI: $uri") // e.g., "primary:MyFolder/MySub/MyFile.txt" or "123-ABC:Docs/Report.pdf"

                var transformedPath = documentId.replaceFirst(":", "/") // e.g., "primary/MyFolder/MySub/MyFile.txt"

                // Ensure it starts with a slash if it's meant to look like an absolute path
                if (!transformedPath.startsWith("/")) {
                    transformedPath = "/$transformedPath" // e.g., "/primary/MyFolder/MySub/MyFile.txt"
                }

                // Now, get the parent directory from this transformed path
                val lastSlashIndex = transformedPath.lastIndexOf('/')
                return if (lastSlashIndex > 0) { // lastSlashIndex > 0 ensures we don't return an empty string for "/file.txt"
                    transformedPath.substring(0, lastSlashIndex) // e.g., "/primary/MyFolder/MySub"
                } else if (lastSlashIndex == 0 && transformedPath.length > 1) {
                    // This means it's like "/file.txt", parent is "/" (root of this transformed structure)
                    "/"
                } else {
                    // Might be just "/filename" (no further slashes after the first) or an unusual case.
                    // Or if documentId was just "primary:file.txt" -> "/primary/file.txt", parent is "/primary"
                    // Or if documentId was just "file.txt" (no colon) -> "/file.txt", parent is "/"
                    // This part might need adjustment based on how "root" of a volume is represented.
                    // If transformedPath is like "/primary", lastSlashIndex is 0.
                    // If it's the root of a volume like "/primary", its "parent" in this context is itself for matching.
                    // Or perhaps you expect null if it's already a root-like path.
                    // Let's assume if there's no further slash after the initial one (or volume name), it is the directory itself.
                    if (transformedPath.count { it == '/' } <= 1 && transformedPath.length > 1) { // e.g. "/primary" or "/someFileInRoot"
                        transformedPath // It's likely a directory itself at this level
                    } else {
                        // Fallback or error for more complex cases not fitting the simple parent model
                        Log.w("getSourceDirectory", "Could not determine parent from transformed SAF path: $transformedPath")
                        null // Or return transformedPath if it should represent the directory itself
                    }
                }
            } else {
                Log.w("getSourceDirectory", "Unhandled content URI (not a document URI): $uri. Falling back to uri.path or uri.toString().")
                return uri.path ?: uri.toString() // This part likely won't match your SAF-derived config paths
            }
        } else {
            Log.w("getSourceDirectory", "Unsupported URI scheme: $scheme for URI: $uri")
            return null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadUserChosenDirectorySafUri() {
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
                userSelectedRomDirectoryUri = null // Invalidate if permission lost
                Toast.makeText(this, "Permission for the selected ROM directory was lost. Please re-select it in Settings.", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.d("MainActivity", "No user ROM directory SAF URI found in SharedPreferences.")
        }
    }

    private fun handleRomLaunch(
        romUri: Uri,
        targetEmulatorPackage: String,
        targetEmulatorActivity: String,
        currentCustomExtras: Bundle,
        intentFlags: Int // Added parameter for intent flags
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

                Log.d("MainActivity", "Cleaning up user-selected SAF directory: ${parentDocumentFile.uri}, excluding '$romName'")
                var cleanupErrorOccurred = false
                withContext(Dispatchers.IO) {
                    try {
                        parentDocumentFile.listFiles().forEach { fileInSaf ->
                            if (fileInSaf.isFile == true && fileInSaf.name != null && fileInSaf.name != romName) {
                                if (!fileInSaf.delete()) {
                                    Log.w("MainActivity", "Failed to delete from SAF dir: ${fileInSaf.name}")
                                    cleanupErrorOccurred = true
                                } else {
                                    Log.d("MainActivity", "Deleted from SAF dir: ${fileInSaf.name}")
                                }
                            } else if (fileInSaf.isFile == true && fileInSaf.name == romName) {
                                Log.d("MainActivity", "Keeping existing target ROM in SAF dir: ${fileInSaf.name}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Exception during SAF directory cleanup: ${e.message}", e)
                        cleanupErrorOccurred = true
                    }
                }
                Log.d("MainActivity", "User-selected SAF directory cleanup finished.${if (cleanupErrorOccurred) " (with some errors)" else ""}")

                withContext(Dispatchers.Main) {
                    statusText.text = if (cleanupErrorOccurred) "Processing ROM (cleanup had issues)..." else "Processing ROM for selected directory..."
                }

                var targetRomDocumentFile = parentDocumentFile.findFile(romName)
                if (targetRomDocumentFile != null && targetRomDocumentFile.exists()) {
                    Log.d("MainActivity", "ROM '$romName' already exists in selected SAF directory.")
                    withContext(Dispatchers.Main) { statusText.text = "ROM already in selected directory. Launching..." }
                } else {
                    Log.d("MainActivity", "ROM '$romName' not found. Starting copy to selected SAF directory...")
                    withContext(Dispatchers.Main) { statusText.text = "Copying ROM to selected directory..." }
                    val mimeType = contentResolver.getType(romUri) ?: "application/octet-stream"
                    targetRomDocumentFile = parentDocumentFile.createFile(mimeType, romName)
                    if (targetRomDocumentFile == null) {
                        Log.e("MainActivity", "Failed to create file '$romName' in SAF directory $targetDirectorySafUri.")
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

                if (targetRomDocumentFile != null && targetRomDocumentFile.exists()) {
                    val finalRomPathUri = targetRomDocumentFile.uri
                    Log.d("MainActivity", "Final ROM path for placeholder replacement: $finalRomPathUri")

                    val processedEmulatorExtras = Bundle(currentCustomExtras)
                    val keysToModify = HashSet(processedEmulatorExtras.keySet())
                    for (key in keysToModify) {
                        val originalValue = processedEmulatorExtras.getString(key)
                        if (originalValue != null && originalValue.contains("%ROMCACHE%")) {
                            val newValue = originalValue.replace("%ROMCACHE%", finalRomPathUri.toString())
                            processedEmulatorExtras.putString(key, newValue)
                            Log.i("MainActivity", "Replaced placeholder for key '$key'. Original: '$originalValue', New: '$newValue'")
                        }
                    }

                    withContext(Dispatchers.Main) { statusText.text = "Copy complete. Launching emulator..." }
                    launchEmulatorWithDocumentUri(
                        finalRomPathUri,
                        targetEmulatorPackage,
                        targetEmulatorActivity,
                        processedEmulatorExtras,
                        intentFlags // Pass the intent flags
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
        var totalBytesCopied = 0L // Initialize here to ensure it's in scope for the final log
        try {
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(targetDocumentFile.uri)?.use { output ->
                    val buffer = ByteArray(8192)
                    // totalBytesCopied is initialized above
                    val fileSize = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
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
                            withContext(Dispatchers.Main) { statusText.text = "Copying... ${totalBytesCopied / 1024} KB" }
                        }
                    }
                    output.flush()
                } ?: run {
                    Log.e("MainActivity", "Failed to open output stream for DocumentFile.")
                    return@withContext false
                }
            } ?: run {
                Log.e("MainActivity", "Failed to open input stream for source URI.")
                return@withContext false
            }
            // Corrected Log:
            Log.d("MainActivity", "Copy to DocumentFile successful. Total bytes copied: $totalBytesCopied")
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying to DocumentFile: ${e.message}", e)
            if (targetDocumentFile.exists()) { targetDocumentFile.delete() }
            false
        }
    }


    // copyWithProgress seems unused if copyToDocumentFileWithProgress is the primary method for SAF.
    // Keeping it for now in case it's used elsewhere or for non-SAF targets.
    private suspend fun copyWithProgress(sourceUri: Uri, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    val buffer = ByteArray(8192)
                    val size = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.length ?: -1
                    var totalRead = 0L
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
                }
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in copyWithProgress: ${e.message}", e)
            if (targetFile.exists()) { targetFile.delete() } // Clean up partial copy
            false
        }
    }


    private fun launchEmulatorWithDocumentUri(
        romContentUri: Uri,
        targetEmulatorPackage: String,
        targetEmulatorActivity: String,
        customExtras: Bundle,
        intentFlags: Int // Added intentFlags parameter
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName(targetEmulatorPackage, targetEmulatorActivity)
            setDataAndType(
                romContentUri,
                contentResolver.getType(romContentUri) ?: "application/octet-stream"
            )

            // Set the flags from the configuration
            this.flags = intentFlags

            // Ensure FLAG_GRANT_READ_URI_PERMISSION is always set for content URIs
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            // It's often good practice to ensure emulators run in a new task
            // unless specifically configured otherwise.
            if ((intentFlags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                Log.d("MainActivity", "FLAG_ACTIVITY_NEW_TASK not in config, adding it by default for emulator launch.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // If FLAG_ACTIVITY_CLEAR_TASK is set, NEW_TASK is required.
            // The logic in EmulatorSettingsActivity should ideally enforce this,
            // but we can also double-check here or rely on the system to handle it.

            if (!customExtras.isEmpty) {
                putExtras(customExtras)
                Log.d("MainActivity", "Added custom extras to emulator intent: ${customExtras.keySet().joinToString()}")
            }
        }

        try {
            Log.i("MainActivity", "Attempting to launch ROM: $romContentUri")
            Log.i("MainActivity", "Target: $targetEmulatorPackage / $targetEmulatorActivity")
            Log.i("MainActivity", "With Flags: ${intent.flags} (Binary: ${Integer.toBinaryString(intent.flags)})")

            val extras = intent.extras
            if (extras != null && !extras.isEmpty) {
                val extrasLog = StringBuilder("Final Intent Extras:\n")
                extras.keySet().forEach { key ->
                    extrasLog.append("  Key='${key}', Value='${extras.get(key)}', Type='${extras.get(key)?.javaClass?.name ?: "NULL"}'\n")
                }
                Log.d("MainActivity", extrasLog.toString())
            } else {
                Log.d("MainActivity", "Final Intent has no extras.")
            }

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
        // Using try-with-resources for cursor
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        return cursor.getString(index)
                    } else {
                        Log.w("MainActivity", "OpenableColumns.DISPLAY_NAME not found in cursor for URI: $uri")
                    }
                } else {
                    Log.w("MainActivity", "Cursor is empty for URI: $uri")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error querying filename from URI: $uri", e)
        }
        // Fallback if cursor method fails
        return uri.lastPathSegment
    }
}
