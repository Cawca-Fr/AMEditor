package com.cawcafr.ameditor

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
// Plus besoin d'importer ApkRebuilder ici, c'est géré en interne par le Patcher
// Plus d'imports Chaquopy (Python)
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
                appendLog("✅ APK sauvegardé avec succès !\n")
                Toast.makeText(this, "Sauvegarde réussie !", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SaveApk", "Erreur sauvegarde", e)
                appendLog("❌ Erreur lors de la sauvegarde : ${e.message}\n")
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            appendLog("ℹ️ Sauvegarde annulée par l'utilisateur.\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // SUPPRESSION : Python.start() n'est plus nécessaire !

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

                    appendLog("📦 APK sélectionné : $originalFileName\n")
                    processButton.isEnabled = true

                } else {
                    appendLog("⚠️ Aucun fichier sélectionné\n")
                }
            }

        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        // Bouton "Lancer le Patch"
        processButton.setOnClickListener {
            val currentApkFile = apkFile ?: return@setOnClickListener

            logTextView.text = getString(R.string.log_header)

            appendLog("⏳ Démarrage du traitement de $originalFileName...\n")
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
                        // Cette fonction est appelée depuis le Patcher/Sanitizer
                        // On doit s'assurer de toucher l'UI sur le thread principal
                        runOnUiThread {
                            appendLog("$logMessage\n")
                        }
                    }

                    when (result) {
                        is PatchResult.Success -> {
                            runOnUiThread {
                                appendLog("🎉 SUCCÈS TOTAL !\n")
                                lastRebuiltApk = result.outputApk
                                Toast.makeText(this@MainActivity, "Sauvegardez le fichier.", Toast.LENGTH_LONG).show()

                                val suggestedName = "MOD_$originalFileName"
                                saveApkLauncher.launch(suggestedName)
                                processButton.isEnabled = true
                            }
                        }
                        is PatchResult.Error -> {
                            runOnUiThread {
                                appendLog("❌ ÉCHEC : ${result.message}\n")
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