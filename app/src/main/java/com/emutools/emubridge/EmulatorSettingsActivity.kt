package com.emutools.emubridge

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.Button
// CheckBox import is removed as individual checkboxes are gone from the main dialog
import android.widget.EditText
import android.widget.TextView // Added for displaying selected flags
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar // Assuming you have a Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.Collections
import java.util.UUID
import android.view.ViewGroup

// Assume EmulatorConfig is defined elsewhere and includes an intentFlags: Int field
// data class EmulatorConfig(
//    val id: String = UUID.randomUUID().toString(),
//    val name: String,
//    val sourceRomDirectoryUri: String?, // Make nullable if it can be empty initially
//    val emulatorPackageName: String,
//    val emulatorActivityName: String,
//    val intentFlags: Int = 0,
//    val customExtras: Bundle = Bundle()
// )

// --- Data class and Repository for Intent Flags ---
data class IntentFlagItem(
    val name: String,
    val flagValue: Int,
    var isSelected: Boolean = false
)

object IntentFlagsRepository {
    val availableFlags: List<IntentFlagItem> by lazy { // Use lazy to initialize once
        listOf(
            IntentFlagItem("New Task", Intent.FLAG_ACTIVITY_NEW_TASK),
            IntentFlagItem("Clear Top", Intent.FLAG_ACTIVITY_CLEAR_TOP),
            IntentFlagItem("Single Top", Intent.FLAG_ACTIVITY_SINGLE_TOP),
            IntentFlagItem("Clear Task", Intent.FLAG_ACTIVITY_CLEAR_TASK),
            IntentFlagItem("No History", Intent.FLAG_ACTIVITY_NO_HISTORY),
            IntentFlagItem("Reorder to Front", Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            IntentFlagItem("Previous is Top", Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP),
            IntentFlagItem("Exclude from Recents", Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            IntentFlagItem("Brought to Front", Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT),
            IntentFlagItem("Reset Task if Needed", Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            IntentFlagItem("Launched From History", Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
            IntentFlagItem("Multiple Task", Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            IntentFlagItem("No Animation", Intent.FLAG_ACTIVITY_NO_ANIMATION),
            IntentFlagItem("No User Action", Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            IntentFlagItem("Retain in Recents", Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS),
            IntentFlagItem("Task On Home", Intent.FLAG_ACTIVITY_TASK_ON_HOME)
            // Add more flags as desired
        ).sortedBy { it.name }
    }

    fun getFlagsWithSelection(currentCombinedFlags: Int): List<IntentFlagItem> {
        return availableFlags.map { flagItem ->
            flagItem.copy(isSelected = (currentCombinedFlags and flagItem.flagValue) != 0)
        }
    }

    fun combineSelectedFlags(selectedItems: List<IntentFlagItem>): Int {
        return selectedItems.filter { it.isSelected }.fold(0) { acc, item -> acc or item.flagValue }
    }
}
// --- End Intent Flags Data ---


class EmulatorSettingsActivity : AppCompatActivity() {

    companion object {
        private val PREFERRED_EMULATOR_ACTIVITIES = mapOf(
            "com.retroarch.aarch64" to "com.retroarch.browser.retroactivity.RetroActivityFuture",
            "com.retroarch.ra32" to "com.retroarch.browser.retroactivity.RetroActivityFuture",
            "com.retroarch" to "com.retroarch.browser.retroactivity.RetroActivityFuture",
            "com.explusalpha.A2600Emu" to "com.imagine.BaseActivity",
            "xyz.aethersx2.android" to "xyz.aethersx2.android.EmulationActivity",
            "com.explusalpha.C64Emu" to "com.imagine.BaseActivity",
            "org.citra.citra_emu" to "org.citra.citra_emu.activities.EmulationActivity",
            "org.citra.citra_emu.canary" to "org.citra.citra_emu.activities.EmulationActivity",
            "org.citra.emu" to "org.citra.emu.ui.EmulationActivity",
            "com.antutu.ABenchMark" to "org.citra.emu.ui.EmulationActivity",
            "com.fms.colem.deluxe" to "com.fms.emulib.TVActivity",
            "com.fms.colem" to "com.fms.emulib.TVActivity",
            "org.dolphinemu.dolphinemu" to "org.dolphinemu.dolphinemu.ui.main.TvMainActivity",
            "org.mm.jr" to "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            "org.dolphinemu.mmjr" to "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            "com.amigan.droidarcadia" to "com.amigan.droidarcadia.MainActivity",
            "com.dsemu.drastic" to "com.dsemu.drastic.DraSticActivity",
            "com.github.stenzek.duckstation" to "com.github.stenzek.duckstation.EmulationActivity",
            "com.github.eka2l1" to "com.github.eka2l1.MainActivity",
            "com.epsxe.ePSXe" to "com.epsxe.ePSXe.ePSXe",
            "com.flycast.emulator" to "com.flycast.emulator.MainActivity",
            "com.fms.fmsx.deluxe" to "com.fms.emulib.TVActivity",
            "com.fms.fmsx" to "com.fms.emulib.TVActivity",
            "com.emulator.fpse" to "com.emulator.fpse.Main",
            "com.emulator.fpse64" to "com.emulator.fpse64.Main",
            "com.explusalpha.GbaEmu" to "com.imagine.BaseActivity",
            "com.explusalpha.GbcEmu" to "com.imagine.BaseActivity",
            "com.fms.ines.free" to "com.fms.emulib.TVActivity",
            "ru.vastness.altmer.iratajaguar" to "ru.vastness.altmer.iratajaguar.EmulatorActivity",
            "io.github.lime3ds.android" to "io.github.lime3ds.android.activities.EmulationActivity",
            "com.explusalpha.LynxEmu" to "com.imagine.BaseActivity",
            "org.mupen64plusae.v3.fzurita.amazon" to "paulscode.android.mupen64plusae.SplashActivity",
            "org.mupen64plusae.v3.fzurita.pro" to "paulscode.android.mupen64plusae.SplashActivity",
            "org.mupen64plusae.v3.fzurita" to "paulscode.android.mupen64plusae.SplashActivity",
            "com.seleuco.mame4droid" to "com.seleuco.mame4droid.MAME4droid",
            "com.seleuco.mame4d2024" to "com.seleuco.mame4droid.MAME4droid",
            "com.fms.mg" to "com.fms.emulib.TVActivity",
            "com.explusalpha.MdEmu" to "com.imagine.BaseActivity",
            "me.magnum.melonds" to "me.magnum.melonds.ui.emulator.EmulatorActivity",
            "me.magnum.melonds.nightly" to "me.magnum.melonds.ui.emulator.EmulatorActivity",
            "com.explusalpha.MsxEmu" to "com.imagine.BaseActivity",
            "org.mupen64plusae.v3.alpha" to "paulscode.android.mupen64plusae.SplashActivity",
            "com.fastemulator.gba" to "com.fastemulator.gba.EmulatorActivity",
            "com.fastemulator.gbc" to "com.fastemulator.gbc.EmulatorActivity",
            "com.explusalpha.NeoEmu" to "com.imagine.BaseActivity",
            "com.explusalpha.NesEmu" to "com.imagine.BaseActivity",
            "com.androidemu.nes" to "com.androidemu.nes.EmulatorActivity",
            "com.explusalpha.NgpEmu" to "com.imagine.BaseActivity",
            "org.openbor.engine" to "org.openbor.engine.GameActivity",
            "com.panda3ds.pandroid" to "com.panda3ds.pandroid.app.MainActivity",
            "com.PceEmu" to "com.imagine.BaseActivity",
            "it.dbtecno.pizzaboygbapro" to "it.dbtecno.pizzaboygbapro.MainActivity",
            "it.dbtecno.pizzaboygba" to "it.dbtecno.pizzaboygba.MainActivity",
            "it.dbtecno.pizzaboypro" to "it.dbtecno.pizzaboypro.MainActivity",
            "it.dbtecno.pizzaboy" to "it.dbtecno.pizzaboy.MainActivity",
            "com.virtualapplications.play" to "com.virtualapplications.play.MainActivity",
            "org.ppsspp.ppssppgold" to "org.ppsspp.ppsspp.PpssppActivity",
            "org.ppsspp.ppsspp" to "org.ppsspp.ppsspp.PpssppActivity",
            "ru.vastness.altmer.real3doplayer" to "ru.vastness.altmer.real3doplayer.EmulatorActivity",
            "io.recompiled.redream" to "io.recompiled.redream.MainActivity",
            "rs.ruffle" to "rs.ruffle.MainActivity",
            "com.explusalpha.SaturnEmu" to "com.imagine.BaseActivity",
            "org.scummvm.scummvm.debug" to "org.scummvm.scummvm.SplashActivity",
            "org.scummvm.scummvm" to "org.scummvm.scummvm.SplashActivity",
            "skyline.emu" to "emu.skyline.EmulationActivity",
            "com.explusalpha.Snes9xPlus" to "com.imagine.BaseActivity",
            "com.fms.speccy.deluxe" to "com.fms.emulib.TVActivity",
            "com.fms.speccy" to "com.fms.emulib.TVActivity",
            "com.explusalpha.SwanEmu" to "com.imagine.BaseActivity",
            "org.vpinball.app" to "org.vpinball.app.VpxLauncherActivity",
            "org.vita3k.emulator" to "org.vita3k.emulator.Emulator",
            "org.devmiyax.yabasanshioro2.pro" to "org.uoyabause.android.Yabause",
            "org.devmiyax.yabasanshioro2" to "org.uoyabause.android.Yabause",
            "xyz.aethersx2.custom" to "xyz.aethersx2.android.EmulationActivity",
            "io.github.azaharplus.android" to "org.citra.citra_emu.activities.EmulationActivity",
            "io.github.borked3ds.android" to "io.github.borked3ds.android.activities.EmulationActivity",
            "org.gamerytb.citra.canary" to "org.citra.citra_emu.ui.main.MainActivity",
            "org.citron.citron_emu" to "org.citron.citron_emu.activities.EmulationActivity",
            "org.citron.citron_emu.ea" to "org.citron.citron_emu.activities.EmulationActivity",
            "com.antutu.ABenchMark" to "org.citron.citron_emu.activities.EmulationActivity",
            "com.miHoYo.Yuanshen" to "org.citron.citron_emu.activities.EmulationActivity",
            "org.cytrus.cytrus_emu" to "org.cytrus.cytrus_emu.activities.EmulationActivity",
            "org.mm.j" to "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            "org.shiiion.primehack" to "org.dolphinemu.dolphinemu.ui.main.MainActivity",
            "dev.eden.eden_emulator" to "org.yuzu.yuzu_emu.activities.EmulationActivity",
            "com.miHoYo.Yuanshen" to "org.yuzu.yuzu_emu.activities.EmulationActivity",
            "com.antutu.ABenchMark" to "org.yuzu.yuzu_emu.activities.EmulationActivity",
            "com.andromeda.androbench2" to "org.suyu.suyu_emu.activities.EmulationActivity",
            "net.rpcsx" to "net.rpcsx.MainActivity",
            "org.sudachi.sudachi_emu" to "org.sudachi.sudachi_emu.activities.EmulationActivity",
            "org.sudachi.sudachi_emu.ea" to "org.sudachi.sudachi_emu.activities.EmulationActivity",
            "com.sumi.SumiEmulator" to "org.sumi.sumi_emu.activities.EmulationActivity",
            "com.antutu.ABenchMark" to "org.sumi.sumi_emu.activities.EmulationActivity",
            "org.suyu.suyu_emu" to "org.suyu.suyu_emu.activities.EmulationActivity",
            "dev.suyu.suyu_emu.relWithDebInfo" to "dev.suyu.suyu_emu.activities.EmulationActivity",
            "dev.suyu.suyu_emu" to "dev.suyu.suyu_emu.activities.EmulationActivity",
            "org.uzuy.uzuy_emu" to "org.uzuy.uzuy_emu.activities.EmulationActivity",
            "org.uzuy.uzuy_emu.ea" to "org.uzuy.uzuy_emu.activities.EmulationActivity",
            "org.uzuy.uzuy_emu.mmjr" to "org.uzuy.uzuy_emu.activities.EmulationActivity",
            "com.antutu.ABenchMark.ea" to "org.uzuy.uzuy_emu.activities.EmulationActivity",
            "com.antutu.ABenchMark" to "org.uzuy.uzuy_emu.activities.EmulationActivity",
            "org.vita3k.emulator.ikhoeyZX" to "org.vita3k.emulator.Emulator",
            "com.winlator.cmod" to "com.winlator.cmod.XServerDisplayActivity",
            "org.yuzu.yuzu_emu" to "org.yuzu.yuzu_emu.activities.EmulationActivity",
            "org.yuzu.yuzu_emu.ea" to "org.yuzu.yuzu_emu.activities.EmulationActivity",
            "org.Ziunx.Ziunx_emu.ea" to "org.yuzu.yuzu_emu.activities.EmulationActivity",
            )
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var fabAddEmulator: FloatingActionButton
    private lateinit var emulatorConfigsAdapter: EmulatorConfigsAdapter
    private var currentEditingConfig: EmulatorConfig? = null
    private val gson = Gson()

    private var currentDialogView: View? = null // To hold reference to inflated dialog view if needed
    private var currentEmulatorConfigBuilder: TempEmulatorConfigBuilder? = null

    // For the add/edit dialog's intent flags
    private var currentDialogFlagItems: MutableList<IntentFlagItem> = mutableListOf()
    private var tvSelectedIntentFlagsDisplay: TextView? = null // For add/edit dialog

    data class TempEmulatorConfigBuilder(
        var name: String = "",
        var sourceRomDirectoryUri: String = "",
        var emulatorPackageName: String = "",
        var emulatorActivityName: String = "",
        var emulatorAppName: String? = null,
        var intentFlags: Int = 0,
        var customExtrasJson: String = initialDefaultExtrasJson() // Initialize with default
    ) {
        companion object {
            private fun initialDefaultExtrasJson(): String {
                val defaultBundle = Bundle().apply {
                    putString("DEFAULT_EXTRA_KEY", "default_value")
                    // Add any other defaults that EmulatorConfig has
                }
                // You'll need a Gson instance here.
                // For simplicity, assuming 'gson' is accessible (e.g., a static field or passed in)
                // This is a common pattern: make gson available in the activity or a utility class.
                // If gson is a member of EmulatorSettingsActivity:
                // return EmulatorSettingsActivity.gsonInstance.toJson(defaultBundle.toMap())
                // Or, for a quick example without making gson static:
                val tempGson = com.google.gson.Gson()
                return tempGson.toJson(defaultBundle.toMap()) // Use the toMap() helper
            }
        }
    }

    // Ensure you have the Bundle.toMap() extension function:
    fun Bundle.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        keySet().forEach { key ->
            map[key] = get(key) // Get the actual object
        }
        return map
    }

    private val directoryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            handleDirectorySelection(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emulator_settings)

        recyclerView = findViewById(R.id.rvEmulatorConfigs)
        fabAddEmulator = findViewById(R.id.fabAddEmulatorConfig) // Ensure ID matches

        applyWindowInsets() // Handle system bar overlaps
        setupRecyclerView()
        loadConfigs()

        fabAddEmulator.setOnClickListener {
            currentEditingConfig = null // Reset for new config
            showAddEditEmulatorDialog(null)
        }
    }

    private fun applyWindowInsets() {
        val rootView = findViewById<View>(android.R.id.content).rootView
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply padding to the root or a specific container within your layout
            // For example, if your activity_emulator_settings.xml has a root CoordinatorLayout with id 'rootCoordinatorLayout'
            // val rootCoordinatorLayout = findViewById<CoordinatorLayout>(R.id.rootCoordinatorLayout)
            // rootCoordinatorLayout.updatePadding(left = insets.left, top = insets.top, right = insets.right)
            // For RecyclerView specifically to not go under nav bar
            recyclerView.updatePadding(bottom = insets.bottom)


            // Adjust FAB margin to avoid navigation bar
            fabAddEmulator.updateLayoutParams<ViewGroup.MarginLayoutParams> { // Use correct LayoutParams type
                // Consider if fabAddEmulator is in CoordinatorLayout or other ViewGroup
                // For CoordinatorLayout.LayoutParams, you might need:
                // fabAddEmulator.updateLayoutParams<CoordinatorLayout.LayoutParams> { ... }
                val defaultMargin = resources.getDimensionPixelSize(R.dimen.fab_margin) // Define fab_margin in dimens.xml
                bottomMargin = defaultMargin + insets.bottom
                // Apply right margin as well if needed for consistency with system gestures
                // rightMargin = defaultMargin + insets.right (if fab is end-aligned)
            }
            windowInsets
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
        currentDialogView = dialogViewInflated // Store reference if needed later
        currentEmulatorConfigBuilder = TempEmulatorConfigBuilder()

        val etName = dialogViewInflated.findViewById<EditText>(R.id.etEmulatorName)
        val btnSelectSourceDirectory = dialogViewInflated.findViewById<Button>(R.id.btnSelectSourceRomDirectory)
        val etSourceDirectoryDisplay = dialogViewInflated.findViewById<EditText>(R.id.etSourceRomDirectory)
        val btnSelectEmulatorApp = dialogViewInflated.findViewById<Button>(R.id.btnSelectEmulatorApp)
        val etEmulatorPackage = dialogViewInflated.findViewById<EditText>(R.id.etEmulatorPackage)
        val etEmulatorActivity = dialogViewInflated.findViewById<EditText>(R.id.etEmulatorActivity)
        val etCustomParams = dialogViewInflated.findViewById<EditText>(R.id.etCustomParameters)

        val btnSelectIntentFlags = dialogViewInflated.findViewById<Button>(R.id.btnSelectIntentFlags)
        tvSelectedIntentFlagsDisplay = dialogViewInflated.findViewById(R.id.tvSelectedIntentFlags)

        etSourceDirectoryDisplay.isEnabled = false // User selects via button

        var initialCombinedFlags = 0
        if (configToEdit != null) {
            currentEditingConfig = configToEdit // Keep track of which config is being edited
            currentEmulatorConfigBuilder?.apply {
                name = configToEdit.name
                sourceRomDirectoryUri = configToEdit.sourceRomDirectoryUri ?: ""
                emulatorPackageName = configToEdit.emulatorPackageName
                emulatorActivityName = configToEdit.emulatorActivityName
                intentFlags = configToEdit.intentFlags
                customExtrasJson = gson.toJson(bundleToMap(configToEdit.customExtras))
            }
            etName.setText(currentEmulatorConfigBuilder?.name)
            etSourceDirectoryDisplay.setText(currentEmulatorConfigBuilder?.sourceRomDirectoryUri)
            etEmulatorPackage.setText(currentEmulatorConfigBuilder?.emulatorPackageName)
            etEmulatorActivity.setText(currentEmulatorConfigBuilder?.emulatorActivityName)
            etCustomParams.setText(currentEmulatorConfigBuilder?.customExtrasJson)
            initialCombinedFlags = configToEdit.intentFlags
        } else {
            currentEditingConfig = null
            etCustomParams.setText("{}") // Default for new config
            initialCombinedFlags = 0
        }

        currentDialogFlagItems = IntentFlagsRepository.getFlagsWithSelection(initialCombinedFlags).toMutableList()
        updateSelectedFlagsDisplay(currentDialogFlagItems)

        btnSelectIntentFlags.setOnClickListener {
            showIntentFlagSelectionDialog(currentDialogFlagItems) { updatedFlags ->
                currentDialogFlagItems = updatedFlags.toMutableList()
                updateSelectedFlagsDisplay(currentDialogFlagItems)
            }
        }

        btnSelectSourceDirectory.setOnClickListener {
            directoryPickerLauncher.launch(null) // Pass null if you want to start at the default location
        }

        btnSelectEmulatorApp.setOnClickListener {
            showAppPickerDialog { appInfo ->
                currentEmulatorConfigBuilder?.emulatorPackageName = appInfo.packageName
                etEmulatorPackage.setText(appInfo.packageName)

                val preferredActivity = PREFERRED_EMULATOR_ACTIVITIES[appInfo.packageName]
                var activityNameToUse: String? = null

                if (preferredActivity != null) {
                    activityNameToUse = preferredActivity
                    Log.i(
                        "EmulatorSettings",
                        "Using preferred activity '$activityNameToUse' for package '${appInfo.packageName}'"
                    )
                } else if (!appInfo.activityName.isNullOrEmpty()) {
                    // appInfo.activityName comes from the AppPickerAdapter's resolution
                    activityNameToUse = appInfo.activityName
                    Log.i(
                        "EmulatorSettings",
                        "Using resolved activity '$activityNameToUse' for package '${appInfo.packageName}'"
                    )
                }

                if (activityNameToUse != null) {
                    currentEmulatorConfigBuilder?.emulatorActivityName = activityNameToUse
                    etEmulatorActivity.setText(activityNameToUse)
                } else {
                    // Handle case where no activity could be determined
                    currentEmulatorConfigBuilder?.emulatorActivityName = ""
                    etEmulatorActivity.setText("")
                    Toast.makeText(
                        this@EmulatorSettingsActivity,
                        "Could not determine main activity.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }


        AlertDialog.Builder(this)
            .setTitle(if (configToEdit == null) "Add Emulator Config" else "Edit Emulator Config")
            .setView(dialogViewInflated)
            .setPositiveButton(if (configToEdit == null) "Add" else "Save") { dialogInterface, _ ->
                currentEmulatorConfigBuilder?.name = etName.text.toString().trim()
                // sourceRomDirectoryUri is updated by handleDirectorySelection
                currentEmulatorConfigBuilder?.emulatorPackageName = etEmulatorPackage.text.toString().trim()
                currentEmulatorConfigBuilder?.emulatorActivityName = etEmulatorActivity.text.toString().trim()
                currentEmulatorConfigBuilder?.customExtrasJson = etCustomParams.text.toString().trim()

                var combinedFlags = IntentFlagsRepository.combineSelectedFlags(currentDialogFlagItems)
                if ((combinedFlags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0 &&
                    (combinedFlags and Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                    combinedFlags = combinedFlags or Intent.FLAG_ACTIVITY_NEW_TASK
                    Log.i("EmulatorSettings", "FLAG_ACTIVITY_NEW_TASK implicitly added with FLAG_ACTIVITY_CLEAR_TASK.")
                }
                currentEmulatorConfigBuilder?.intentFlags = combinedFlags

                if (validateConfigBuilder(currentEmulatorConfigBuilder)) {
                    saveEmulatorConfig(currentEmulatorConfigBuilder)
                } else {
                    // Toast for validation is handled in validateConfigBuilder
                    // To prevent dialog closing on validation fail, you'd need a custom dialog
                    // or to re-show the dialog. For simplicity, this example lets it close.
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun validateConfigBuilder(builder: TempEmulatorConfigBuilder?): Boolean {
        if (builder == null) {
            Toast.makeText(this, "Error: Configuration data is missing.", Toast.LENGTH_LONG).show()
            return false
        }
        if (builder.name.isBlank()) {
            Toast.makeText(this, "Emulator Name is required.", Toast.LENGTH_LONG).show()
            return false
        }
        if (builder.sourceRomDirectoryUri.isBlank()) { // URI can be empty string if not selected
            // Allow empty for now, user might not have selected yet or doesn't need it.
            // Or add specific validation: Toast.makeText(this, "Source ROM Directory is required.", Toast.LENGTH_LONG).show(); return false
        }
        if (builder.emulatorPackageName.isBlank()) {
            Toast.makeText(this, "Emulator Package Name is required.", Toast.LENGTH_LONG).show()
            return false
        }
        if (builder.emulatorActivityName.isBlank()) {
            Toast.makeText(this, "Emulator Activity Name is required.", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }


    private fun updateSelectedFlagsDisplay(items: List<IntentFlagItem>) {
        val selected = items.filter { it.isSelected }
        val displayText = if (selected.isEmpty()) {
            "No flags selected"
        } else {
            val names = selected.joinToString { it.name }
            "${selected.size} flags: ${names.take(50)}${if (names.length > 50) "..." else ""}" // Truncate if too long
        }
        tvSelectedIntentFlagsDisplay?.text = displayText // Use safe call as it's part of a dialog
    }

    private fun showIntentFlagSelectionDialog(
        currentSelectedFlagsState: List<IntentFlagItem>,
        onFlagsConfirmed: (List<IntentFlagItem>) -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_recyclerview_picker, null)
        val recyclerViewFlags = dialogView.findViewById<RecyclerView>(R.id.rvPickerList)

        // Important: Create a deep copy for the dialog to modify.
        // This ensures that if the user cancels, the original list (currentDialogFlagItems) isn't changed.
        val dialogWorkingList = currentSelectedFlagsState.map { it.copy() }.toMutableList()
        val adapter = IntentFlagPickerAdapter(dialogWorkingList)

        recyclerViewFlags.adapter = adapter
        recyclerViewFlags.layoutManager = LinearLayoutManager(this)

        AlertDialog.Builder(this)
            .setTitle("Select Intent Flags")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                onFlagsConfirmed(adapter.getAllFlags()) // getAllFlags() returns the (potentially modified) dialogWorkingList
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun saveEmulatorConfig(builder: TempEmulatorConfigBuilder?) {
        builder?.let {
            val customExtrasBundle = try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val map: Map<String, String> = gson.fromJson(it.customExtrasJson.ifBlank { "{}" }, type)
                Bundle().apply {
                    map.forEach { (key, value) -> putString(key, value) }
                }
            } catch (e: Exception) {
                Log.e("EmulatorSettings", "Error parsing custom extras JSON: ${it.customExtrasJson}", e)
                Toast.makeText(this, "Invalid Custom Parameters JSON format.", Toast.LENGTH_LONG).show()
                Bundle() // Return empty bundle on error
            }

            val configToSave = EmulatorConfig(
                id = currentEditingConfig?.id ?: UUID.randomUUID().toString(),
                name = it.name,
                sourceRomDirectoryUri = it.sourceRomDirectoryUri.ifBlank { null }, // Store null if blank
                emulatorPackageName = it.emulatorPackageName,
                emulatorActivityName = it.emulatorActivityName,
                intentFlags = it.intentFlags,
                customExtras = customExtrasBundle
            )

            if (currentEditingConfig == null) {
                EmulatorConfigManager.addEmulatorConfig(this, configToSave)
            } else {
                EmulatorConfigManager.updateEmulatorConfig(this, configToSave)
            }
            loadConfigs() // Refresh the list
        }
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


    private fun showAppPickerDialog(onAppSelected: (appInfo: AppInfo) -> Unit) {
        val pm = packageManager
        val mainLauncherIntent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        Log.d("AppPicker", "Querying for ACTION_MAIN, CATEGORY_LAUNCHER activities...")

        val launchablePackages: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainLauncherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainLauncherIntent, 0)
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
            var activityNameToUse = launcherActivityInfo.name // Default
            var specificActivityFoundByName = false

            try {
                val flagsForGetPackageInfo: Int = PackageManager.GET_ACTIVITIES // This is an Int

                @Suppress("DEPRECATION") // getPackageInfo with int flags is deprecated on API 33+
                val packageInfo = pm.getPackageInfo(packageName, flagsForGetPackageInfo) // Pass the Int directly

                if (packageInfo.activities != null) {
                    for (activity in packageInfo.activities) {
                        if (activity.name.endsWith(".EmulationActivity")) {
                            activityNameToUse = activity.name
                            specificActivityFoundByName = true
                            Log.i("AppPicker", "Found '.EmulationActivity' in '$packageName': $activityNameToUse")
                            break
                        }
                    }
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e("AppPicker", "Package not found for activities: $packageName", e)
            } catch (e: Exception) {
                Log.e("AppPicker", "Error processing package activities for $packageName: ${e.message}", e)
            }

            if (!specificActivityFoundByName) {
                Log.d("AppPicker", "'.EmulationActivity' not found in '$packageName'. Using default: ${launcherActivityInfo.name}")
            }
            appList.add(AppInfo(appName, packageName, activityNameToUse, icon))
            processedPackages.add(packageName)
        }

        if (appList.isEmpty()) {
            Toast.makeText(this, "No apps to display in picker.", Toast.LENGTH_SHORT).show()
            return
        }
        Collections.sort(appList, compareBy { it.appName.toString().lowercase() })

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker_layout, null) // Ensure this layout exists
        val recyclerViewDialog = dialogView.findViewById<RecyclerView>(R.id.rvAppPickerListInDialog) // Ensure this ID exists
        lateinit var dialog: AlertDialog
        val appPickerAdapter = AppPickerAdapter(appList) { selectedAppInfo -> // Ensure AppPickerAdapter exists
            onAppSelected(selectedAppInfo)
            dialog.dismiss()
        }
        recyclerViewDialog.adapter = appPickerAdapter
        recyclerViewDialog.layoutManager = LinearLayoutManager(this)
        dialog = AlertDialog.Builder(this)
            .setTitle("Select Emulator Application")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }


    fun bundleToMap(bundle: Bundle?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        bundle?.keySet()?.forEach { key ->
            bundle.getString(key)?.let { map[key] = it }
        }
        return map
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // NavUtils.navigateUpFromSameTask(this) // Or finish() if it's the top level of this flow
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // Safely convert Long to Int for older API calls
    private fun Long.toIntSafely(): Int {
        if (this > Int.MAX_VALUE || this < Int.MIN_VALUE) {
            Log.w("toIntSafely", "Long value $this is out of Int range, returning 0")
            return 0
        }
        return this.toInt()
    }

    /**
     * Attempts to convert a SAF Tree URI to a direct file path. Highly fragile.
     * Consider removing or using only as a last resort if DocumentFile API is insufficient.
     */
    private fun convertSafTreeUriToFilePath(context: Context, treeUri: Uri): String? {
        if (!DocumentsContract.isTreeUri(treeUri) ||
            treeUri.authority != "com.android.externalstorage.documents") {
            Log.w("FilePathConversion", "URI is not a tree URI from ExternalStorageProvider: $treeUri")
            return null
        }
        val docId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val split = docId.split(":")
        if (split.size < 2) return null
        val type = split[0].lowercase()
        val path = split[1]

        val storageDir: File? = when (type) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> {
                // This SD card detection is very heuristic and likely to fail on many devices/OS versions.
                context.externalCacheDirs.firstNotNullOfOrNull { cacheDir ->
                    cacheDir?.path?.split("/Android")?.getOrNull(0)
                        ?.takeIf { it.contains(type, ignoreCase = true) }
                        ?.let { File(it) }
                }
            }
        }
        return storageDir?.let { File(it, path).absolutePath }
            .also { Log.i("FilePathConversion", "Constructed path: $it for docId: $docId (Type: $type)") }
    }

}
