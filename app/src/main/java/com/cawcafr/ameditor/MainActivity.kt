package com.cawcafr.ameditor

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cawcafr.ameditor.axml.AXMLWriter
import com.cawcafr.ameditor.util.ApkRebuilder
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var selectApkButton: Button
    private lateinit var processButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var manifestToSave: File? = null

    private val saveManifestLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        manifestToSave?.let { manifestFile ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        FileInputStream(manifestFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    appendLog("✅ Binary manifest copied successfully to: $uri\n")
                    Toast.makeText(this, "Binary manifest saved!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("SaveManifest", "Error saving binary manifest", e)
                    appendLog("❌ Error saving binary manifest: ${e.message}\n")
                    Toast.makeText(this, "Error saving manifest: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else { // uri is null
                appendLog("ℹ️ Binary manifest save canceled by user.\n")
            }
        } ?: run { // Block executed if manifestToSave is null
            if (uri != null) { // manifestToSave was null, but the user still chose a file (unlikely but handled)
                appendLog("❌ Error: No manifest file to save was found (manifestToSave was null).\n")
                Toast.makeText(this, "Error: Manifest to save not found.", Toast.LENGTH_LONG).show()
            } else { // manifestToSave AND uri are null (most likely case if manifestToSave is null)
                appendLog("ℹ️ Manifest save attempt canceled (missing manifest or URI).\n")
            }
        }
        // manifestToSave = null // Optional: reset
    }

    private var apkFile: File? = null

    private var lastRebuiltApk: File? = null

    private val saveApkLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        if (uri != null && lastRebuiltApk != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(lastRebuiltApk!!).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                appendLog("✅ APK copied successfully to the chosen folder\n")
                Toast.makeText(this, "APK copied successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SaveApk", "Error saving APK", e)
                appendLog("❌ Error saving APK: ${e.message}\n")
                Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            if (uri == null) {
                appendLog("ℹ️ Save canceled by user.\n")
            } else { // lastRebuiltApk is null
                appendLog("❌ Error: No rebuilt APK to save found.\n")
                Toast.makeText(this, "Error: APK to save not found.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        selectApkButton = findViewById(R.id.selectApkButton)
        processButton = findViewById(R.id.processButton)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        // New system for choosing an APK
        val pickApkLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    val originalFileName = getFileName(uri)
                    val cacheFileName = "selected_internal_apk.apk"
                    val copiedFileInCache = copyUriToCache(uri, cacheFileName)

                    apkFile = copiedFileInCache

                    appendLog("📦 APK selected: $originalFileName (copied to cache as ${copiedFileInCache.name})\n")

                } else {
                    appendLog("⚠️ No file selected\n")
                }
            }

        // APK selection
        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        // APK processing
        processButton.setOnClickListener {
            val currentApkFile = apkFile
            if (currentApkFile == null) {
                appendLog("⚠️ No APK selected.\n")
                return@setOnClickListener
            }

            appendLog("⏳ Starting processing of ${currentApkFile.name}...\n")

            Thread {
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("manifestpatcher")

                    val resultJson = module.callAttr("process_apk", currentApkFile.absolutePath).toString()
                    val result = JSONObject(resultJson)

                    val status = result.optString("status")
                    val message = result.optString("message", "")
                    val patchedXmlPath = result.optString("patchedXmlPath")
                    val tmpDirPath = result.optString("tmpDirPath")
                    val removedTrackers = result.optInt("removed_trackers", 0)
                    val neutralizedOneSignal = result.optInt("neutralized_onesignal", 0)

                    runOnUiThread {
                        appendLog("🐍 Python script finished.\n")
                        if (status == "success") {
                            appendLog("✅ Status: Success\n")
                            if (message.isNotEmpty()) appendLog("ℹ️ Message: $message\n")
                            appendLog("📄 Patched XML: $patchedXmlPath\n")
                            appendLog("📂 Temporary directory (for XML): $tmpDirPath\n")
                            appendLog("🕵️ Trackers removed: $removedTrackers\n")
                            appendLog("🔕 OneSignal neutralized: $neutralizedOneSignal\n")
                        } else {
                            appendLog("❌ Python script failed\nStatus: $status\n")
                            if (message.isNotEmpty()) appendLog("Message: $message\n")
                            val errorMsg = result.optString("error", "Unknown Python script error.")
                            appendLog("Error: $errorMsg\n")
                        }
                    }

                    if (status != "success") return@Thread
                    if (patchedXmlPath.isNullOrEmpty() || tmpDirPath.isNullOrEmpty()) {
                        runOnUiThread { appendLog("⚠️ Missing information (patchedXmlPath or tmpDirPath) to continue the process.\n") }
                        return@Thread
                    }

                    val patchedXmlFile = File(patchedXmlPath)
                    if (!patchedXmlFile.exists()) {
                        runOnUiThread { appendLog("❌ Patched XML file not found: $patchedXmlPath\n") }
                        return@Thread
                    }

                    val tmpDirFile = File(tmpDirPath)
                    if (!tmpDirFile.exists() && !tmpDirFile.mkdirs()) {
                        runOnUiThread { appendLog("❌ Unable to create temporary directory for AXML: $tmpDirPath\n") }
                        return@Thread
                    }
                    val newAxmlFile = File(tmpDirPath, "AndroidManifest.axml")

                    runOnUiThread { appendLog("⚙️ Re-encoding XML → AXML in progress…\n") }

                    // Conversion XML → AXML - NEW VERSION with corrected AXMLWriter
                    try {
                        FileOutputStream(newAxmlFile).use { out ->
                            AXMLWriter.encode(patchedXmlFile, out)
                        }

                        // Check that the file is not empty
                        val fileLength = newAxmlFile.length()
                        if (fileLength == 0L) {
                            val errorMsg = "❌ CRITICAL ERROR: The generated AndroidManifest.axml file is EMPTY (${newAxmlFile.absolutePath}). Encoding probably failed."
                            Log.e("AXMLWriter", errorMsg)
                            runOnUiThread { appendLog("$errorMsg\n") }
                            throw IOException("Generated AXML is empty, probable encoding error.")
                        }

                        // Copy of the patched manifest for debug
                        try {
                            val parentDir = File(apkFile!!.absolutePath).parentFile
                            val manifestCopy = File(parentDir, "AndroidManifest_patched.axml")
                            FileInputStream(newAxmlFile).use { input ->
                                FileOutputStream(manifestCopy).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            runOnUiThread {
                                appendLog("✅ Patched manifest copied to selected folder: ${manifestCopy.absolutePath}\n")
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                appendLog("❌ Error when copying the patched Manifest: ${e.message}\n")
                            }
                        }

                        runOnUiThread {
                            appendLog("✅ AXML re-encoding finished: ${newAxmlFile.absolutePath}\n")
                            appendLog("📏 AXML manifest size on disk: $fileLength bytes\n")
                        }

                        // Update variable and preparation for backup
                        manifestToSave = newAxmlFile

                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Please select where to save the binary manifest", Toast.LENGTH_LONG).show()
                            saveManifestLauncher.launch("AndroidManifest_patched.axml")
                            appendLog("📄 Choose a location to save the binary AndroidManifest.axml file (for debugging).\n")
                        }

                        // APK reconstruction
                        runOnUiThread { appendLog("🛠️ Rebuilding APK in progress…\n") }
                        try {
                            val outputApk = File(cacheDir, "processed_${currentApkFile.name}")
                            ApkRebuilder.rebuildApkWithZipFileStrict(currentApkFile, newAxmlFile, outputApk)
                            runOnUiThread {
                                lastRebuiltApk = outputApk
                                appendLog("🎉 APK rebuilt successfully: ${outputApk.absolutePath}\n")

                                Toast.makeText(this@MainActivity, "Please select where you want to save the app", Toast.LENGTH_LONG).show()

                                val preferredSuggestedFileName = "patched_${currentApkFile.name}"
                                saveApkLauncher.launch(preferredSuggestedFileName)

                                appendLog("ℹ️ Choose the folder where to save the modified APK.\n")
                            }
                        } catch (e: Exception) {
                            runOnUiThread { appendLog("❌ Error during APK reconstruction: ${e.message}\n") }
                        }

                    } catch (e: AXMLWriter.AXMLEncodeException) {
                        // Specific management of AXML encoding errors
                        Log.e("AxmlRebuild", "AXML encoding error", e)
                        runOnUiThread {
                            appendLog("❌ AXML encoding failed: ${e.message}\n")
                            if (e.cause != null) {
                                appendLog("🔧 Root cause: ${e.cause?.message}\n")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AxmlRebuild", "Error during re-encoding or reconstruction", e)
                        runOnUiThread {
                            appendLog("❌ Critical error during AXML re-encoding or APK reconstruction: ${e.message}\n")
                        }
                    }

                } catch (e: Exception) {
                    runOnUiThread { appendLog("❌ Major exception during Python processing: ${e.message}\n") }
                    Log.e("ProcessApkThread", "Error in the APK processing thread", e)
                }
            }.start()
        }
    }

    /** Copies the selected file (content:// URI) into the app cache */
    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        this.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    fileName = cursor.getString(displayNameIndex)
                }
            }
        }
        if (fileName == null) {
            fileName = uri.lastPathSegment
        }
        return fileName ?: "unknown_temp_apk.apk"
    }

    private fun copyUriToCache(uri: Uri, desiredFileName: String): File {
        val inputStream: InputStream? = contentResolver.openInputStream(uri)
        val outFile = File(cacheDir, desiredFileName)
        val outputStream = FileOutputStream(outFile)
        inputStream?.copyTo(outputStream)
        inputStream?.close()
        outputStream.close()
        return outFile
    }

    /** Adds text to the log area */
    private fun appendLog(message: String) {
        logTextView.append(message)
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}