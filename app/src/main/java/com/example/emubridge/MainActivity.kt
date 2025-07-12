package com.example.emubridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        val romUri = intent?.data
        if (romUri != null) {
            handleRomLaunch(romUri)
        } else {
            statusText.text = "No ROM provided"
        }
    }

    private fun handleRomLaunch(romUri: Uri) {
        val romName = getFileNameFromUri(romUri) ?: "rom.bin"
        val targetFile = File(romCacheDir, romName)

        progressBar.visibility = View.VISIBLE
        statusText.text = "Copying ROM..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val copied = copyWithProgress(romUri, targetFile)
                if (copied) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Launch emulator..."
                        launchEmulator(targetFile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Copy failed."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.localizedMessage}"
                }
            }
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

    private fun launchEmulator(romFile: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClassName("com.retroarch", "com.retroarch.browser.retroactivity.RetroActivityFuture")
            setDataAndType(
                FileProvider.getUriForFile(
                    this@MainActivity,
                    "${packageName}.fileprovider",
                    romFile
                ),
                "application/octet-stream"
            )
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch emulator", Toast.LENGTH_LONG).show()
        }

        finish()
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
