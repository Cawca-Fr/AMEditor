package com.cawcafr.ameditor

import android.app.Activity
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
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cawcafr.ameditor.util.SignerUtils
import com.cawcafr.ameditor.util.CustomPatchData
import com.cawcafr.ameditor.util.CustomPatchActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore

class MainActivity : AppCompatActivity() {

    private lateinit var selectApkButton: Button
    private lateinit var processButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private lateinit var signCheckBox: CheckBox
    private lateinit var importKeyButton: Button
    private lateinit var infoButton: ImageButton

    private lateinit var previewButton: Button
    private lateinit var customPatchButton: Button

    private var apkFile: File? = null

    /** Cache du manifest XML extrait en arrière-plan après sélection APK. */
    private var cachedXmlContent: String? = null

    /** Dialog affiché pendant l'import manifest en arrière-plan. */
    private var importDialog: AlertDialog? = null
    private var lastRebuiltApk: File? = null
    private var originalFileName: String = "unknown.apk"

    // Variables pour le mode Keystore
    private var userKeystoreFile: File? = null
    private var keystorePass: String = ""
    private var keyAlias: String = ""
    private var keyPass: String = ""

    // Variables pour le mode PK8/PEM
    private var userPk8File: File? = null
    private var userPemFile: File? = null
    private var isUsingPk8Mode = false // Flag pour savoir quelle méthode utiliser

    private var isLogPlaceholderVisible = true

    // Lanceur pour sauvegarder l'APK final
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

