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
import androidx.compose.ui.graphics.vector.path
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import kotlin.io.path.exists

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


        if (romUri != null) {
            if (userSelectedRomDirectoryUri == null) { // This is the TARGET directory for the ROM
                statusText.text = "ROM destination directory not configured. Please set it in main Settings."
                return
            }

            var emulatorPackageToUse: String? = null
            var emulatorActivityToUse: String? = null
            var customExtrasForLaunch = Bundle() // Initialize with empty bundle

            val romSourceDirectoryString = getSourceDirectoryFromUri(this, romUri)
        //    statusText.text = romSourceDirectoryString
         //   statusText.text = EmulatorConfigManager.loadEmulatorConfigs(this)?.firstOrNull()?.sourceRomDirectoryUri

            if (romSourceDirectoryString != null) {
                val matchingConfig = EmulatorConfigManager.findConfigForRomDirectory(this, romSourceDirectoryString)

                if (matchingConfig != null) {
                    Log.i("MainActivity", "Found matching emulator config: ${matchingConfig.name}")
                    emulatorPackageToUse = matchingConfig.emulatorPackageName
                    emulatorActivityToUse = matchingConfig.emulatorActivityName
                    customExtrasForLaunch.putAll(matchingConfig.customExtras) // Use extras from config
                    // Now, also merge any *additional* extras from the intent itself,
                    // potentially overwriting config extras if keys are the same.
                    additionalEmulatorExtras.keySet().forEach { key ->
                        val value = additionalEmulatorExtras.get(key)
                        // Be careful about type, here assuming String for simplicity as in original code
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

            // Fallback or use explicit intent extras if no config matched or source dir unknown
            if (emulatorPackageToUse == null || emulatorActivityToUse == null) {
                if (explicitEmulatorPackage != null && explicitEmulatorActivity != null) {
                    statusText.text = "Launching with explicitly specified emulator..."
                    emulatorPackageToUse = explicitEmulatorPackage
                    emulatorActivityToUse = explicitEmulatorActivity
                    customExtrasForLaunch.putAll(additionalEmulatorExtras) // Use only intent extras
                } else {
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    val preferredEmulatorPackage = sharedPreferences.getString("emulator_package", null)
                    val preferredEmulatorActivity = sharedPreferences.getString("emulator_activity", null)

                    if (preferredEmulatorPackage != null && preferredEmulatorActivity != null) {
                        statusText.text = "Launching with preferred emulator (no specific config found)..."
                        emulatorPackageToUse = preferredEmulatorPackage
                        emulatorActivityToUse = preferredEmulatorActivity
                        customExtrasForLaunch.putAll(additionalEmulatorExtras) // Use only intent extras with preferred
                    } else {
                        statusText.text = "Emulator not configured. Please set up an emulator configuration or specify via Intent."
                        return
                    }
                }
            }

            if (emulatorPackageToUse != null && emulatorActivityToUse != null) {
                // Pass the determined customExtrasForLaunch
                handleRomLaunch(romUri, emulatorPackageToUse!!, emulatorActivityToUse!!, customExtrasForLaunch)
            } else {
                statusText.text = "Could not determine emulator to launch. Check configurations."
            }

        } else {
            statusText.text = "No ROM provided"
        }
    }

    /**
     * Attempts to determine a canonical "source directory" string from a given URI.
     * This is crucial for matching against user-configured emulator settings.
     *
     * NOTE: This function makes several assumptions and might need refinement based on
     * the exact nature of URIs your app receives. Test thoroughly!
     *
     * @param context Context for accessing ContentResolver.
     * @param uri The URI of the ROM file.
     * @return A string representing the source directory, or null if it cannot be determined.
     */
    fun getSourceDirectoryFromUri(context: Context, uri: Uri): String? {
        val filePath = uri.path

        if (filePath == null || filePath.isBlank()) {
            println("Error: File path is null or blank.")
            return null
        }

        try {
            val file = File(filePath)

            // Check if the path itself might be a directory
            // If file.parent is null, it means it's likely a root path or just a filename without path separators
            if (file.parent == null) {
                // It could be a root like "/" or just "filename.txt"
                // If it's just "filename.txt", its parent is conceptually the current working directory,
                // but File(filePath).parentFile might be null.
                // If the path is "/", its parent is null.
                // A more robust check might be needed if you expect relative paths.
                // For absolute paths, file.parentFile should give the correct parent.
                val parentFile = file.parentFile
                return parentFile?.absolutePath // or just parentFile?.path
            }

            // Get the parent directory's path as a string
            return file.parent // This returns the parent path string directly
            // or file.parentFile?.absolutePath for the canonical absolute path

        } catch (e: SecurityException) {
            println("Error: Security exception accessing path '$filePath': ${e.message}")
            // This might happen if you don't have permissions for the path,
            // though less common with just path string manipulation than actual file access.
            return null
        } catch (e: Exception) {
            println("Error processing path '$filePath': ${e.message}")
            // Catch other potential issues, though direct string ops are usually safe
            return null
        }


      /*  when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                // This is the most common case for URIs from SAF or ContentProviders
                try {
                    // If it's a document URI, try to get the tree URI and then its document ID,
                    // or try to backtrack from a child URI.
                    if (DocumentsContract.isDocumentUri(context, uri)) {
                        val documentId = DocumentsContract.getDocumentId(uri) // e.g., "tree_id:document_id" or "single_doc_id"

                        // If it's a child document in a tree
                        if (documentId.contains(":")) {
                            val parts = documentId.split(":")
                            if (parts.size > 1) {
                                val treeDocumentId = parts[0] // This might represent the "root" of a selected tree
                                var parentDocumentId = parts[1]

                                // Attempt to get the parent of the document.
                                // The direct "path" of a content URI isn't always like a file system path.
                                // We are trying to find a stable identifier for the *directory* it resides in.

                                // One strategy: If the original URI's path segments can be reliably parsed.
                                // Content URI paths can be like: /tree/TREE_ID/document/DOCUMENT_ID_PATH
                                // or /document/DOCUMENT_ID_PATH
                                val pathSegments = uri.pathSegments
                                if (pathSegments.isNotEmpty()) {
                                    // Example: content://com.android.providers.downloads.documents/document/msf%3A123
                                    // pathSegments = ["document", "msf:123"]
                                    // Example: content://com.android.externalstorage.documents/tree/PRIMARY%3AMyFolder/document/PRIMARY%3AMyFolder%2FMySubfolder%2From.zip
                                    // pathSegments = ["tree", "PRIMARY:MyFolder", "document", "PRIMARY:MyFolder/MySubfolder/rom.zip"]

                                    // Attempt to find the "tree" part or a stable "document" part that represents the directory.
                                    // This logic is highly dependent on the ContentProvider's URI structure.

                                    var treeUriHint: Uri? = null
                                    var directoryPathIdentifier: String? = null

                                    if (pathSegments.size >= 2 && pathSegments[0] == "tree") {
                                        // Likely a SAF tree URI descendant
                                        // The segment after "tree" is often the root of the user-picked directory.
                                        // e.g., "PRIMARY%3ADocuments"
                                        val treeId = pathSegments[1]
                                        treeUriHint = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
                                        Log.d("getSourceDirectory", "Identified potential tree base: $treeUriHint")
                                        // For simplicity, we might use the tree URI string itself as the "source directory"
                                        // if the ROM is directly within this tree root, or a more complex path if nested.

                                        // If there's a "document" part further down, that's the actual item.
                                        // We need its parent.
                                        if (pathSegments.size >= 4 && pathSegments[2] == "document") {
                                            val fullDocumentPath = pathSegments[3] // e.g., "PRIMARY%3ADocuments%2Ffolder%2From.nes"
                                            val lastSlash = fullDocumentPath.lastIndexOf("%2F") // URL encoded slash
                                            if (lastSlash != -1) {
                                                directoryPathIdentifier = treeUriHint.toString() + "/" + fullDocumentPath.substring(0, lastSlash)
                                            } else {
                                                // ROM is directly in the root of the picked tree
                                                directoryPathIdentifier = treeUriHint.toString()
                                            }
                                        } else {
                                            // Could be the tree URI itself if it points to a directory from which ROMs are launched directly
                                            directoryPathIdentifier = treeUriHint.toString()
                                        }
                                        Log.d("getSourceDirectory", "Derived directory path identifier: $directoryPathIdentifier for tree-based URI")
                                        return directoryPathIdentifier
                                    } else if (pathSegments.size >= 2 && pathSegments[0] == "document") {
                                        // A single document URI, not necessarily part of a user-picked tree.
                                        // e.g., content://com.android.providers.downloads.documents/document/123
                                        // Its "parent" is harder to define without knowing the provider's logic.
                                        // One might try to query for MediaStore.MediaColumns.RELATIVE_PATH or BUCKET_DISPLAY_NAME
                                        // but that's provider-specific.
                                        // For now, we might consider the URI up to the last segment as a "directory" proxy.
                                        val docIdPath = pathSegments[1]
                                        val lastSlash = docIdPath.lastIndexOf("%2F") // URL encoded slash
                                        if (lastSlash != -1) {
                                            // A crude way to get a "parent path" from the document ID itself if it's structured like a path
                                            val parentPart = docIdPath.substring(0, lastSlash)
                                            // Reconstruct a "directory URI" - this is speculative
                                            directoryPathIdentifier = Uri.Builder()
                                                .scheme(uri.scheme)
                                                .authority(uri.authority)
                                                .appendPath(pathSegments[0]) // "document"
                                                .appendPath(parentPart) // Assumed parent path part
                                                .build().toString()
                                            Log.d("getSourceDirectory", "Derived directory path (speculative) for document URI: $directoryPathIdentifier")
                                            return directoryPathIdentifier
                                        } else {
                                            // No parent path in document ID, perhaps it's a "flat" provider or root item.
                                            // Consider the authority + "document" as the directory
                                            directoryPathIdentifier = Uri.Builder()
                                                .scheme(uri.scheme)
                                                .authority(uri.authority)
                                                .appendPath(pathSegments[0])
                                                .build().toString()
                                            Log.w("getSourceDirectory", "Could not determine parent path from single document URI segments: $uri. Using base: $directoryPathIdentifier")
                                            return directoryPathIdentifier
                                        }
                                    }
                                }
                            }
                        }
                        // Fallback for general Document URIs:
                        // If the URI itself could represent a directory chosen by the user and ROMs are directly inside.
                        // Or if we can't reliably parse further.
                        // This is less precise. You need to know how the user selects source directories.
                        // If the user *always* picks a directory with SAF for a configuration,
                        // and that configuration's sourceRomDirectoryUri is a tree URI,
                        // then an incoming ROM URI should ideally be a descendant of that tree.
                        // A simple `uri.toString().startsWith(configuredTreeUriString)` might work in specific scenarios.
                        // However, we are trying to derive the directory *from the romUri* itself.

                        Log.w("getSourceDirectory", "Unhandled Document URI structure or could not reliably determine parent. Falling back to URI string: $uri")
                        // As a last resort for content URIs, if you cannot parse a clear "directory" part,
                        // you might need a more sophisticated mapping or even user input if it's ambiguous.
                        // Returning the URI authority or a known prefix if it's always from a specific provider.
                        // For now, this is a placeholder. A more robust solution might involve querying
                        // the ContentProvider for parent information if available, but that's not standard.
                        return uri.authority // Very rough, might group too many things
                    } else {
                        // Non-document content URI. Example: content://media/external/file/123
                        // The path itself might be the identifier.
                        // Or query MediaStore for RELATIVE_PATH or BUCKET_DISPLAY_NAME if it's a MediaStore URI.
                        // For now, let's try to get the path and remove the last segment.
                        val path = uri.path
                        if (path != null) {
                            val lastSlash = path.lastIndexOf('/')
                            if (lastSlash > 0) { // Ensure it's not the root slash
                                val parentPath = path.substring(0, lastSlash)
                                val dirUri = uri.buildUpon().path(parentPath).build()
                                Log.d("getSourceDirectory", "Derived directory for generic content URI: $dirUri")
                                return dirUri.toString()
                            } else if (lastSlash == 0 && path.length > 1) { // e.g. /something
                                val dirUri = uri.buildUpon().path("/").build() // Root of this content URI's path space
                                Log.d("getSourceDirectory", "Derived root directory for generic content URI: $dirUri")
                                return dirUri.toString()
                            } else {
                                Log.w("getSourceDirectory", "Cannot determine parent path for generic content URI: $uri. Using full path or authority.")
                                // If no slashes or only root, the URI itself might represent the "directory" in some contexts
                                return path ?: uri.authority // Fallback to path or authority
                            }
                        }
                        Log.w("getSourceDirectory", "Content URI without standard document format or path: $uri")
                        return uri.authority // Fallback
                    }
                } catch (e: Exception) {
                    Log.e("getSourceDirectory", "Error processing content URI: $uri", e)
                    return null // Or a very generic fallback like uri.getAuthority()
                }
            }
            ContentResolver.SCHEME_FILE -> {
                // File URIs (e.g., file:///sdcard/ROMS/nes/game.nes)
                // This is less common with modern Android practices (SAF, Scoped Storage)
                // but might occur if dealing with apps that don't use SAF or for internal files.
                val filePath = uri.path
                if (filePath != null) {
                    try {
                        val file = File(filePath)
                        if (file.exists()) {
                            val parentDir = file.parentFile
                            if (parentDir != null) {
                                Log.d("getSourceDirectory", "Derived directory for file URI: ${parentDir.absolutePath}")
                                // You might want to return parentDir.toURI().toString() if you want to keep it as a URI string
                                return parentDir.absolutePath // Or parentDir.toURI().toString()
                            } else {
                                Log.w("getSourceDirectory", "File URI has no parent directory (already a root?): $uri")
                                return null // Or handle as a root directory if appropriate
                            }
                        } else {
                            Log.w("getSourceDirectory", "File URI points to a non-existent file: $uri")
                            return null
                        }
                    } catch (e: Exception) {
                        Log.e("getSourceDirectory", "Error processing file URI: $uri", e)
                        return null
                    }
                }
                return null
            }
            else -> {
                Log.w("getSourceDirectory", "Unsupported URI scheme: ${uri.scheme} for $uri")
                return null // Or perhaps uri.toString() if you want to try a direct match for unknown schemes
            }
        }*/
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu) // Your existing menu
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
