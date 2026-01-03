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
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
    private var originalFileName: String = "unknown.apk" // Pour garder le nom "Facebook.apk" par exemple

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
                appendLog("❌ Erreur sauvegarde: ${e.message}\n")
                Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            appendLog("ℹ️ Sauvegarde annulée ou fichier manquant.\n")
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

        val pickApkLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                if (uri != null) {
                    // On récupère le vrai nom (ex: insta.apk)
                    originalFileName = getFileName(uri) ?: "app.apk"

                    // On copie dans le cache avec un nom fixe pour travailler dessus
                    val cacheFileName = "selected_internal_apk.apk"
                    val copiedFileInCache = copyUriToCache(uri, cacheFileName)

                    apkFile = copiedFileInCache

                    appendLog("📦 APK sélectionné : $originalFileName\n")
                    processButton.isEnabled = true // On active le bouton seulement maintenant

                } else {
                    appendLog("⚠️ Aucun fichier sélectionné\n")
                }
            }

        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        processButton.setOnClickListener {
            val currentApkFile = apkFile
            if (currentApkFile == null) {
                appendLog("⚠️ Aucun APK sélectionné.\n")
                return@setOnClickListener
            }

            appendLog("⏳ Démarrage du patch de $originalFileName...\n")

            // On lance le travail dans un Thread séparé pour ne pas bloquer l'interface
            Thread {
                try {
                    val apkPatcher = ApkManifestPatcher(this)
                    // Fichier de sortie temporaire
                    val outputApk = File(cacheDir, "patched_temp.apk")

                    // CORRECTION ICI : Appel de la bonne méthode 'patchApkManifest'
                    when (val result = apkPatcher.patchApkManifest(currentApkFile, outputApk)) {
                        is PatchResult.Success -> {
                            runOnUiThread {
                                val stats = result.stats
                                appendLog("✅ SUCCÈS ! \n   - Composants supprimés : ${stats.removedComponents}\n   - Configs neutralisées : ${stats.neutralizedConfigs}\n")
                                lastRebuiltApk = result.outputApk
                                appendLog("🎉 APK prêt. Ouverture de la sauvegarde...\n")

                                Toast.makeText(this@MainActivity, "Choisissez où sauvegarder l'APK patché", Toast.LENGTH_LONG).show()

                                // On propose le nom d'origine préfixé
                                val suggestedName = "MOD_$originalFileName"
                                saveApkLauncher.launch(suggestedName)
                            }
                        }
                        is PatchResult.Error -> {
                            runOnUiThread {
                                appendLog("❌ Erreur fatale : ${result.message}\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { appendLog("❌ Exception critique : ${e.message}\n") }
                    Log.e("ProcessApkThread", "Erreur Thread", e)
                }
            }.start()
        }
    }

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
        // Écraser si existe déjà
        if (outFile.exists()) outFile.delete()

        val outputStream = FileOutputStream(outFile)
        inputStream?.copyTo(outputStream)
        inputStream?.close()
        outputStream.close()
        return outFile
    }

    private fun appendLog(message: String) {
        logTextView.append(message)
        // Scroll automatique vers le bas
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}