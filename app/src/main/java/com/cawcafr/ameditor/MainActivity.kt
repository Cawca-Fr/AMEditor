package com.cawcafr.ameditor

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri
import androidx.core.graphics.toColorInt

class MainActivity : AppCompatActivity() {

    // ── Vues ──────────────────────────────────────────────────────────────────
    private lateinit var selectApkButton: Button
    private lateinit var processButton: Button
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var signCheckBox: CheckBox
    private lateinit var importKeyButton: Button
    private lateinit var infoButton: ImageButton
    private lateinit var previewButton: Button
    private lateinit var customPatchButton: Button

    // Nouvelles vues
    private lateinit var apkInfoBar: View
    private lateinit var apkInfoDivider: View
    private lateinit var apkSizeText: TextView
    private lateinit var apkOutputSizeLabel: TextView
    private lateinit var apkOutputSizeText: TextView
    private lateinit var manifestSizeText: TextView
    private lateinit var manifestOutputSizeLabel: TextView
    private lateinit var manifestOutputSizeText: TextView
    private lateinit var signatureBadge: TextView
    private lateinit var progressContainer: View
    private lateinit var processProgressBar: ProgressBar
    private lateinit var progressStepText: TextView
    private lateinit var logLineCount: TextView
    private lateinit var logCopyButton: ImageButton
    private lateinit var logClearButton: ImageButton

    // ── Données ───────────────────────────────────────────────────────────────
    private var apkFile: File? = null
    private var cachedXmlContent: String? = null
    /** Taille binaire réelle du manifest AXML dans le ZIP (pas xmlString.length). */
    private var cachedManifestBinarySize: Long = -1L
    private var importDialog: AlertDialog? = null
    private var lastRebuiltApk: File? = null
    private var originalFileName: String = "unknown.apk"

    private var userKeystoreFile: File? = null
    private var keystorePass: String = ""
    private var keyAlias: String = ""
    private var keyPass: String = ""
    private var userPk8File: File? = null
    private var userPemFile: File? = null
    private var isUsingPk8Mode = false

    // ── Logs ──────────────────────────────────────────────────────────────────
    /** Vrai tant que le placeholder gris "Output Logs" est affiché. */
    private var isLogPlaceholderVisible = true
    private var logLineCounter = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ══════════════════════════════════════════════════════════════════════════
    // Lanceurs
    // ══════════════════════════════════════════════════════════════════════════

