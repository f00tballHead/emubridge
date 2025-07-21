package com.emutools.emubridge

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.setText
import androidx.core.content.getSystemService
// import androidx.core.app.NavUtils // Uncomment if you use NavUtils for Up navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
// import com.google.android.material.appbar.MaterialToolbar // Uncomment if you use a MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Collections
import java.util.UUID

// Data class for App Picker (can be in its own file or here)
data class AppInfo(
    val appName: CharSequence,
    val packageName: String,
    val activityName: String?, // Main launchable activity
    val icon: Drawable?
)

// Assume EmulatorConfig is defined (e.g., in its own file or EmulatorConfigManager.kt)
// data class EmulatorConfig(
//    val id: String = UUID.randomUUID().toString(),
//    val name: String,
//    val sourceRomDirectoryUri: String,
//    val emulatorPackageName: String,
//    val emulatorActivityName: String,
//    val customExtras: Bundle = Bundle()
// )

class EmulatorSettingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddEmulator: FloatingActionButton
    private lateinit var emulatorConfigsAdapter: EmulatorConfigsAdapter
    private var currentEditingConfig: EmulatorConfig? = null

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let { selectedUri ->
                // Persist permissions for the original content URI - STILL IMPORTANT if you ever need to use SAF
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(selectedUri, takeFlags)
                    Log.d("EmulatorSettings", "Persisted permission for original content URI: $selectedUri")
                } catch (e: SecurityException) {
                    Log.e("EmulatorSettings", "Failed to persist permission for $selectedUri", e)
                }

                // --- NEW: Attempt to convert to File Path ---
                val filePath = convertSafTreeUriToFilePath(this, selectedUri)

                if (filePath != null) {
                    Log.i("EmulatorSettings", "Converted SAF Tree URI '$selectedUri' to File Path: '$filePath'")
                    currentEmulatorConfigBuilder?.sourceRomDirectoryUri = filePath
                    currentDialogView?.findViewById<EditText>(R.id.etSourceRomDirectory)?.setText(filePath)
                } else {
                    Log.w("EmulatorSettings", "Could not convert SAF Tree URI '$selectedUri' to a file path. Storing TreeDocumentId as fallback.")
                    // Fallback to TreeDocumentId if conversion fails (recommended)
                    val treeDocId = DocumentsContract.getTreeDocumentId(selectedUri)
                    if (treeDocId != null) {
                        currentEmulatorConfigBuilder?.sourceRomDirectoryUri = treeDocId
                        currentDialogView?.findViewById<EditText>(R.id.etSourceRomDirectory)
                            ?.setText("ID: $treeDocId (Path conversion failed)")
                        Toast.makeText(this, "Could not get direct file path. Using directory ID.", Toast.LENGTH_LONG).show()
                    } else {
                        currentEmulatorConfigBuilder?.sourceRomDirectoryUri = selectedUri.toString() // Worst case
                        currentDialogView?.findViewById<EditText>(R.id.etSourceRomDirectory)?.setText(selectedUri.toString())
                        Toast.makeText(this, "Could not identify directory.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    /**
     * Attempts to convert a SAF Tree URI (typically from ExternalStorageProvider) to a direct file path.
     * WARNING: This is fragile and may not work for all providers or Android versions.
     * It's highly dependent on the URI's structure.
     */
    private fun convertSafTreeUriToFilePath(context: Context, treeUri: Uri): String? {
        if (!DocumentsContract.isTreeUri(treeUri) ||
            treeUri.authority != "com.android.externalstorage.documents") {
            Log.w("FilePathConversion", "URI is not a tree URI from ExternalStorageProvider: $treeUri")
            return null // Only attempt for known external storage tree URIs
        }

        val docId = DocumentsContract.getTreeDocumentId(treeUri) // e.g., "primary:Download/MyFolder" or "1234-5678:Roms"
        if (docId == null) {
            Log.w("FilePathConversion", "Could not get TreeDocumentId from $treeUri")
            return null
        }

        val split = docId.split(":")
        if (split.size < 2) {
            Log.w("FilePathConversion", "TreeDocumentId format unexpected: $docId")
            return null
        }

        val type = split[0].lowercase() // "primary" or "1234-5678" (sd card id)
        val path = split[1]         // "Download/MyFolder" or "Roms"

        var storageDir: File? = null
        when (type) {
            "primary" -> {
                storageDir = Environment.getExternalStorageDirectory() // Base external storage
            }
            else -> {
                // Attempt to find SD card path (this is also fragile)
                val externalCacheDirs = context.externalCacheDirs
                for (cacheDir in externalCacheDirs) {
                    if (cacheDir != null) {
                        val rootDir = cacheDir.path.split("/Android")[0]
                        // A common heuristic: if docId starts with a part of this rootDir's name (like the UUID)
                        // This is VERY heuristic.
                        if (rootDir.contains(type)) { // This is a weak check
                            storageDir = File(rootDir)
                            break
                        }
                    }
                }


                if (storageDir == null) {
                    Log.w("FilePathConversion", "Could not determine storage directory for type: $type (SD card?). Tried common heuristics.")
                    // Last ditch effort: try to find a volume whose getTreeDocumentId starts with type
                    // This involves querying all storage volumes, which is heavier.
                    // This is complex and often requires querying DocumentsContract.buildRootsUri.
                    // For simplicity, we'll return null here. A robust solution is much harder.
                    return null
                }
            }
        }

        return if (storageDir != null) {
            val finalPath = File(storageDir, path).absolutePath
            Log.i("FilePathConversion", "Constructed path: $finalPath for docId: $docId")
            finalPath
        } else {
            Log.w("FilePathConversion", "Storage directory was null for type: $type")
            null
        }
    }

    private var currentDialogView: View? = null
    private var currentEmulatorConfigBuilder: TempEmulatorConfigBuilder? = null
    data class TempEmulatorConfigBuilder(
        var name: String = "",
        var sourceRomDirectoryUri: String = "",
        var emulatorPackageName: String = "",
        var emulatorActivityName: String = "",
        var customExtrasJson: String = "{}"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulator_settings)

        // --- Toolbar Setup ---
        // Example: If you have a Toolbar with id 'toolbar' in activity_emulator_settings.xml
        // and your Activity theme is a .NoActionBar theme:
        // val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        // setSupportActionBar(toolbar)
        // supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // supportActionBar?.title = "Emulator Configurations" // Or from manifest's android:label
        // --- End Toolbar Setup ---

        recyclerView = findViewById(R.id.rvEmulatorConfigs)
        fabAddEmulator = findViewById(R.id.fabAddEmulatorConfig)

        setupRecyclerView()
        loadConfigs()

        fabAddEmulator.setOnClickListener {
            currentEditingConfig = null // Ensure we are adding a new one
            showAddEditEmulatorDialog(null)
        }
    }

    private fun setupRecyclerView() {
        emulatorConfigsAdapter = EmulatorConfigsAdapter(
            mutableListOf(),
            onEdit = { config ->
                currentEditingConfig = config
                showAddEditEmulatorDialog(config)
            },
            onDelete = { config ->
                showDeleteConfirmationDialog(config)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = emulatorConfigsAdapter
    }

    private fun loadConfigs() {
        val configs = EmulatorConfigManager.loadEmulatorConfigs(this)
        emulatorConfigsAdapter.updateData(configs)
    }

    private fun showDeleteConfirmationDialog(config: EmulatorConfig) {
        AlertDialog.Builder(this)
            .setTitle("Delete Configuration")
            .setMessage("Are you sure you want to delete '${config.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                EmulatorConfigManager.deleteEmulatorConfig(this, config.id)
                loadConfigs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddEditEmulatorDialog(configToEdit: EmulatorConfig?) {
        val dialogViewInflated = LayoutInflater.from(this).inflate(R.layout.dialog_add_edit_emulator, null)
        currentDialogView = dialogViewInflated
        currentEmulatorConfigBuilder = TempEmulatorConfigBuilder() // Always start fresh for the builder

        val etName = dialogViewInflated.findViewById<EditText>(R.id.etEmulatorName)
        val btnSelectSourceDirectory = dialogViewInflated.findViewById<Button>(R.id.btnSelectSourceRomDirectory)
        val etSourceDirectoryDisplay = dialogViewInflated.findViewById<EditText>(R.id.etSourceRomDirectory)
        val btnSelectEmulatorApp = dialogViewInflated.findViewById<Button>(R.id.btnSelectEmulatorApp)
        val etEmulatorPackage = dialogViewInflated.findViewById<EditText>(R.id.etEmulatorPackage)
        val etEmulatorActivity = dialogViewInflated.findViewById<EditText>(R.id.etEmulatorActivity)
        val etCustomParams = dialogViewInflated.findViewById<EditText>(R.id.etCustomParameters)

        etSourceDirectoryDisplay.isEnabled = false // User selects via button

        if (configToEdit != null) {
            currentEditingConfig = configToEdit // Keep track of which original config is being edited
            currentEmulatorConfigBuilder?.apply {
                name = configToEdit.name
                sourceRomDirectoryUri = configToEdit.sourceRomDirectoryUri
                emulatorPackageName = configToEdit.emulatorPackageName
                emulatorActivityName = configToEdit.emulatorActivityName
                customExtrasJson = Gson().toJson(bundleToMap(configToEdit.customExtras))
            }
            etName.setText(currentEmulatorConfigBuilder?.name)
            etSourceDirectoryDisplay.setText(currentEmulatorConfigBuilder?.sourceRomDirectoryUri)
            etEmulatorPackage.setText(currentEmulatorConfigBuilder?.emulatorPackageName)
            etEmulatorActivity.setText(currentEmulatorConfigBuilder?.emulatorActivityName)
            etCustomParams.setText(currentEmulatorConfigBuilder?.customExtrasJson)
        } else {
            currentEditingConfig = null // Explicitly ensure no config is being edited
            // For new config, currentEmulatorConfigBuilder is already fresh
            etCustomParams.setText("{}") // Default for new config
        }

        btnSelectSourceDirectory.setOnClickListener {
            directoryPickerLauncher.launch(null) // selected URI will update currentEmulatorConfigBuilder
        }

        btnSelectEmulatorApp.setOnClickListener {
            showAppPickerDialog { selectedAppInfo ->
                currentEmulatorConfigBuilder?.emulatorPackageName = selectedAppInfo.packageName
                etEmulatorPackage.setText(selectedAppInfo.packageName)

                if (!selectedAppInfo.activityName.isNullOrEmpty()) {
                    currentEmulatorConfigBuilder?.emulatorActivityName = selectedAppInfo.activityName
                    etEmulatorActivity.setText(selectedAppInfo.activityName)
                } else {
                    currentEmulatorConfigBuilder?.emulatorActivityName = ""
                    etEmulatorActivity.setText("")
                    etEmulatorActivity.hint = "Enter Activity Name" // Prompt for manual entry
                    Toast.makeText(this, "Could not determine main activity. Please enter manually.", Toast.LENGTH_LONG).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (configToEdit == null) "Add Emulator Config" else "Edit Emulator Config")
            .setView(dialogViewInflated)
            .setPositiveButton(if (configToEdit ==null) "Add" else "Save") { dialogInterface, _ ->
                // Retrieve values directly from TempEmulatorConfigBuilder which should be up-to-date
                val name = currentEmulatorConfigBuilder?.name ?: etName.text.toString().trim()
                val sourceDir = currentEmulatorConfigBuilder?.sourceRomDirectoryUri ?: etSourceDirectoryDisplay.text.toString().trim()
                val emuPackage = currentEmulatorConfigBuilder?.emulatorPackageName ?: etEmulatorPackage.text.toString().trim()
                val emuActivity = currentEmulatorConfigBuilder?.emulatorActivityName ?: etEmulatorActivity.text.toString().trim()
                val paramsJson = etCustomParams.text.toString().trim() // Custom params can be directly from EditText

                // Update builder from EditTexts as a final catch-all, in case of direct edits
                currentEmulatorConfigBuilder?.name = etName.text.toString().trim()
                currentEmulatorConfigBuilder?.emulatorPackageName = etEmulatorPackage.text.toString().trim()
                currentEmulatorConfigBuilder?.emulatorActivityName = etEmulatorActivity.text.toString().trim()
                // sourceRomDirectoryUri is updated by directoryPickerLauncher

                if (currentEmulatorConfigBuilder?.name.isNullOrBlank() ||
                    currentEmulatorConfigBuilder?.sourceRomDirectoryUri.isNullOrBlank() ||
                    currentEmulatorConfigBuilder?.emulatorPackageName.isNullOrBlank() ||
                    currentEmulatorConfigBuilder?.emulatorActivityName.isNullOrBlank()) {
                    Toast.makeText(this, "All fields except Custom Parameters are required.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton // Don't dismiss, keep dialog open
                }

                val customExtrasBundle = Bundle()
                try {
                    if (paramsJson.isNotBlank() && paramsJson != "{}") {
                        val type = object : TypeToken<Map<String, String>>() {}.type
                        val paramsMap: Map<String, String> = Gson().fromJson(paramsJson, type)
                        paramsMap.forEach { (key, value) -> customExtrasBundle.putString(key, value) }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error parsing custom parameters JSON: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("EmulatorSettings", "JSON parsing error", e)
                    return@setPositiveButton // Don't dismiss
                }

                val finalConfig = EmulatorConfig(
                    id = currentEditingConfig?.id ?: UUID.randomUUID().toString(),
                    name = currentEmulatorConfigBuilder!!.name, // Use !! as we checked for null/blank
                    sourceRomDirectoryUri = currentEmulatorConfigBuilder!!.sourceRomDirectoryUri,
                    emulatorPackageName = currentEmulatorConfigBuilder!!.emulatorPackageName,
                    emulatorActivityName = currentEmulatorConfigBuilder!!.emulatorActivityName,
                    customExtras = customExtrasBundle
                )

                if (currentEditingConfig == null) { // It's a new config
                    EmulatorConfigManager.addEmulatorConfig(this, finalConfig)
                } else { // It's an existing config being edited
                    EmulatorConfigManager.updateEmulatorConfig(this, finalConfig)
                }
                loadConfigs() // Refresh the list
                dialogInterface.dismiss()
                cleanUpAfterDialog()
            }
            .setNegativeButton("Cancel") { dialogInterface, _ ->
                dialogInterface.cancel()
                cleanUpAfterDialog()
            }
            .setCancelable(false) // Or true, depending on preference
            .show()
    }

    private fun cleanUpAfterDialog() {
        currentDialogView = null
        currentEmulatorConfigBuilder = null
        currentEditingConfig = null
    }


    private fun showAppPickerDialog(onAppSelected: (appInfo: AppInfo) -> Unit) {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        Log.d("AppPicker", "Querying for ACTION_MAIN, CATEGORY_LAUNCHER activities...")
        val appPackages: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
        Log.d("AppPicker", "Found ${appPackages.size} ResolveInfo objects initially.")

        if (appPackages.isEmpty()) {
            Toast.makeText(this, "No launchable applications found by initial query.", Toast.LENGTH_SHORT).show()
            return
        }

        val appList = mutableListOf<AppInfo>()
        for (resolveInfo in appPackages) {
            val activityInfo = resolveInfo.activityInfo
            if (activityInfo != null) {
                try {
                    val appName = resolveInfo.loadLabel(pm)
                    val packageName = activityInfo.packageName
                    val activityName = activityInfo.name
                    val icon = resolveInfo.loadIcon(pm)
                    appList.add(AppInfo(appName, packageName, activityName, icon))
                } catch (e: Exception) {
                    Log.e("AppPicker", "Error loading info for package: ${activityInfo.packageName}. Skipping.", e)
                }
            }
        }
        Log.d("AppPicker", "Successfully loaded ${appList.size} apps into the list for the dialog.")

        if (appList.isEmpty()) {
            Toast.makeText(this, "No applications could be loaded into the picker.", Toast.LENGTH_SHORT).show()
            return
        }

        Collections.sort(appList, compareBy { it.appName.toString().lowercase() })

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker_layout, null)
        val recyclerViewDialog = dialogView.findViewById<RecyclerView>(R.id.rvAppPickerListInDialog)
        // recyclerViewDialog.layoutManager = LinearLayoutManager(this) // Can be set in XML too

        lateinit var dialog: AlertDialog // Declare dialog here

        val appPickerAdapter = AppPickerAdapter(appList) { selectedAppInfo ->
            onAppSelected(selectedAppInfo)
            dialog.dismiss() // Dismiss the dialog directly
        }

        recyclerViewDialog.adapter = appPickerAdapter

        dialog = AlertDialog.Builder(this)
            .setTitle("Select Emulator Application")
            .setView(dialogView)
            .setNegativeButton("Cancel") { d, _ ->
                d.dismiss()
            }
            .create()

        dialog.show()
    }

    private fun bundleToMap(bundle: Bundle): Map<String, String> {
        val map = mutableMapOf<String, String>()
        bundle.keySet().forEach { key ->
            val value = bundle.get(key)
            if (value is String) {
                map[key] = value
            } else if (value != null) {
                map[key] = value.toString()
            }
        }
        return map
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanUpAfterDialog() // Just in case a dialog was somehow left open
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // NavUtils.navigateUpFromSameTask(this) // If parentActivityName is set
            finish() // Simpler way to go back
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun handleDirectorySelection(selectedTreeUri: Uri?) {
        selectedTreeUri?.let { treeUri ->
            // Persist read/write permissions (important for SAF)
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, takeFlags)

            var directoryIdentifier: String? = null

            // Try to get the Tree Document ID - this is usually the best "path part"
            if (DocumentsContract.isTreeUri(treeUri)) { // Check if it's indeed a tree URI
                directoryIdentifier = DocumentsContract.getTreeDocumentId(treeUri)
                if (directoryIdentifier != null) {
                    Log.i("EmulatorSettings", "Using TreeDocumentId as directory identifier: $directoryIdentifier")
                } else {
                    Log.w("EmulatorSettings", "Could not get TreeDocumentId from tree URI: $treeUri. Falling back to generic extraction.")
                    directoryIdentifier = extractPathIdentifierFromGenericUri(treeUri)
                }
            } else {
                Log.w("EmulatorSettings", "Selected URI is not a tree URI: $treeUri. Attempting to extract path identifier.")
                directoryIdentifier = extractPathIdentifierFromGenericUri(treeUri)
            }


            if (directoryIdentifier != null) {
                // *** CORRECTED PART ***
                // Update the builder, which is then used to create/update the final config
                currentEmulatorConfigBuilder?.sourceRomDirectoryUri = directoryIdentifier
                Log.i("EmulatorSettings", "Assigned directoryIdentifier to builder: $directoryIdentifier")

                // Update the UI EditText if it's visible and being used to display this
                // (Assuming etSourceDirectoryDisplay is the EditText from your showAddEditEmulatorDialog)
                currentDialogView?.findViewById<EditText>(R.id.etSourceRomDirectory)?.setText(directoryIdentifier)

            } else {
                Log.e("EmulatorSettings", "Could not derive a usable directory identifier from URI: $treeUri")
                Toast.makeText(this, "Could not identify the selected directory.", Toast.LENGTH_SHORT).show()
                // Optionally clear the EditText if it failed:
                // currentDialogView?.findViewById<EditText>(R.id.etSourceRomDirectory)?.setText("")
            }
        }
    }

    // Helper to extract a path-like identifier if not a direct tree URI
    // This logic should mirror parts of getSourceDirectoryFromUri from MainActivity
    private fun extractPathIdentifierFromGenericUri(uri: Uri): String? {
        if (DocumentsContract.isDocumentUri(this, uri)) {
            val documentId = DocumentsContract.getDocumentId(uri)
            // If it's a tree URI passed here, its documentId is the treeDocumentId
            if (documentId.contains(":")) { // e.g. "primary:Games" or "primary:Games/Subfolder"
                return documentId // The documentId itself might be the path we want
            }
            // Add more specific parsing here if needed, similar to getSourceDirectoryFromUri's non-tree document parsing
            Log.w("EmulatorSettings", "extractPathIdentifierFromGenericUri: Could not simply use documentId for: $documentId")
            return documentId // Fallback to raw documentId if no ":"
        } else if (ContentResolver.SCHEME_FILE == uri.scheme) {
            return uri.path // For file URIs, the path is the identifier (potentially parent path if it's a file)
        }
        // Add other schemes or more complex parsing if needed
        Log.w("EmulatorSettings", "extractPathIdentifierFromGenericUri: Unhandled URI scheme or structure: $uri")
        return uri.toString() // Fallback to full string if no better parsing
    }
}