                // ── FIX CACHE : supprime l'APK patché dès qu'il est exporté ──────
                // lastRebuiltApk est dans cacheDir — une fois copié vers Downloads,
                // il est inutile de le garder (~154 MB libérés)
                lastRebuiltApk?.delete()
            } catch (e: Exception) {
                appendLog("❌ Save Error: ${e.message}\n")
            }
        }
    }

    // Lanceur pour le CERTIFICAT (.pem) - lancé uniquement après avoir choisi un .pk8
    private val pickCertLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(uri)
            if (fileName == null || (!fileName.endsWith(".pem", true) && !fileName.endsWith(".x509.pem", true) && !fileName.endsWith(".crt", true))) {
                Toast.makeText(this, "Invalid Certificate. Please select a .pem or .x509.pem file.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            val cachePem = File(cacheDir, "user_cert.pem")
            copyUriToFile(uri, cachePem)
            userPemFile = cachePem

            // On a les deux fichiers (PK8 et PEM), on active le mode
            verifyPk8PemPair()
        }
    }

    // Lanceur Principal (Keystore OU PK8)
    private val pickKeystoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(uri)

            // --- CAS 1 : C'est une clé privée PK8 ---
            if (fileName != null && fileName.endsWith(".pk8", ignoreCase = true)) {
                val cachePk8 = File(cacheDir, "user_key.pk8")
                copyUriToFile(uri, cachePk8)
                userPk8File = cachePk8

                // On demande maintenant le certificat
                Toast.makeText(this, "Private Key loaded. Now select the Certificate (.pem)", Toast.LENGTH_LONG).show()
                pickCertLauncher.launch("*/*")
                return@registerForActivityResult
            }

            // --- CAS 2 : C'est un Keystore classique (Uniquement PKCS12 sur Android) ---
            if (
                fileName == null ||
                !(fileName.endsWith(".p12", true) || fileName.endsWith(".pfx", true))
            ) {
                Toast.makeText(
                    this,
                    "Only PKCS12 keystores (.p12 / .pfx) OR private keys (.pk8) are supported",
                    Toast.LENGTH_LONG
                ).show()
                return@registerForActivityResult
            }

            // Traitement Keystore standard
            val cacheKeyFile = File(cacheDir, "user_keystore.p12")
            copyUriToFile(uri, cacheKeyFile)
            userKeystoreFile = cacheKeyFile

            // On désactive le mode PK8
            isUsingPk8Mode = false
            showKeystorePasswordDialog()
        }
    }

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        originalFileName = getFileName(uri) ?: "app.apk"
        val copiedFile   = File(cacheDir, "selected_internal.apk")

        // Étape 1 : copie rapide → retour immédiat dans l'app
        try {
            copyUriToFile(uri, copiedFile)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to copy APK: ${e.message}", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        apkFile        = copiedFile
        cachedXmlContent = null  // reset du cache pour le nouvel APK
        XmlContentHolder.clear()

        setApkDependentButtonsEnabled(true)
        appendLog("📦 APK Selected: $originalFileName\n")

        // Étape 2 : extraction manifest en arrière-plan avec dialog
        startManifestImport()
    }

    private fun startManifestImport() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_importing, null)
        importDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .also { it.show() }

        Thread {
            try {
                val xml = ApkManifestPatcher(this).fetchManifestContent(apkFile!!)
                runOnUiThread {
                    cachedXmlContent = xml
                    XmlContentHolder.set(xml)                 // ← pré-chargé en avance
                    importDialog?.dismiss()
                    importDialog = null
                    appendLog("✅ Manifest ready.\n")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    importDialog?.dismiss()
                    importDialog = null
                    appendLog("⚠️ Manifest pre-load failed: ${e.message}\n")
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = true    // icônes NOIRES

        setContentView(R.layout.activity_main)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        selectApkButton = findViewById(R.id.selectApkButton)
        processButton = findViewById(R.id.processButton)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById(R.id.logScrollView)
        signCheckBox = findViewById(R.id.signCheckBox)
        importKeyButton = findViewById(R.id.importKeyButton)
        infoButton = findViewById(R.id.infoButton)
        previewButton = findViewById(R.id.previewButton)
        customPatchButton = findViewById(R.id.customPatchButton)

        setApkDependentButtonsEnabled(false)


        logTextView.text = "Output Logs"
        logTextView.setTextColor(Color.GRAY)

        infoButton.setOnClickListener {
            showInfoDialog()
        }

        importKeyButton.setOnClickListener {
            pickKeystoreLauncher.launch("*/*")
        }

        previewButton.setOnClickListener {
            if (apkFile == null) return@setOnClickListener
            showManifestPreview(apkFile!!)
        }

        customPatchButton.setOnClickListener {
            if (apkFile == null) return@setOnClickListener

            // On doit d'abord extraire le XML pour l'envoyer à l'éditeur
            loadXmlForCustomEditor()
        }

        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        processButton.setOnClickListener {
            if (apkFile == null) return@setOnClickListener

            // Vérification de sécurité
            val isKeystoreReady = (!isUsingPk8Mode && userKeystoreFile != null)
            val isPk8Ready = (isUsingPk8Mode && userPk8File != null && userPemFile != null)

            if (signCheckBox.isChecked && !isKeystoreReady && !isPk8Ready) {
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

                    handlePatchResult(result, unsignedApk, shouldSign)

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

    private fun setApkDependentButtonsEnabled(enabled: Boolean) {
        previewButton.isEnabled     = enabled
        customPatchButton.isEnabled = enabled
        processButton.isEnabled     = enabled
        // La checkbox garde son propre état (dépend de la clé, pas de l'APK)
        // mais on la remet à jour visuellement pour cohérence
        if (!enabled) signCheckBox.isEnabled = false
    }

    private fun loadXmlForCustomEditor() {
        val cached = cachedXmlContent
        if (cached != null) {
            XmlContentHolder.set(cached)                      // ← ICI, pas putExtra
            val intent = Intent(this, CustomPatchActivity::class.java)
            customPatchLauncher.launch(intent)
            return
        }

        val toast = Toast.makeText(this, "Reading manifest…", Toast.LENGTH_SHORT)
        toast.show()

        Thread {
            try {
                val xml = ApkManifestPatcher(this).fetchManifestContent(apkFile!!)
                cachedXmlContent = xml

                runOnUiThread {
                    toast.cancel()
                    XmlContentHolder.set(xml)                 // ← ICI, pas putExtra
                    val intent = Intent(this, CustomPatchActivity::class.java)
                    customPatchLauncher.launch(intent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast.cancel()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startCustomPatchProcess(data: CustomPatchData) {
        logTextView.text = ""
        logTextView.setTextColor(Color.BLACK)
        isLogPlaceholderVisible = false
        appendLog("⏳ Starting CUSTOM patch...\n")
        processButton.isEnabled = false
        
        // Check signatures settings...
        val shouldSign = signCheckBox.isChecked

        Thread {
            try {
                val patcher = ApkManifestPatcher(this)
                val unsignedApk = File(cacheDir, "unsigned_custom.apk")
                
                // Appel de la méthode spécifique
                val result = patcher.applyCustomPatch(apkFile!!, unsignedApk, data) { msg ->
                    runOnUiThread { appendLog("$msg\n") }
                }
                
                handlePatchResult(result, unsignedApk, shouldSign)
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("❌ Crash: ${e.message}\n")
                    processButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun handlePatchResult(result: PatchResult, unsignedApk: File, shouldSign: Boolean) {
        if (result is PatchResult.Success) {
            var finalApk = result.outputApk
            var outputPrefix = "PATCHED_"

            if (shouldSign) {
                val signedApk = File(cacheDir, "signed_mod.apk")
                try {
                    if (isUsingPk8Mode) {
                        // MODE 1 : PK8 + PEM
                        runOnUiThread { appendLog("🔏 Signing using PK8 Key + PEM Cert...\n") }
                        SignerUtils.signApkWithFilePair(
                            inputApk = unsignedApk,
                            outputApk = signedApk,
                            pk8File = userPk8File!!,
                            pemFile = userPemFile!!
                        )
                    } else {
                        // MODE 2 : KEYSTORE
                        runOnUiThread { appendLog("🔏 Signing using Keystore (PKCS12)...\n") }
                        SignerUtils.signApk(
                            inputApk = unsignedApk,
                            outputApk = signedApk,
                            keystoreFile = userKeystoreFile!!,
                            keystorePass = keystorePass,
                            keyAlias = keyAlias,
                            keyPass = keyPass
                        )
                    }

                    finalApk = signedApk
                    outputPrefix = "SIGNED_"
                    runOnUiThread { appendLog("✅ Signature applied successfully.\n") }

                } catch (e: Exception) {
                    runOnUiThread { appendLog("❌ Signature Failed: ${e.message}. Saving unsigned version.\n") }
                }
            } else {
                runOnUiThread { appendLog("ℹ️ Skipping signature (User choice).\n") }
            }

            lastRebuiltApk = finalApk
            runOnUiThread {
                appendLog("🎉 SUCCESS! Output ready.\n")
                saveApkLauncher.launch("$outputPrefix$originalFileName")
                processButton.isEnabled = true
            }
        } else if (result is PatchResult.Error) {
            runOnUiThread {
                appendLog("❌ FAILED: ${result.message}\n")
                processButton.isEnabled = true
            }
        }
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Supported Signature Formats")
            .setMessage("This app supports the following formats:\n\n" +
                    "✅ PKCS12 (.p12, .pfx)\n" +
                    "✅ Platform Keys (.pk8 + .pem)\n\n" +
                    "❌ JKS and BKS files are NOT supported.\n\n" +
                    "If you have an unsupported file (like .jks), please use 'MT Manager' or 'Ads regex++' to convert it to [.pk8 + .pem] or sign with.")
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun showManifestPreview(file: File) {
        val cached = cachedXmlContent
        if (cached != null) {
            XmlContentHolder.set(cached)                      // ← ICI, pas putExtra
            startActivity(Intent(this, XmlPreviewActivity::class.java))
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Reading Manifest…")
            .setMessage("Parsing AXML…")
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                val xmlContent = ApkManifestPatcher(this).fetchManifestContent(file)
                cachedXmlContent = xmlContent

                runOnUiThread {
                    progressDialog.dismiss()
                    XmlContentHolder.set(xmlContent)          // ← ICI, pas putExtra
                    startActivity(Intent(this, XmlPreviewActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }



    // --- Fonction de validation simple pour PK8/PEM ---
    private fun verifyPk8PemPair() {
        isUsingPk8Mode = true
        signCheckBox.isEnabled = true
        signCheckBox.isChecked = true
        importKeyButton.text = "PK8/PEM Loaded ✅"
        appendLog("🔑 Raw Key Pair loaded successfully.\n")
        Toast.makeText(this, "Key Pair Loaded!", Toast.LENGTH_SHORT).show()
    }

    private fun showKeystorePasswordDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dpToPx(24)
            setPadding(padding, dpToPx(10), padding, 0)
        }

        // Password
        val passLayout = TextInputLayout(context).apply {
            hint = "Password"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val inputPass = TextInputEditText(passLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        passLayout.addView(inputPass)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dpToPx(16)) }
        passLayout.layoutParams = params
        layout.addView(passLayout)

        // Alias
        val aliasLayout = TextInputLayout(context).apply {
            hint = "Alias"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val inputAlias = TextInputEditText(aliasLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        aliasLayout.addView(inputAlias)
        aliasLayout.layoutParams = params
        layout.addView(aliasLayout)

        // Alias password
        val keyPassLayout = TextInputLayout(context).apply {
            hint = "Alias Password"
            helperText = "Leave empty if same as keystore password"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val inputKeyPass = TextInputEditText(keyPassLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyPassLayout.addView(inputKeyPass)
        layout.addView(keyPassLayout)

        // Progress
        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val progressParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            setMargins(0, dpToPx(10), 0, 0)
        }
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
            val kPass = inputPass.text?.toString()?.trim() ?: ""
            var alias = inputAlias.text?.toString()?.trim() ?: ""
            var keyP = inputKeyPass.text?.toString()?.trim() ?: ""

            if (kPass.isEmpty()) {
                Toast.makeText(context, "Password is required", Toast.LENGTH_SHORT).show()
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
                    if (userKeystoreFile == null || !userKeystoreFile!!.exists()) {
                        errorMsg = "Keystore file missing."
                    } else {
                        val ks = try {
                            SignerUtils.loadKeyStore(userKeystoreFile!!, kPass)
                        } catch (e: Exception) {
                            throw RuntimeException("Keystore load failed: ${e.message}", e)
                        }

                        val aliases = mutableListOf<String>()
                        val en = ks.aliases()
                        while (en.hasMoreElements()) aliases.add(en.nextElement())

                        if (alias.isEmpty()) {
                            alias = if (aliases.size == 1) aliases[0] else ""
                        }

                        if (alias.isEmpty()) {
                            errorMsg = if (aliases.isEmpty()) {
                                "No aliases found in keystore."
                            } else {
                                "Please choose an alias. Available aliases: ${aliases.joinToString(", ")}"
                            }
                            throw RuntimeException(errorMsg)
                        }

                        if (!ks.containsAlias(alias)) {
                            errorMsg = "Alias '$alias' not found. Available: ${aliases.joinToString(", ")}"
                            throw RuntimeException(errorMsg)
                        }

                        try {
                            val entry = ks.getEntry(alias, KeyStore.PasswordProtection(keyP.toCharArray()))
                                    as? KeyStore.PrivateKeyEntry
                            if (entry == null) {
                                errorMsg = "Cannot access key: wrong alias or wrong key password."
                                throw RuntimeException(errorMsg)
                            } else {
                                success = true
                            }
                        } catch (e: Exception) {
                            throw RuntimeException("Key access failed: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Unknown error"
                    runOnUiThread { appendLog("ERROR while verifying keystore: ${e::class.java.name}: ${e.message}\n") }
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

    private val customPatchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data?.getSerializableExtra("PATCH_DATA") as? CustomPatchData
            if (data != null) {
                // On lance le processus avec les données reçues
                startCustomPatchProcess(data)
            }
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