    private val saveApkLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri: Uri? ->
        if (uri != null && lastRebuiltApk != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(lastRebuiltApk!!).use { it.copyTo(out) }
                }
                appendLog("✅ ${getString(R.string.log_apk_saved_success).trim()}")
                Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                lastRebuiltApk?.delete()
            } catch (e: Exception) {
                appendLog("❌ ${getString(R.string.log_error_saving_apk, e.message)}")
            }
        }
    }

    private val pickCertLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(uri)
            if (fileName == null || (!fileName.endsWith(".pem", true) && !fileName.endsWith(".x509.pem", true) && !fileName.endsWith(".crt", true))) {
                Toast.makeText(this, getString(R.string.error_invalid_cert), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val cachePem = File(cacheDir, "user_cert.pem")
            copyUriToFile(uri, cachePem)
            userPemFile = cachePem
            verifyPk8PemPair()
        }
    }

    private val pickKeystoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(uri)
            if (fileName != null && fileName.endsWith(".pk8", ignoreCase = true)) {
                val cachePk8 = File(cacheDir, "user_key.pk8")
                copyUriToFile(uri, cachePk8)
                userPk8File = cachePk8
                Toast.makeText(this, getString(R.string.toast_pk8_loaded), Toast.LENGTH_LONG).show()
                pickCertLauncher.launch("*/*")
                return@registerForActivityResult
            }
            if (fileName == null || !(fileName.endsWith(".p12", true) || fileName.endsWith(".pfx", true))) {
                Toast.makeText(this, getString(R.string.error_unsupported_keystore), Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val cacheKeyFile = File(cacheDir, "user_keystore.p12")
            copyUriToFile(uri, cacheKeyFile)
            userKeystoreFile = cacheKeyFile
            isUsingPk8Mode = false
            showKeystorePasswordDialog()
        }
    }

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        originalFileName = getFileName(uri) ?: "app.apk"
        val copiedFile   = File(cacheDir, "selected_internal.apk")

        try {
            copyUriToFile(uri, copiedFile)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_copy_apk_failed, e.message), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }

        apkFile               = copiedFile
        cachedXmlContent      = null
        cachedManifestBinarySize = -1L
        XmlContentHolder.clear()

        // ── Affiche le bandeau taille APK ──────────────────────────────────
        showApkSizeBar(copiedFile.length())

        setApkDependentButtonsEnabled(true)

        clearLogs()
        appendLog(getString(R.string.log_apk_selected, originalFileName))
        appendLog(getString(R.string.log_apk_size, formatFileSize(copiedFile.length())))

        startManifestImport()
    }

    private val customPatchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data?.getSerializableExtra("PATCH_DATA") as? CustomPatchData
            if (data != null) startCustomPatchProcess(data)
        }
    }

    // onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        // Vues existantes
        selectApkButton  = findViewById(R.id.selectApkButton)
        processButton    = findViewById(R.id.processButton)
        logTextView      = findViewById(R.id.logTextView)
        logScrollView    = findViewById(R.id.logScrollView)
        signCheckBox     = findViewById(R.id.signCheckBox)
        importKeyButton  = findViewById(R.id.importKeyButton)
        infoButton       = findViewById(R.id.infoButton)
        previewButton    = findViewById(R.id.previewButton)
        customPatchButton = findViewById(R.id.customPatchButton)

        // Nouvelles vues
        apkInfoBar        = findViewById(R.id.apkInfoBar)
        apkInfoDivider    = findViewById(R.id.apkInfoDivider)
        apkSizeText       = findViewById(R.id.apkSizeText)
        apkOutputSizeLabel = findViewById(R.id.apkOutputSizeLabel)
        apkOutputSizeText = findViewById(R.id.apkOutputSizeText)
        manifestSizeText       = findViewById(R.id.manifestSizeText)
        manifestOutputSizeLabel = findViewById(R.id.manifestOutputSizeLabel)
        manifestOutputSizeText  = findViewById(R.id.manifestOutputSizeText)
        signatureBadge    = findViewById(R.id.signatureBadge)
        progressContainer = findViewById(R.id.progressContainer)
        processProgressBar = findViewById(R.id.processProgressBar)
        progressStepText  = findViewById(R.id.progressStepText)
        logLineCount      = findViewById(R.id.logLineCount)
        logCopyButton     = findViewById(R.id.logCopyButton)
        logClearButton    = findViewById(R.id.logClearButton)

        setApkDependentButtonsEnabled(false)
        showLogPlaceholder()

        // ── Listeners ──────────────────────────────────────────────────────
        selectApkButton.setOnClickListener {
            pickApkLauncher.launch("application/vnd.android.package-archive")
        }

        previewButton.setOnClickListener {
            if (apkFile != null) showManifestPreview(apkFile!!)
        }

        customPatchButton.setOnClickListener {
            if (apkFile != null) loadXmlForCustomEditor()
        }

        importKeyButton.setOnClickListener {
            pickKeystoreLauncher.launch("*/*")
        }

        infoButton.setOnClickListener { showInfoDialog() }

        logCopyButton.setOnClickListener { copyLogsToClipboard() }
        logClearButton.setOnClickListener { clearLogs() }

        processButton.setOnClickListener {
            if (apkFile == null) return@setOnClickListener

            val isKeystoreReady = (!isUsingPk8Mode && userKeystoreFile != null)
            val isPk8Ready      = (isUsingPk8Mode && userPk8File != null && userPemFile != null)

            if (signCheckBox.isChecked && !isKeystoreReady && !isPk8Ready) {
                Toast.makeText(this, getString(R.string.error_import_signature), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            clearLogs()
            appendLog(getString(R.string.log_starting_process, originalFileName))
            setProcessing(true, getString(R.string.log_extracting_manifest))

            val shouldSign = signCheckBox.isChecked

            Thread {
                try {
                    val patcher    = ApkManifestPatcher(this)
                    val unsignedApk = File(cacheDir, "unsigned_mod.apk")

                    val result = patcher.patchApkManifest(apkFile!!, unsignedApk) { msg ->
                        runOnUiThread {
                            appendLog(msg.trim())
                            // Met à jour le label de progression selon le step
                            if (msg.contains("Step")) updateProgressLabel(msg.trim())
                        }
                    }
                    handlePatchResult(result, unsignedApk, shouldSign)
                } catch (e: Exception) {
                    runOnUiThread {
                        appendLog("❌ ${getString(R.string.log_crash, e.message)}")
                        setProcessing(false)
                    }
                    e.printStackTrace()
                }
            }.start()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // APK size bar
    // ══════════════════════════════════════════════════════════════════════════

    private fun showApkSizeBar(sizeBytes: Long) {
        apkSizeText.text = formatFileSize(sizeBytes)
        // Réinitialise les tailles output (nouveau APK sélectionné)
        apkOutputSizeLabel.visibility      = View.GONE
        apkOutputSizeText.visibility       = View.GONE
        manifestOutputSizeLabel.visibility = View.GONE
        manifestOutputSizeText.visibility  = View.GONE
        // Réinitialise aussi la taille manifest input (sera mise à jour après import)
        manifestSizeText.text = "…"
        apkInfoBar.visibility     = View.VISIBLE
        apkInfoDivider.visibility = View.VISIBLE
    }

    /** Affiche la taille de l'APK output après rebuild. */
    private fun updateApkOutputSize(outputFile: File) {
        if (!outputFile.exists()) return
        apkOutputSizeText.text        = formatFileSize(outputFile.length())
        apkOutputSizeLabel.visibility = View.VISIBLE
        apkOutputSizeText.visibility  = View.VISIBLE
    }

    /** Affiche la taille du manifest AXML patché après rebuild. */
    private fun updateManifestOutputSize(sizeBytes: Long) {
        manifestOutputSizeText.text        = formatFileSize(sizeBytes)
        manifestOutputSizeLabel.visibility = View.VISIBLE
        manifestOutputSizeText.visibility  = View.VISIBLE
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Signature badge
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Met à jour le badge sous la row signature :
     *   state = "none"     → gris  "🔒 No key imported"
     *   state = "keystore" → vert  "🔑 PKCS12 ready · $alias"
     *   state = "pk8"      → vert  "🔑 PK8 / PEM ready"
     */
    private fun updateSignatureBadge(state: String, alias: String = "") {
        when (state) {
            "keystore" -> {
                val label = if (alias.isNotEmpty()) getString(R.string.signature_ready_pkcs12, alias) else getString(R.string.signature_ready_pkcs12_no_alias)
                signatureBadge.text      = label
                signatureBadge.setTextColor("#16A34A".toColorInt())
            }
            "pk8" -> {
                signatureBadge.text = getString(R.string.signature_ready_pk8)
                signatureBadge.setTextColor("#16A34A".toColorInt())
            }
            else -> {
                signatureBadge.text = getString(R.string.signature_no_key)
                signatureBadge.setTextColor("#9CA3AF".toColorInt())
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Progress bar
    // ══════════════════════════════════════════════════════════════════════════

    private fun setProcessing(active: Boolean, label: String = "") {
        progressContainer.visibility = if (active) View.VISIBLE else View.GONE
        if (active && label.isNotEmpty()) progressStepText.text = label
        processButton.isEnabled    = !active
        selectApkButton.isEnabled  = !active
    }

    private fun updateProgressLabel(label: String) {
        progressStepText.text = label
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Logs colorés
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ajoute une ligne dans les logs avec :
     *  • timestamp [HH:mm:ss] en gris
     *  • texte coloré selon le type :
     *      ✅  → vert
     *      ❌  → rouge
     *      ⏳ / ⚙️  → orange
     *      ⚠️  → jaune-orangé
     *      📦 / 💾 / 🔑 / 🔏 / ℹ️  → bleu
     *      🎉  → vert foncé + gras
     *      autres → gris foncé
     */
    fun appendLog(message: String) {
        if (isLogPlaceholderVisible) {
            logTextView.text = ""
            isLogPlaceholderVisible = false
        }

        val ssb  = SpannableStringBuilder()
        val text = logTextView.text as? SpannableStringBuilder ?: SpannableStringBuilder(logTextView.text)

        // Timestamp gris
        val timestamp = "[${timeFormat.format(Date())}] "
        val tsStart   = text.length
        text.append(timestamp)
        text.setSpan(ForegroundColorSpan("#9CA3AF".toColorInt()), tsStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Couleur selon le préfixe emoji
        val color = when {
            message.startsWith("✅") || message.startsWith("🎉") -> "#16A34A".toColorInt() // vert
            message.startsWith("❌")                             -> "#DC2626".toColorInt() // rouge
            message.startsWith("⏳") || message.startsWith("⚙️") -> "#EA580C".toColorInt() // orange
            message.startsWith("⚠️")                             -> "#D97706".toColorInt() // ambre
            message.startsWith("📦") || message.startsWith("💾") ||
                    message.startsWith("🔑") || message.startsWith("🔏") ||
                    message.startsWith("ℹ️")                             -> "#2563EB".toColorInt() // bleu
            else                                                  -> "#374151".toColorInt() // gris foncé
        }

        val msgStart = text.length
        text.append(message)
        text.setSpan(ForegroundColorSpan(color), msgStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Gras pour les lignes importantes
        if (message.startsWith("✅") || message.startsWith("❌") || message.startsWith("🎉")) {
            text.setSpan(StyleSpan(Typeface.BOLD), msgStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        text.append("\n")

        logTextView.setText(text, TextView.BufferType.SPANNABLE)
        logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }

        // Met à jour le compteur de lignes + montre les boutons
        logLineCounter++
        updateLogHeader()
    }

    private fun showLogPlaceholder() {
        logTextView.text = getString(R.string.log_placeholder)
        logTextView.setTextColor("#9CA3AF".toColorInt())
        isLogPlaceholderVisible = true
        logLineCounter = 0
        updateLogHeader()
    }

    private fun clearLogs() {
        logTextView.text = ""
        isLogPlaceholderVisible = false
        logLineCounter = 0
        updateLogHeader()
    }

    private fun updateLogHeader() {
        val hasLogs = logLineCounter > 0
        logLineCount.visibility  = if (hasLogs) View.VISIBLE else View.GONE
        logCopyButton.visibility = if (hasLogs) View.VISIBLE else View.GONE
        logClearButton.visibility = if (hasLogs) View.VISIBLE else View.GONE
        if (hasLogs) logLineCount.text = getString(R.string.log_line_count, logLineCounter)
    }

    private fun copyLogsToClipboard() {
        val text = logTextView.text.toString()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("AMEditor logs", text))
        Toast.makeText(this, getString(R.string.toast_logs_copied), Toast.LENGTH_SHORT).show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Manifest import
    // ══════════════════════════════════════════════════════════════════════════

    private fun startManifestImport() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_importing, null)
        importDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            .also { it.show() }

        Thread {
            try {
                val (xml, binarySize) = ApkManifestPatcher(this).fetchManifestContent(apkFile!!)
                runOnUiThread {
                    cachedXmlContent         = xml
                    cachedManifestBinarySize = binarySize
                    XmlContentHolder.set(xml)
                    importDialog?.dismiss(); importDialog = null
                    // Affiche la taille binaire réelle (pas xml.length qui est ~14KB de plus)
                    manifestSizeText.text = formatFileSize(binarySize)
                    appendLog(getString(R.string.log_manifest_ready, formatFileSize(binarySize)))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    importDialog?.dismiss(); importDialog = null
                    appendLog(getString(R.string.log_manifest_preload_failed, e.message))
                }
            }
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Patch process
    // ══════════════════════════════════════════════════════════════════════════

    private fun startCustomPatchProcess(data: CustomPatchData) {
        clearLogs()
        appendLog(getString(R.string.log_starting_custom_patch))
        setProcessing(true, getString(R.string.log_applying_custom_rules))

        val shouldSign = signCheckBox.isChecked

        Thread {
            try {
                val patcher     = ApkManifestPatcher(this)
                val unsignedApk = File(cacheDir, "unsigned_custom.apk")

                val result = patcher.applyCustomPatch(apkFile!!, unsignedApk, data) { msg ->
                    runOnUiThread {
                        appendLog(msg.trim())
                        if (msg.contains("Step")) updateProgressLabel(msg.trim())
                    }
                }
                handlePatchResult(result, unsignedApk, shouldSign)
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("❌ ${getString(R.string.log_crash, e.message)}")
                    setProcessing(false)
                }
            }
        }.start()
    }

    private fun handlePatchResult(result: PatchResult, unsignedApk: File, shouldSign: Boolean) {
        if (result is PatchResult.Success) {
            var finalApk     = result.outputApk
            var outputPrefix = "PATCHED_"

            if (shouldSign) {
                val signedApk = File(cacheDir, "signed_mod.apk")
                try {
                    runOnUiThread { updateProgressLabel(getString(R.string.log_signing_apk)) }
                    if (isUsingPk8Mode) {
                        runOnUiThread { appendLog(getString(R.string.log_signing_pk8_pem)) }
                        SignerUtils.signApkWithFilePair(unsignedApk, signedApk, userPk8File!!, userPemFile!!)
                    } else {
                        runOnUiThread { appendLog(getString(R.string.log_signing_pkcs12)) }
                        SignerUtils.signApk(unsignedApk, signedApk, userKeystoreFile!!, keystorePass, keyAlias, keyPass)
                    }
                    finalApk     = signedApk
                    outputPrefix = "SIGNED_"
                    runOnUiThread { appendLog(getString(R.string.log_signature_applied)) }
                } catch (e: Exception) {
                    runOnUiThread { appendLog(getString(R.string.log_signature_failed, e.message)) }
                }
            } else {
                runOnUiThread { appendLog(getString(R.string.log_signature_skipped)) }
            }

            lastRebuiltApk = finalApk

            // Affiche taille APK output + taille manifest output dans le bandeau
            runOnUiThread {
                updateApkOutputSize(finalApk)
                // Taille du manifest patché depuis PatchStats (binaire AXML encodé)
                val manifestOut = result.stats.manifestOutputSize
                if (manifestOut > 0L) updateManifestOutputSize(manifestOut)
            }

            runOnUiThread {
                appendLog(getString(R.string.log_success_output, formatFileSize(finalApk.length())))
                setProcessing(false)
                saveApkLauncher.launch("$outputPrefix$originalFileName")
            }

        } else if (result is PatchResult.Error) {
            runOnUiThread {
                appendLog("❌ ${getString(R.string.log_failure, result.message)}")
                setProcessing(false)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Preview / Custom editor
    // ══════════════════════════════════════════════════════════════════════════

    private fun loadXmlForCustomEditor() {
        val cached = cachedXmlContent
        if (cached != null) {
            XmlContentHolder.set(cached)
            customPatchLauncher.launch(Intent(this, CustomPatchActivity::class.java))
            return
        }
        val toast = Toast.makeText(this, getString(R.string.log_reading_manifest), Toast.LENGTH_SHORT)
        toast.show()
        Thread {
            try {
                val (xml, binarySize) = ApkManifestPatcher(this).fetchManifestContent(apkFile!!)
                cachedXmlContent         = xml
                cachedManifestBinarySize = binarySize
                runOnUiThread {
                    toast.cancel()
                    manifestSizeText.text = formatFileSize(binarySize)
                    XmlContentHolder.set(xml)
                    customPatchLauncher.launch(Intent(this, CustomPatchActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast.cancel()
                    Toast.makeText(this, getString(R.string.generic_error_exception, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showManifestPreview(file: File) {
        val cached = cachedXmlContent
        if (cached != null) {
            XmlContentHolder.set(cached)
            startActivity(Intent(this, XmlPreviewActivity::class.java))
            return
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_reading_manifest_title)).setMessage(getString(R.string.dialog_parsing_axml_msg)).setCancelable(false).create()
        progressDialog.show()
        Thread {
            try {
                val (xmlContent, binarySize) = ApkManifestPatcher(this).fetchManifestContent(file)
                cachedXmlContent         = xmlContent
                cachedManifestBinarySize = binarySize
                runOnUiThread {
                    progressDialog.dismiss()
                    manifestSizeText.text = formatFileSize(binarySize)
                    XmlContentHolder.set(xmlContent)
                    startActivity(Intent(this, XmlPreviewActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, getString(R.string.generic_error_exception, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Signature
    // ══════════════════════════════════════════════════════════════════════════

    private fun verifyPk8PemPair() {
        isUsingPk8Mode = true
        signCheckBox.isEnabled = true
        signCheckBox.isChecked = true
        importKeyButton.text = getString(R.string.button_pk8_pem_ready)
        updateSignatureBadge("pk8")
        appendLog(getString(R.string.log_raw_key_pair_loaded))
        Toast.makeText(this, getString(R.string.toast_key_pair_loaded), Toast.LENGTH_SHORT).show()
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_supported_formats_title))
            .setMessage(getString(R.string.dialog_supported_formats_msg))
            .setPositiveButton(getString(R.string.btn_ok), null).show()
    }

    private fun showKeystorePasswordDialog() {
        val context = this
        val layout  = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = dpToPx(24); setPadding(p, dpToPx(10), p, 0)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.setMargins(0, 0, 0, dpToPx(16)) }

        fun field(hint: String, password: Boolean): Pair<TextInputLayout, TextInputEditText> {
            val til = TextInputLayout(context).apply {
                this.hint = hint; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; layoutParams = lp
            }
            val et = TextInputEditText(til.context).apply {
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            til.addView(et); layout.addView(til); return til to et
        }

        val (_, inputPass)    = field(getString(R.string.label_password), true)
        val (_, inputAlias)   = field(getString(R.string.label_alias), false)
        val (tilKeyPass, inputKeyPass) = field(getString(R.string.label_alias_password), true)
        tilKeyPass.helperText = getString(R.string.helper_same_password)

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.gravity = Gravity.CENTER; it.setMargins(0, dpToPx(10), 0, 0) }
        }
        layout.addView(progressBar)

        val dialog = AlertDialog.Builder(context)
            .setTitle(getString(R.string.dialog_import_signature_title)).setView(layout)
            .setPositiveButton(getString(R.string.btn_ok), null).setNegativeButton(getString(R.string.btn_cancel), null).create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val kPass = inputPass.text?.toString()?.trim() ?: ""
            var alias = inputAlias.text?.toString()?.trim() ?: ""
            var keyP  = inputKeyPass.text?.toString()?.trim() ?: ""
            if (kPass.isEmpty()) { Toast.makeText(context, getString(R.string.error_password_required), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (keyP.isEmpty()) keyP = kPass

            progressBar.visibility = View.VISIBLE
            listOf(inputPass, inputAlias, inputKeyPass).forEach { it.isEnabled = false }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true

            Thread {
                var success = false; var errorMsg = ""
                try {
                    val ks = SignerUtils.loadKeyStore(userKeystoreFile!!, kPass)
                    val aliases = mutableListOf<String>().also { list -> val en = ks.aliases(); while (en.hasMoreElements()) list.add(en.nextElement()) }
                    if (alias.isEmpty()) alias = if (aliases.size == 1) aliases[0] else ""
                    if (alias.isEmpty()) throw RuntimeException(if (aliases.isEmpty()) getString(R.string.error_no_aliases) else getString(R.string.error_choose_alias, aliases.joinToString(", ")))
                    if (!ks.containsAlias(alias)) throw RuntimeException(getString(R.string.error_alias_not_found, alias, aliases.joinToString(", ")))
                    val entry = ks.getEntry(alias, KeyStore.PasswordProtection(keyP.toCharArray())) as? KeyStore.PrivateKeyEntry
                        ?: throw RuntimeException(getString(R.string.error_cannot_access_key))
                    success = true
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Unknown error"
                    runOnUiThread { appendLog(getString(R.string.error_keystore_load, errorMsg)) }
                }
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    listOf(inputPass, inputAlias, inputKeyPass).forEach { it.isEnabled = true }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    if (success) {
                        this.keystorePass = kPass; this.keyAlias = alias; this.keyPass = keyP
                        signCheckBox.isEnabled = true; signCheckBox.isChecked = true
                        importKeyButton.text = getString(R.string.button_signature_ready)
                        updateSignatureBadge("keystore", alias)
                        appendLog(getString(R.string.log_signature_loaded, alias))
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, getString(R.string.error_failed_general, errorMsg), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Utilitaires
    // ══════════════════════════════════════════════════════════════════════════

    private fun setApkDependentButtonsEnabled(enabled: Boolean) {
        previewButton.isEnabled      = enabled
        customPatchButton.isEnabled  = enabled
        processButton.isEnabled      = enabled
        if (!enabled) signCheckBox.isEnabled = false
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> getString(R.string.format_mb, bytes / (1024.0 * 1024.0))
        bytes >= 1024        -> getString(R.string.format_kb, bytes / 1024.0)
        else                 -> getString(R.string.format_b, bytes)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) result = it.getString(idx)
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
            FileOutputStream(destFile).use { input.copyTo(it) }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Menu 3 points
    // ══════════════════════════════════════════════════════════════════════════

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_language  -> { showLanguageDialog();  true }
            R.id.menu_changelog -> { showChangelogDialog(); true }
            R.id.menu_faq       -> { showFaqDialog();       true }
            R.id.menu_contact   -> { showContactDialog();   true }
            R.id.menu_ressources -> { showRessourcesDialog(); true }
            R.id.menu_credits   -> { showCreditsDialog();   true }
            else                -> super.onOptionsItemSelected(item)
        }
    }

    // ── Language ──────────────────────────────────────────────────────────────

    private fun showLanguageDialog() {
        val languages = arrayOf("English", "Français")
        val codes     = arrayOf("en",       "fr")

        // Détecte la langue actuelle pour pré-cocher
        val currentLang = resources.configuration.locales[0].language
        val checkedItem = codes.indexOfFirst { it == currentLang }.takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_language))
            .setSingleChoiceItems(languages, checkedItem) { dialog, which ->
                val selected = codes[which]
                if (selected != currentLang) applyLanguage(selected)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun applyLanguage(langCode: String) {
        val locale = java.util.Locale(langCode)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(resources.configuration)
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        // Redémarre l'activité pour appliquer
        val intent = intent
        finish()
        startActivity(intent)
    }

    // ── Changelog ─────────────────────────────────────────────────────────────

    private fun showChangelogDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_changelog_title))
            .setMessage(getString(R.string.dialog_changelog_msg))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

    // ── FAQ ───────────────────────────────────────────────────────────────────

    private fun showFaqDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_faq_title))
            .setMessage(getString(R.string.dialog_faq_msg))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

    // ── Contact ───────────────────────────────────────────────────────────────

    private fun showContactDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_contact_title))
            .setMessage(getString(R.string.contact_telegram))
            .setPositiveButton(getString(R.string.btn_open_telegram)) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/manifestpatcher"))
                runCatching { startActivity(intent) }
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    // ── Ressources ────────────────────────────────────────────────────────────

    private fun showRessourcesDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_ressources_title))
            .setMessage(getString(R.string.dialog_ressources_msg))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }

    // ── Credits ───────────────────────────────────────────────────────────────

    private fun showCreditsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_credits_title))
            .setMessage(getString(R.string.dialog_credits_msg))
            .setPositiveButton(getString(R.string.btn_ok), null)
            .show()
    }
}
