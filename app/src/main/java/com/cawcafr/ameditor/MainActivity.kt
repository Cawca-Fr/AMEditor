package com.cawcafr.ameditor

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var selectApkButton: Button
    private lateinit var processButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private var apkFile: File? = null
    private var lastRebuiltApk: File? = null
    private var originalFileName: String = "unknown.apk"

    // Gestionnaire pour sauvegarder le fichier final
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
                appendLog("✅ APK saved successfully!\n")

                // --- MODIFICATION ICI ---
                // On affiche le chemin "nettoyé"
                val readablePath = getReadablePathFromUri(uri)
                appendLog("📂 Location: $readablePath\n")
                // ------------------------

                Toast.makeText(this, "Save successful!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SaveApk", "Save error", e)
                appendLog("❌ Error saving APK: ${e.message}\n")
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            appendLog("ℹ️ Save cancelled by user.\n")
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Configuration de la Toolbar
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        selectApkButton = findViewById(R.id.selectApkButton)
        processButton = findViewById(R.id.processButton)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        // Sélecteur de fichier (Input)
        val pickApkLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    originalFileName = getFileName(uri) ?: "app.apk"

                    // Copie en cache pour pouvoir le manipuler
                    val cacheFileName = "selected_internal.apk"
                    val copiedFileInCache = copyUriToCache(uri, cacheFileName)

                    apkFile = copiedFileInCache

                    appendLog("📦 APK selected: $originalFileName\n")
                    processButton.isEnabled = true

                } else {
                    appendLog("⚠️ No file selected\n")
                }
            }

        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        // Bouton "Lancer le Patch"
        processButton.setOnClickListener {
            val currentApkFile = apkFile ?: return@setOnClickListener

            // Remplace R.string.log_header par un string en dur ou assure-toi que ta ressource est en anglais
            logTextView.text = "--- Logs ---\n"

            appendLog("⏳ Starting processing of $originalFileName...\n")
            processButton.isEnabled = false

            Thread {
                try {
                    val apkPatcher = ApkManifestPatcher(this)
                    val finalOutputApk = File(cacheDir, "mod_${System.currentTimeMillis()}.apk")

                    // APPEL AVEC CALLBACK DE LOG
                    val result = apkPatcher.patchApkManifest(
                        currentApkFile,
                        finalOutputApk
                    ) { logMessage ->
                        // FILTRE AMÉLIORÉ
                        // On ignore les composants désactivés, les permissions supprimées ET les Warnings non critiques
                        if (!logMessage.startsWith("Disabled component:") &&
                            !logMessage.startsWith("Removed permission:") &&
                            !logMessage.startsWith("Warning:")) { // <-- AJOUT ICI (Masque les Warnings)

                            runOnUiThread {
                                appendLog("$logMessage\n")
                            }
                        }
                    }

                    when (result) {
                        is PatchResult.Success -> {
                            runOnUiThread {
                                appendLog("🎉 SUCCESS!\n")
                                lastRebuiltApk = result.outputApk
                                Toast.makeText(this@MainActivity, "Please save the file.", Toast.LENGTH_LONG).show()

                                val suggestedName = "MOD_$originalFileName"
                                saveApkLauncher.launch(suggestedName)
                                processButton.isEnabled = true
                            }
                        }
                        is PatchResult.Error -> {
                            runOnUiThread {
                                appendLog("❌ FAILURE: ${result.message}\n")
                                processButton.isEnabled = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        appendLog("❌ Crash: ${e.message}\n")
                        processButton.isEnabled = true
                    }
                }
            }.start()
        }
    }

    // --- Utilitaires ---

    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
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
        return fileName
    }

    private fun copyUriToCache(uri: Uri, desiredFileName: String): File {
        val inputStream: InputStream? = contentResolver.openInputStream(uri)
        val outFile = File(cacheDir, desiredFileName)
        if (outFile.exists()) outFile.delete()

        val outputStream = FileOutputStream(outFile)
        inputStream?.copyTo(outputStream)
        inputStream?.close()
        outputStream.close()
        return outFile
    }

    private fun getReadablePathFromUri(uri: Uri): String {
        var path = uri.path ?: return "Unknown"
        // Décoder les caractères spéciaux (%20 -> espace, %2F -> /, etc.)
        path = java.net.URLDecoder.decode(path, "UTF-8")

        // Remplacer le format "primary:" par le chemin standard Android
        if (path.contains("primary:")) {
            path = path.replaceAfter("primary:", "") + "/storage/emulated/0/" + path.substringAfter("primary:")
            path = path.replace("/document/primary:", "") // Nettoyage final
        }
        // Nettoyage spécifique aux DocumentsProvider
        if (path.startsWith("/document/")) {
            path = path.replace("/document/", "")
        }

        return path
    }

    private fun appendLog(message: String) {
        // Mise à jour de l'UI sur le thread principal si besoin
        if (Thread.currentThread() == mainLooper.thread) {
            logTextView.append(message)
            logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        } else {
            runOnUiThread {
                logTextView.append(message)
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }
}
