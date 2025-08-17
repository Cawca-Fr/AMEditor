package com.cawcafr.ameditor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.cawcafr.ameditor.axml.AXMLWriter
import com.cawcafr.ameditor.util.ApkRebuilder
import com.cawcafr.ameditor.util.ApkRebuilder.rebuildApkJava
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private val LEGACY_STORAGE_PERMISSION_REQUEST_CODE = 124
    private lateinit var selectApkButton: Button
    private lateinit var processButton: Button
    private lateinit var logTextView: TextView
    private var selectedApkUri: Uri? = null
    private var tempProcessedApkPath: String? = null
    private var tempInputApkPath: String? = null

    private val selectApkLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedApkUri = uri
                    logTextView.text = getString(R.string.selected_apk_message, getFileName(uri))
                    processButton.isEnabled = true
                }
            }
        }

    private val manageStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val granted = Environment.isExternalStorageManager()
                Log.d("Permission", "MANAGE_EXTERNAL_STORAGE = $granted")
                Toast.makeText(
                    this,
                    if (granted) getString(R.string.permission_manage_storage_granted)
                    else getString(R.string.permission_manage_storage_needed_for_save),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val saveApkLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
            uri?.let {
                tempProcessedApkPath?.let { path ->
                    try {
                        FileInputStream(File(path)).use { input ->
                            contentResolver.openOutputStream(uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        logTextView.append(
                            "\n${
                                getString(
                                    R.string.apk_saved_successfully_at,
                                    getFileName(uri)
                                )
                            }"
                        )
                        Toast.makeText(
                            this,
                            getString(R.string.apk_saved_successfully),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        logTextView.append("\n${getString(R.string.error_saving_apk, e.message)}")
                    } finally {
                        cleanupTempFiles()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectApkButton = findViewById(R.id.selectApkButton)
        processButton = findViewById(R.id.processButton)
        logTextView = findViewById(R.id.logTextView)
        processButton.isEnabled = false

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        selectApkButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "application/vnd.android.package-archive"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            selectApkLauncher.launch(intent)
        }

        processButton.setOnClickListener {
            if (!hasRequiredPermissions()) {
                checkAndRequestPermissions()
                return@setOnClickListener
            }
            selectedApkUri?.let { uri ->
                val tempApk = copyApkToCache(uri) ?: return@let
                tempInputApkPath = tempApk.absolutePath
                Log.d(
                    "APK-CHECK",
                    "Python will process: ${tempApk.absolutePath}  size=${tempApk.length()}"
                )
                val outApk = File(cacheDir, "processed_${getFileName(uri)}")
                tempProcessedApkPath = outApk.absolutePath

                Thread {
                    try {
                        val result = Python.getInstance()
                            .getModule("manifestpatcher")
                            .callAttr("process_apk", tempApk.absolutePath, cacheDir.absolutePath)

                        runOnUiThread {
                            if (result != null) {
                                try {
                                    val success = result.get(PyObject.fromJava("success"))?.toBoolean() == true

                                    if (success) {
                                        val patchedXmlPath = result.get(PyObject.fromJava("patched_xml"))?.toString()
                                        val tmpDirPath     = result.get(PyObject.fromJava("tmpdir"))?.toString()

                                        if (!patchedXmlPath.isNullOrEmpty() && !tmpDirPath.isNullOrEmpty()) {
                                            val patchedXmlFile = File(patchedXmlPath)
                                            val newAxmlFile    = File(tmpDirPath, "AndroidManifest.axml")

                                            // 1️⃣ Encodage en AXML via AXMLWriter
                                            AXMLWriter.encode(patchedXmlFile, newAxmlFile.outputStream())

                                            // 2️⃣ Reconstruction de l'APK via notre utilitaire séparé
                                            val outputApk = File(cacheDir, "processed_${getFileName(uri)}")
                                            ApkRebuilder.rebuildApkJava(File(tempInputApkPath!!), newAxmlFile, outputApk)

                                            tempProcessedApkPath = outputApk.absolutePath
                                            saveApkLauncher.launch("modified_${getFileName(uri)}")
                                        } else {
                                            logTextView.append(
                                                "\n${getString(R.string.python_script_error, "Missing patched XML/tmpdir")}"
                                            )
                                            cleanupTempFiles()
                                        }
                                    } else {
                                        val errorMsg = result.get(PyObject.fromJava("error"))?.toString() ?: "Unknown error"
                                        logTextView.append(
                                            "\n${getString(R.string.python_script_error, errorMsg)}"
                                        )
                                        cleanupTempFiles()
                                    }

                                } catch (e: Exception) {
                                    logTextView.append(
                                        "\n${getString(R.string.python_script_error, "Invalid return type: ${result.toString()}")}"
                                    )
                                    cleanupTempFiles()
                                }
                            } else {
                                logTextView.append(
                                    "\n${getString(R.string.python_script_error, "Null return from Python")}"
                                )
                                cleanupTempFiles()
                            }
                        }

                    } catch (e: Exception) {
                        Log.e("ProcessApk", "Error processing APK", e)
                        runOnUiThread {
                            logTextView.append("\n${e.message}")
                            cleanupTempFiles()
                        }
                    }
                }.start()
            } ?: Toast.makeText(this, getString(R.string.select_apk_first), Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun copyApkToCache(uri: Uri): File? =
        try {
            val tempFile = File(cacheDir, "input_${System.currentTimeMillis()}.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            if (tempFile.length() > 0) tempFile else null
        } catch (e: Exception) {
            Log.e("CopyApk", "Failed to copy APK", e)
            null
        }

    private fun hasRequiredPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
        }

    private fun checkAndRequestPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    setData("package:$packageName".toUri())
                }
                manageStoragePermissionLauncher.launch(intent)
                return false
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (permissions.any {
                    ContextCompat.checkSelfPermission(
                        this,
                        it
                    ) != PackageManager.PERMISSION_GRANTED
                }) {
                ActivityCompat.requestPermissions(
                    this,
                    permissions,
                    LEGACY_STORAGE_PERMISSION_REQUEST_CODE
                )
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LEGACY_STORAGE_PERMISSION_REQUEST_CODE &&
            grantResults.any { it != PackageManager.PERMISSION_GRANTED }
        ) {
            Toast.makeText(
                this,
                getString(R.string.permission_legacy_storage_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    result = cursor.getString(nameIndex)
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result ?: "unknown.apk"
    }

    private fun cleanupTempFiles() {
        tempInputApkPath?.let { File(it).delete() }
        tempInputApkPath = null
        tempProcessedApkPath?.let { File(it).delete() }
        tempProcessedApkPath = null
    }
}