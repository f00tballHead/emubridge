package com.emutools.emubridge

import android.os.Bundle
import java.util.UUID

data class EmulatorConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceRomDirectoryUri: String,
    val emulatorPackageName: String,
    val emulatorActivityName: String,
    val customExtras: Bundle = Bundle()
)