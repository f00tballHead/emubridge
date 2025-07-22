package com.emutools.emubridge

import android.os.Bundle
import java.util.UUID

data class EmulatorConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceRomDirectoryUri: String,
    val emulatorPackageName: String,
    val emulatorActivityName: String,
    val intentFlags: Int = 0,
    val customExtras: Bundle = Bundle()
) {
    fun hasFlag(flag: Int): Boolean {
        return (intentFlags and flag) == flag
    }
}