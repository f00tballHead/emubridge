# EmuBridge for Android

EmuBridge is an Android application that acts as a bridge to launch ROMs with various emulators after first copying the ROM file to a user defined directory. It's particularly useful for launching ROM's from internal storage on devices with slow SD cards.

EmuBridge handles:
1.  **Receiving a ROM URI via Intent**: Another app (like a frontend) can send an Intent to EmuBridge with the URI of the ROM file.
2.  **User-configurable ROM Cache Directory**: Users can select a directory (using the Storage Access Framework - SAF) where ROMs will be temporarily copied or accessed. This is necessary because many emulators cannot directly access ROMs via the content URI provided by the frontend, especially if the frontend uses SAF for its own ROM storage.
3.  **User-configurable Default Emulator**: Users can set a preferred emulator package and activity in the settings.
4.  **Emulator-Specific Target via Intent**: The calling app can specify a target emulator package and activity directly in the Intent, overriding the user's default.
5.  **Copying ROM to Cache**: EmuBridge copies the ROM from the incoming URI to its configured cache directory. This makes the ROM accessible to emulators that require direct file paths or cannot handle SAF URIs.
6.  **Cleanup of Cache Directory**: Before copying a new ROM, it cleans up the cache directory (optionally keeping the current ROM if it's the same file being launched again).
7.  **Dynamic Intent Extras & Placeholder Replacement**:
    *   EmuBridge can receive arbitrary String extras in the launch Intent.
    *   It supports a special placeholder `%ROMCACHE%` within these extra values. Before launching the emulator, EmuBridge replaces any occurrence of `%ROMCACHE%` with the actual URI of the ROM file in its cache directory. This allows frontends to dynamically configure emulator arguments, such as specifying a core or a content path.
8.  **Launching the Emulator**: EmuBridge constructs a new Intent to launch the target emulator, setting the ROM's URI (from the cache) as the Intent's data and including any processed extras.

## How It Works

1.  A frontend application (e.g., ES-DE) wants to launch a game.
2.  The frontend sends an `Intent` to `com.emutools.emubridge.MainActivity`.
    *   The `Intent.data` is set to the `Uri` of the ROM file.
    *   Required: `TARGET_EMULATOR_PACKAGE` (String extra) specifies the Android package name of the emulator.
    *   Required: `TARGET_EMULATOR_ACTIVITY` (String extra) specifies the fully qualified class name of the emulator's main launchable Activity.
    *   Optional: Any other String extras can be included. If their value is `"%ROMCACHE%"`, it will be replaced by the ROM's cached URI. If their value *contains* `"%ROMCACHE%"`, that part will be replaced.
3.  EmuBridge `MainActivity` receives the Intent.
4.  It loads the user's preferred SAF directory for caching ROMs (user must configure this in EmuBridge settings first.
5.  It cleans the cache directory.
6.  It copies the ROM from the input URI to a file within this cache directory.
7.  It processes any additional extras, replacing `%ROMCACHE%` with the new cache URI.
8.  It builds a new `Intent` for the target emulator:
    *   `Intent.setData()` is set to the `Uri` of the ROM file in EmuBridge's cache.
    *   `Intent.setClassName(targetEmulatorPackage, targetEmulatorActivity)` sets the specific emulator component.
    *   `Intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`
    *   `Intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)`
    *   Any processed extras are added to this new Intent.
9.  EmuBridge starts the emulator's activity.
10. The emulator launches with the ROM.

## Setup & Configuration

### 1. Install EmuBridge
   Install the EmuBridge APK on your Android device.

### 2. Configure EmuBridge Settings
   Open EmuBridge:
   *   **ROM Destination Directory**: This is the most crucial step. You **must** select a folder where EmuBridge can copy ROMs. Use the "Select ROM Directory" option and grant access to a folder of your choice (e.g., `Internal Storage/EmuBridgeCache/`). This directory will be managed by EmuBridge. IMPORTANT: Ensure this is an empty directory that is only used for EmuBridge as this gets cleared and I won't be held responsible for any data loss!)

### 3. Frontend Configuration Examples (ES-DE)

ES-DE (EmulationStation Desktop Edition) for Android allows you to define custom commands for launching emulators. Here's how you can configure it to use EmuBridge.

You'll typically modify your 'es_fine_rules.xml' and 'es_systems.xml' files within your custom configuration directory.

Example config for es_find_rules.xml:

```xml
    <emulator name="EMUBRIDGE">
        <!-- EmuBridge -->
        <rule type="androidpackage">
            <entry>com.emutools.emubridge/.MainActivity</entry>
        </rule>
    </emulator>
```

Example es_systems.xml config line for PS2 using AetherSX2:

```xml
<command label="EmuBridge"> %EMULATOR_EMUBRIDGE% %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER% %EXTRA_TARGET_EMULATOR_PACKAGE%=xyz.aethersx2.android %EXTRA_TARGET_EMULATOR_ACTIVITY%=xyz.aethersx2.android.EmulationActivity %EXTRA_bootPath%=%ROMCACHE%</command>
```

Example es_systems.xml config line for Saturn using RetroArch Yabause core:

```xml
<command label="EmuBridge">%EMULATOR_EMUBRIDGE% %ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER% %EXTRA_TARGET_EMULATOR_PACKAGE%=com.retroarch %EXTRA_TARGET_EMULATOR_ACTIVITY%=com.retroarch.browser.retroactivity.RetroActivityFuture %EXTRA_CONFIGFILE%=/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg %EXTRA_LIBRETRO%=yabause_libretro_android.so %EXTRA_ROM%=%ROM%</command>
```


Example 'player' config for Daijiso:
<img width="3040" height="1904" alt="Screenshot_20250717-204631" src="https://github.com/user-attachments/assets/cc4b8fb8-6aef-46c5-9fab-92276a38038e" />
