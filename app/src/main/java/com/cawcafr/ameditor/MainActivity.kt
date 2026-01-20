package com.cawcafr.ameditor

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cawcafr.ameditor.util.SignerUtils
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var selectApkButton: Button
    private lateinit var processButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private lateinit var signCheckBox: CheckBox
    private lateinit var importKeyButton: Button

    private var apkFile: File? = null
    private var lastRebuiltApk: File? = null
    private var originalFileName: String = "unknown.apk"

    private var userKeystoreFile: File? = null
    private var keystorePass: String = ""
    private var keyAlias: String = ""
    private var keyPass: String = ""

    // Gestion de l'état "placeholder" des logs
    private var isLogPlaceholderVisible = true

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
                appendLog("✅ APK Saved successfully!\n")
                Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                appendLog("❌ Save Error: ${e.message}\n")
            }
        }
    }

    private val pickKeystoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(uri)
            // Vérification basique, mais ZipSigner gère JKS et BKS
            if (fileName == null || (!fileName.endsWith(".jks", ignoreCase = true) && !fileName.endsWith(".bks", ignoreCase = true) && !fileName.endsWith(".keystore", ignoreCase = true))) {
                Toast.makeText(this, "Please select a valid keystore (.jks)", Toast.LENGTH_LONG).show()
                // On continue quand même si l'user insiste ? Non, pour l'instant on retourne.
                return@registerForActivityResult
            }

            val cacheKeyFile = File(cacheDir, "user_keystore.jks")
            copyUriToFile(uri, cacheKeyFile)
            userKeystoreFile = cacheKeyFile

            showKeystorePasswordDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectApkButton = findViewById(R.id.selectApkButton)
        processButton = findViewById(R.id.processButton)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)

        signCheckBox = findViewById(R.id.signCheckBox)
        importKeyButton = findViewById(R.id.importKeyButton)

        logTextView.text = "Output Logs"
        logTextView.setTextColor(Color.GRAY)

        importKeyButton.setOnClickListener {
            pickKeystoreLauncher.launch("*/*")
        }

        val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                originalFileName = getFileName(uri) ?: "app.apk"
                val cacheFileName = "selected_internal.apk"
                val copiedFile = File(cacheDir, cacheFileName)
                copyUriToFile(uri, copiedFile)
                apkFile = copiedFile
                appendLog("📦 APK Selected: $originalFileName\n")
                processButton.isEnabled = true
            }
        }

        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        processButton.setOnClickListener {
            if (apkFile == null) return@setOnClickListener

            if (signCheckBox.isChecked && userKeystoreFile == null) {
                Toast.makeText(this, "Please import a signature first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            logTextView.text = ""
            logTextView.setTextColor(Color.BLACK)
            isLogPlaceholderVisible = false

            appendLog("⏳ Starting process for $originalFileName...\n")
            processButton.isEnabled = false

            val shouldSign = signCheckBox.isChecked

            Thread {
                try {
                    val apkPatcher = ApkManifestPatcher(this)
                    val unsignedApk = File(cacheDir, "unsigned_mod.apk")

                    val result = apkPatcher.patchApkManifest(apkFile!!, unsignedApk) { msg ->
                        runOnUiThread { appendLog("$msg\n") }
                    }

                    if (result is PatchResult.Success) {
                        // Par défaut, le fichier final est le fichier patché non signé
                        var finalApk = result.outputApk
                        var outputPrefix = "PATCHED_" // Préfixe par défaut

                        if (shouldSign && userKeystoreFile != null) {
                            runOnUiThread { appendLog("🔏 Signing APK with user key...\n") }

                            val signedApk = File(cacheDir, "signed_mod.apk")
                            try {
                                SignerUtils.signApk(
                                    inputApk = unsignedApk,
                                    outputApk = signedApk,
                                    keystoreFile = userKeystoreFile!!,
                                    keystorePass = keystorePass,
                                    keyAlias = keyAlias,
                                    keyPass = keyPass
                                )
                                // Si on arrive ici, la signature a réussi
                                finalApk = signedApk
                                outputPrefix = "SIGNED_" // On change le préfixe
                                runOnUiThread { appendLog("✅ Signature applied successfully.\n") }
                            } catch (e: Exception) {
                                // Si échec, on garde finalApk = unsignedApk et outputPrefix = "PATCHED_"
                                runOnUiThread { appendLog("❌ Signature Failed: ${e.message}. Saving unsigned version.\n") }
                            }
                        } else {
                            runOnUiThread { appendLog("ℹ️ Skipping signature (User choice).\n") }
                        }

                        lastRebuiltApk = finalApk

                        runOnUiThread {
                            appendLog("🎉 SUCCESS! Output ready.\n")
                            // Utilisation du préfixe dynamique
                            saveApkLauncher.launch("$outputPrefix$originalFileName")
                            processButton.isEnabled = true
                        }
                    } else if (result is PatchResult.Error) {
                        runOnUiThread {
                            appendLog("❌ FAILED: ${result.message}\n")
                            processButton.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        appendLog("❌ Crash: ${e.message}\n")
                        processButton.isEnabled = true
                    }
                    e.printStackTrace()
                }
            }.start()
        }
    }

    private fun showKeystorePasswordDialog() {
        val context = this
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        val padding = dpToPx(24)
        layout.setPadding(padding, dpToPx(10), padding, 0)

        // --- CHAMP 1 : Password ---
        val passLayout = TextInputLayout(context)
        passLayout.hint = "Password"
        passLayout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        val inputPass = TextInputEditText(passLayout.context)
        // TEXTE VISIBLE (Pas de points)
        inputPass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        passLayout.addView(inputPass)

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dpToPx(16))
        passLayout.layoutParams = params
        layout.addView(passLayout)

        // --- CHAMP 2 : Alias ---
        val aliasLayout = TextInputLayout(context)
        aliasLayout.hint = "Alias"
        aliasLayout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        val inputAlias = TextInputEditText(aliasLayout.context)
        inputAlias.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        aliasLayout.addView(inputAlias)
        aliasLayout.layoutParams = params
        layout.addView(aliasLayout)

        // --- CHAMP 3 : Alias Password ---
        val keyPassLayout = TextInputLayout(context)
        keyPassLayout.hint = "Alias Password"
        keyPassLayout.helperText = "Leave empty if same as keystore password"
        keyPassLayout.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        val inputKeyPass = TextInputEditText(keyPassLayout.context)
        // TEXTE VISIBLE
        inputKeyPass.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        keyPassLayout.addView(inputKeyPass)
        layout.addView(keyPassLayout)

        // --- Loading Spinner ---
        val progressBar = ProgressBar(context)
        progressBar.isIndeterminate = true
        progressBar.visibility = View.GONE
        val progressParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        progressParams.gravity = Gravity.CENTER
        progressParams.setMargins(0, dpToPx(10), 0, 0)
        progressBar.layoutParams = progressParams
        layout.addView(progressBar)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Import signature")
            .setView(layout)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val kPass = inputPass.text.toString()
            val alias = inputAlias.text.toString()
            var keyP = inputKeyPass.text.toString()

            if (kPass.isEmpty() || alias.isEmpty()) {
                Toast.makeText(context, "Password and Alias are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (keyP.isEmpty()) keyP = kPass

            progressBar.visibility = View.VISIBLE
            inputPass.isEnabled = false
            inputAlias.isEnabled = false
            inputKeyPass.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

            Thread {
                var success = false
                var errorMsg = ""

                try {
                    // TEST DE CHARGEMENT STANDARD
                    val keyStoreInstance = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType())
                    FileInputStream(userKeystoreFile).use { 
                        keyStoreInstance.load(it, kPass.toCharArray()) 
                    }
                    
                    if (keyStoreInstance.containsAlias(alias)) {
                        // Test du mot de passe de la clé
                        try {
                            val entry = keyStoreInstance.getEntry(alias, java.security.KeyStore.PasswordProtection(keyP.toCharArray()))
                            if (entry != null) success = true
                            else errorMsg = "Cannot access key (wrong key password?)"
                        } catch(e: Exception) {
                             errorMsg = "Key Password Invalid"
                        }
                    } else {
                        errorMsg = "Alias '$alias' not found"
                    }
                    
                } catch (e: Exception) {
                    errorMsg = "Keystore load failed: ${e.message}"
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    inputPass.isEnabled = true
                    inputAlias.isEnabled = true
                    inputKeyPass.isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true

                    if (success) {
                        this.keystorePass = kPass
                        this.keyAlias = alias
                        this.keyPass = keyP

                        signCheckBox.isEnabled = true
                        signCheckBox.isChecked = true
                        importKeyButton.text = "Signature Loaded ✅"
                        appendLog("🔑 Signature loaded & Verified (Alias: $alias)\n")

                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "Verification Failed: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) result = result?.substring(cut!! + 1)
        }
        return result
    }

    private fun copyUriToFile(uri: Uri, destFile: File) {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun appendLog(msg: String) {
        if (isLogPlaceholderVisible) {
            logTextView.text = ""
            logTextView.setTextColor(Color.BLACK)
            isLogPlaceholderVisible = false
        }
        logTextView.append(msg)
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
