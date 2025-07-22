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
import android.widget.CheckBox // Added for Intent Flags
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
// import androidx.compose.ui.graphics.vector.path // Not used
// import androidx.compose.ui.semantics.setText // Not used
// import androidx.core.content.getSystemService // Not used
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
import android.content.pm.PackageManager
import android.os.Build

// Assume EmulatorConfig is defined elsewhere and includes an intentFlags: Int field
// data class EmulatorConfig(
//    val id: String = UUID.randomUUID().toString(),
//    val name: String,
//    val sourceRomDirectoryUri: String,
//    val emulatorPackageName: String,
//    val emulatorActivityName: String,
//    val intentFlags: Int = 0, // IMPORTANT: Ensure this field exists
//    val customExtras: Bundle = Bundle()
// )

data class AppInfo(
    val appName: CharSequence,
    val packageName: String,
    val activityName: String?,
    val icon: Drawable?
)

class EmulatorSettingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddEmulator: FloatingActionButton
    private lateinit var emulatorConfigsAdapter: EmulatorConfigsAdapter
    private var currentEditingConfig: EmulatorConfig? = null
    private val gson = Gson() // For JSON processing

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            // Updated to call handleDirectorySelection
            handleDirectorySelection(uri)
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
        var intentFlags: Int = 0, // Added for Intent Flags
        var customExtrasJson: String = "{}"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulator_settings)

        // --- Toolbar Setup ---
        // Example: If you have a Toolbar with id 'toolbar' in activity_emulator_settings.xml
        // and your Activity theme is a .NoActionBar theme:
        // val toolbar: com.google.android.material.appbar.MaterialToolbar = findViewById(R.id.toolbar)
        // setSupportActionBar(toolbar)
        // supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // supportActionBar?.title = "Emulator Configurations" // Or from manifest's android:label

        // Enable Up button if you have a parent activity declared in the manifest
        // supportActionBar?.setDisplayHomeAsUpEnabled(true)


        recyclerView = findViewById(R.id.rvEmulatorConfigs)
        fabAddEmulator = findViewById(R.id.fabAddEmulatorConfig)

        setupRecyclerView()
        loadConfigs()

        fabAddEmulator.setOnClickListener {
            currentEditingConfig = null
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
        // Ensure EmulatorConfigManager and EmulatorConfig are correctly defined elsewhere
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

        // --- Intent Flag CheckBoxes ---
        val cbFlagNewTask = dialogViewInflated.findViewById<CheckBox>(R.id.cbFlagActivityNewTask)
        val cbFlagClearTop = dialogViewInflated.findViewById<CheckBox>(R.id.cbFlagActivityClearTop)
        val cbFlagSingleTop = dialogViewInflated.findViewById<CheckBox>(R.id.cbFlagActivitySingleTop)
        val cbFlagClearTask = dialogViewInflated.findViewById<CheckBox>(R.id.cbFlagActivityClearTask)
        val cbFlagNoHistory = dialogViewInflated.findViewById<CheckBox>(R.id.cbFlagActivityNoHistory)
        // --- End Intent Flag CheckBoxes ---


        etSourceDirectoryDisplay.isEnabled = false // User selects via button

        if (configToEdit != null) {
            currentEditingConfig = configToEdit
            currentEmulatorConfigBuilder?.apply {
                name = configToEdit.name
                sourceRomDirectoryUri = configToEdit.sourceRomDirectoryUri ?: ""
                emulatorPackageName = configToEdit.emulatorPackageName
                emulatorActivityName = configToEdit.emulatorActivityName
                intentFlags = configToEdit.intentFlags // Load intent flags
                customExtrasJson = gson.toJson(bundleToMap(configToEdit.customExtras))
            }
            etName.setText(currentEmulatorConfigBuilder?.name)
            etSourceDirectoryDisplay.setText(currentEmulatorConfigBuilder?.sourceRomDirectoryUri)
            etEmulatorPackage.setText(currentEmulatorConfigBuilder?.emulatorPackageName)
            etEmulatorActivity.setText(currentEmulatorConfigBuilder?.emulatorActivityName)
            etCustomParams.setText(currentEmulatorConfigBuilder?.customExtrasJson)

            // Set CheckBox states from loaded config
            currentEmulatorConfigBuilder?.intentFlags?.let { flags ->
                cbFlagNewTask.isChecked = (flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
                cbFlagClearTop.isChecked = (flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                cbFlagSingleTop.isChecked = (flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                cbFlagClearTask.isChecked = (flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0
                cbFlagNoHistory.isChecked = (flags and Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
            }

        } else {
            currentEditingConfig = null
            etCustomParams.setText("{}") // Default for new config
            // Ensure checkboxes are unchecked for new config (default state)
            cbFlagNewTask.isChecked = false
            cbFlagClearTop.isChecked = false
            cbFlagSingleTop.isChecked = false
            cbFlagClearTask.isChecked = false
        }

        btnSelectSourceDirectory.setOnClickListener {
            directoryPickerLauncher.launch(null)
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
                    etEmulatorActivity.hint = "Enter Activity Name"
                    Toast.makeText(this, "Could not determine main activity. Please enter manually.", Toast.LENGTH_LONG).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (configToEdit == null) "Add Emulator Config" else "Edit Emulator Config")
            .setView(dialogViewInflated)
            .setPositiveButton(if (configToEdit == null) "Add" else "Save") { dialogInterface, _ ->
                // Update builder from EditTexts for final values
                currentEmulatorConfigBuilder?.name = etName.text.toString().trim()
                // sourceRomDirectoryUri is updated by directoryPickerLauncher/handleDirectorySelection
                currentEmulatorConfigBuilder?.emulatorPackageName = etEmulatorPackage.text.toString().trim()
                currentEmulatorConfigBuilder?.emulatorActivityName = etEmulatorActivity.text.toString().trim()
                currentEmulatorConfigBuilder?.customExtrasJson = etCustomParams.text.toString().trim()


                // --- Combine Intent Flags from CheckBoxes ---
                var combinedFlags = 0
                if (cbFlagNewTask.isChecked) combinedFlags = combinedFlags or Intent.FLAG_ACTIVITY_NEW_TASK
                if (cbFlagClearTop.isChecked) combinedFlags = combinedFlags or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (cbFlagSingleTop.isChecked) combinedFlags = combinedFlags or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (cbFlagClearTask.isChecked) {
                    combinedFlags = combinedFlags or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    // Ensure NEW_TASK is also set if CLEAR_TASK is set, as it's required
                    if (!cbFlagNewTask.isChecked) {
                        combinedFlags = combinedFlags or Intent.FLAG_ACTIVITY_NEW_TASK
                        Log.i("EmulatorSettings", "FLAG_ACTIVITY_NEW_TASK implicitly added with FLAG_ACTIVITY_CLEAR_TASK.")
                        // Optionally, you could also check cbFlagNewTask visually here if you want to be strict
                    }
                }
                currentEmulatorConfigBuilder?.intentFlags = combinedFlags
                // --- End Combine Intent Flags ---

                if (currentEmulatorConfigBuilder?.name.isNullOrBlank() ||
                    currentEmulatorConfigBuilder?.sourceRomDirectoryUri.isNullOrBlank() || // Ensure this is not just ""
                    currentEmulatorConfigBuilder?.emulatorPackageName.isNullOrBlank() ||
                    currentEmulatorConfigBuilder?.emulatorActivityName.isNullOrBlank()) {
                    Toast.makeText(this, "All fields except Custom Parameters are required.", Toast.LENGTH_LONG).show()
                    // Re-show dialog without dismissing by not calling dialogInterface.dismiss() directly
                    // This requires a more complex dialog setup to prevent auto-dismissal on positive button.
                    // For simplicity here, we'll let it dismiss and the user has to re-open.
                    // A better way is to get the positive button from the dialog and set its onClickListener manually
                    // to control dismissal.
                    return@setPositiveButton
                }

                val customExtrasBundle = Bundle()
                try {
                    val paramsJson = currentEmulatorConfigBuilder!!.customExtrasJson
                    if (paramsJson.isNotBlank() && paramsJson != "{}") {
                        val type = object : TypeToken<Map<String, String>>() {}.type
                        val paramsMap: Map<String, String> = gson.fromJson(paramsJson, type)
                        paramsMap.forEach { (key, value) -> customExtrasBundle.putString(key, value) }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error parsing custom parameters JSON: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e("EmulatorSettings", "JSON parsing error", e)
                    return@setPositiveButton
                }

                val finalConfig = EmulatorConfig(
                    id = currentEditingConfig?.id ?: UUID.randomUUID().toString(),
                    name = currentEmulatorConfigBuilder!!.name,
                    sourceRomDirectoryUri = currentEmulatorConfigBuilder!!.sourceRomDirectoryUri,
                    emulatorPackageName = currentEmulatorConfigBuilder!!.emulatorPackageName,
                    emulatorActivityName = currentEmulatorConfigBuilder!!.emulatorActivityName,
                    intentFlags = currentEmulatorConfigBuilder!!.intentFlags, // Save intent flags
                    customExtras = customExtrasBundle
                )

                if (currentEditingConfig == null) {
                    EmulatorConfigManager.addEmulatorConfig(this, finalConfig)
                } else {
                    EmulatorConfigManager.updateEmulatorConfig(this, finalConfig)
                }
                loadConfigs()
                dialogInterface.dismiss() // Dismiss on success
                cleanUpAfterDialog()
            }
            .setNegativeButton("Cancel") { dialogInterface, _ ->
                dialogInterface.cancel()
                cleanUpAfterDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun cleanUpAfterDialog() {
        currentDialogView = null
        currentEmulatorConfigBuilder = null
        currentEditingConfig = null
    }

    private fun showAppPickerDialog(onAppSelected: (appInfo: AppInfo) -> Unit) {
        val pm = packageManager
        val mainLauncherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        Log.d("AppPicker", "Querying for ACTION_MAIN, CATEGORY_LAUNCHER activities...")

        val launchablePackages: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainLauncherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainLauncherIntent, 0) // Use 0 for default behavior
        }
        Log.d("AppPicker", "Found ${launchablePackages.size} LAUNCHER ResolveInfo objects initially.")

        if (launchablePackages.isEmpty()) {
            Toast.makeText(this, "No launchable applications found.", Toast.LENGTH_SHORT).show()
            return
        }

        val appList = mutableListOf<AppInfo>()
        val processedPackages = mutableSetOf<String>()

        for (launcherResolveInfo in launchablePackages) {
            val launcherActivityInfo = launcherResolveInfo.activityInfo
            if (launcherActivityInfo == null || processedPackages.contains(launcherActivityInfo.packageName)) {
                continue
            }

            val appName = launcherResolveInfo.loadLabel(pm)
            val packageName = launcherActivityInfo.packageName
            val icon = launcherResolveInfo.loadIcon(pm)
            var activityNameToUse = launcherActivityInfo.name // Default to the main launcher activity
            var specificActivityFoundByName = false

            try {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                }

                if (packageInfo.activities != null) {
                    for (activity in packageInfo.activities) {
                        // Check for the specific name.
                        // activity.name is fully qualified (e.g., com.example.EmulatorActivity)
                        if (activity.name.endsWith(".EmulationActivity")) { // Or your exact desired name
                            activityNameToUse = activity.name // Use the fully qualified name
                            specificActivityFoundByName = true
                            Log.i("AppPicker", "Found specific activity '.EmulationActivity' in package '$packageName': $activityNameToUse")
                            break // Found it, no need to check further activities in this package
                        }
                    }
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("AppPicker", "Package not found when trying to get its activities: $packageName", e)
                // Continue with the default launcher activity for this app (activityNameToUse is already set to launcher)
            } catch (e: Exception) {
                Log.e("AppPicker", "Error processing package activities for $packageName: ${e.message}", e)
            }

            if (!specificActivityFoundByName) {
                Log.d("AppPicker", "Specific '.EmulationActivity' not found in package '$packageName'. Using default launcher: ${launcherActivityInfo.name}")
            }

            appList.add(AppInfo(appName, packageName, activityNameToUse, icon))
            processedPackages.add(packageName)
        }

        Log.d("AppPicker", "Successfully processed ${appList.size} apps into the list for the dialog.")

        if (appList.isEmpty()) {
            Toast.makeText(this, "No applications could be loaded into the picker.", Toast.LENGTH_SHORT).show()
            return
        }

        Collections.sort(appList, compareBy { it.appName.toString().lowercase() })

        // ... rest of your dialog showing code (LayoutInflater, RecyclerView setup, AlertDialog) ...
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker_layout, null)
        val recyclerViewDialog = dialogView.findViewById<RecyclerView>(R.id.rvAppPickerListInDialog)
        lateinit var dialog: AlertDialog // make it accessible for dismiss
        val appPickerAdapter = AppPickerAdapter(appList) { selectedAppInfo ->
            onAppSelected(selectedAppInfo)
            dialog.dismiss() // dismiss the dialog on selection
        }
        recyclerViewDialog.adapter = appPickerAdapter
        if (recyclerViewDialog.layoutManager == null) { // Ensure LayoutManager is set
            recyclerViewDialog.layoutManager = LinearLayoutManager(this)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Select Emulator Application")
            .setView(dialogView)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
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
                map[key] = value.toString() // Convert other types to string for simplicity
            }
        }
        return map
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanUpAfterDialog()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // NavUtils.navigateUpFromSameTask(this) // If using NavUtils for Up navigation
            finish() // Simple back navigation
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun handleDirectorySelection(selectedTreeUri: Uri?) {
        selectedTreeUri?.let { treeUri ->
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                Log.d("EmulatorSettings", "Persisted permission for URI: $treeUri")
            } catch (e: SecurityException) {
                Log.e("EmulatorSettings", "Failed to persist permission for $treeUri", e)
                Toast.makeText(this, "Failed to get permanent access to directory.", Toast.LENGTH_LONG).show()
                // Do not proceed if permission failed
            }


            // --- Attempt to convert to File Path (more robust handling) ---
            val filePath = convertSafTreeUriToFilePath(this, treeUri)
            val directoryIdentifier: String

            if (filePath != null) {
                Log.i("EmulatorSettings", "Using direct File Path: '$filePath'")
                directoryIdentifier = filePath
            } else {
                Log.w("EmulatorSettings", "Could not convert to file path. Using TreeDocumentId as fallback.")
                val treeDocId = if (DocumentsContract.isTreeUri(treeUri)) DocumentsContract.getTreeDocumentId(treeUri) else null
                if (treeDocId != null) {
                    directoryIdentifier = treeDocId
                    Toast.makeText(this, "Using directory ID (path conversion failed).", Toast.LENGTH_SHORT).show()
                } else {
                    // Fallback further to the URI string itself if everything else fails
                    directoryIdentifier = treeUri.toString()
                    Log.e("EmulatorSettings", "Could not get TreeDocumentId or FilePath. Using full URI string: $treeUri")
                    Toast.makeText(this, "Could not identify directory. Storing full URI.", Toast.LENGTH_LONG).show()
                }
            }

            currentEmulatorConfigBuilder?.sourceRomDirectoryUri = directoryIdentifier
            currentDialogView?.findViewById<EditText>(R.id.etSourceRomDirectory)?.setText(directoryIdentifier)

        }
    }


    // extractPathIdentifierFromGenericUri is not strictly needed if convertSafTreeUriToFilePath covers the main cases
    // and you have a fallback to treeUri.toString(). If you need more complex generic URI parsing,
    // you can reinstate or expand it.
}

// Dummy EmulatorConfig and EmulatorConfigManager for compilation if not defined elsewhere
// data class EmulatorConfig(
//    val id: String,
//    val name: String,
//    val sourceRomDirectoryUri: String?,
//    val emulatorPackageName: String,
//    val emulatorActivityName: String,
//    val intentFlags: Int = 0,
//    val customExtras: Bundle = Bundle()
// )

// object EmulatorConfigManager {
//    fun loadEmulatorConfigs(context: Context): MutableList<EmulatorConfig> = mutableListOf()
//    fun addEmulatorConfig(context: Context, config: EmulatorConfig) {}
//    fun updateEmulatorConfig(context: Context, config: EmulatorConfig) {}
//    fun deleteEmulatorConfig(context: Context, id: String) {}
// }

// Dummy AppPickerAdapter for compilation
// class AppPickerAdapter(
//    private val apps: List<AppInfo>,
//    private val onAppSelected: (AppInfo) -> Unit
// ) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {
//    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) { /* ... */ }
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder { /* ... */ }
//    override fun onBindViewHolder(holder: ViewHolder, position: Int) { /* ... */ }
//    override fun getItemCount(): Int = apps.size
// }

// Added missing PackageManager import

