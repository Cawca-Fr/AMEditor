package com.cawcafr.ameditor.util

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cawcafr.ameditor.R
import java.util.regex.Pattern

class CustomPatchActivity : AppCompatActivity() {

    private lateinit var xmlTextView: TextView
    private lateinit var btnDelete: com.google.android.material.button.MaterialButton
    private lateinit var btnDeactivate: com.google.android.material.button.MaterialButton

    private var currentMode = Mode.DELETE
    private var xmlContent = ""

    // Stockage des actions : Map<NomDuComposant, ModeChoisi>
    private val selectedActions = mutableMapOf<String, Mode>()

    // Pour mémoriser les positions des spans de background et les mettre à jour
    private val tagRanges = mutableListOf<TagRange>()

    enum class Mode { DELETE, DEACTIVATE, NONE }

    data class TagRange(
        val start: Int,
        val end: Int,
        val componentName: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_patch)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false) // On cache le titre pour centrer les boutons

        xmlTextView = findViewById(R.id.xmlTextView)
        btnDelete = findViewById(R.id.btnModeDelete)
        btnDeactivate = findViewById(R.id.btnModeDeactivate)

        // Récupération du XML
        xmlContent = intent.getStringExtra("XML_CONTENT") ?: ""

        setupButtons()
        renderXml()
    }

    private fun setupButtons() {
        updateButtonStyles()

        btnDelete.setOnClickListener {
            currentMode = if (currentMode == Mode.DELETE) Mode.NONE else Mode.DELETE
            updateButtonStyles()
        }

        btnDeactivate.setOnClickListener {
            currentMode = if (currentMode == Mode.DEACTIVATE) Mode.NONE else Mode.DEACTIVATE
            updateButtonStyles()
        }
    }

    private fun updateButtonStyles() {
        // Reset styles
        btnDelete.setBackgroundColor(Color.TRANSPARENT)
        btnDelete.setTextColor(Color.parseColor("#D32F2F"))
        btnDeactivate.setBackgroundColor(Color.TRANSPARENT)
        btnDeactivate.setTextColor(Color.parseColor("#FBC02D"))

        // Apply active style
        when (currentMode) {
            Mode.DELETE -> {
                btnDelete.setBackgroundColor(Color.parseColor("#FFCDD2")) // Rouge clair
            }
            Mode.DEACTIVATE -> {
                btnDeactivate.setBackgroundColor(Color.parseColor("#FFF9C4")) // Jaune clair
            }
            else -> {} // Rien
        }
    }

    private fun renderXml() {
        // 1. Coloration Syntaxique de base (Bleu/Vert/Violet)
        val spannable = XmlSyntaxHighlighter.highlight(xmlContent)

        // 2. Détection des blocs interactifs
        // On cherche les balises qui ont un attribut android:name="..."
        // Regex simplifiée : <(tag) ... android:name="([^"]+)" ... >
        // Note: Le regex XML parfait est impossible, mais celui-ci suffit pour 99% des manifestes formatés
        val pattern = Pattern.compile("(<([a-zA-Z0-9\\-:]+)[^>]*?android:name=\"([^\"]+)\"[^>]*>)")
        val matcher = pattern.matcher(xmlContent)

        tagRanges.clear()

        while (matcher.find()) {
            val fullTagStart = matcher.start(1)
            val fullTagEnd = matcher.end(1)
            val componentName = matcher.group(3) ?: continue

            // On enregistre la position
            tagRanges.add(TagRange(fullTagStart, fullTagEnd, componentName))

            // On ajoute un ClickableSpan sur TOUTE la balise
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onTagClicked(componentName)
                }
                // Enlever le soulignement par défaut des liens
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false
                }
            }
            spannable.setSpan(clickableSpan, fullTagStart, fullTagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        xmlTextView.setText(spannable, TextView.BufferType.SPANNABLE)
        xmlTextView.movementMethod = LinkMovementMethod.getInstance() // Important pour les clics
    }

    private fun onTagClicked(name: String) {
        if (currentMode == Mode.NONE) return

        // Logique de bascule (Toggle)
        if (selectedActions.containsKey(name)) {
            if (selectedActions[name] == currentMode) {
                // Si on clique avec le même mode -> On désélectionne
                selectedActions.remove(name)
            } else {
                // Si on clique avec un autre mode -> On change le mode
                selectedActions[name] = currentMode
            }
        } else {
            // Nouvelle sélection
            selectedActions[name] = currentMode
        }

        refreshHighlights()
    }

    private fun refreshHighlights() {
        val spannable = xmlTextView.text as? Spannable ?: return

        // 1. Nettoyer les anciens BackgroundSpans
        val existingSpans = spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java)
        for (span in existingSpans) {
            spannable.removeSpan(span)
        }

        // 2. Appliquer les nouvelles couleurs
        for (range in tagRanges) {
            val mode = selectedActions[range.componentName] ?: continue

            val color = when (mode) {
                Mode.DELETE -> Color.parseColor("#40D32F2F") // Rouge semi-transparent
                Mode.DEACTIVATE -> Color.parseColor("#40FBC02D") // Jaune semi-transparent
                else -> Color.TRANSPARENT
            }

            if (color != Color.TRANSPARENT) {
                spannable.setSpan(
                    BackgroundColorSpan(color),
                    range.start,
                    range.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        // Force refresh
        xmlTextView.invalidate()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_custom_patch, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_apply -> {
                applyPatch()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun applyPatch() {
        if (selectedActions.isEmpty()) {
            Toast.makeText(this, "No elements selected", Toast.LENGTH_SHORT).show()
            return
        }

        val deleteSet = mutableSetOf<String>()
        val disableSet = mutableSetOf<String>()

        selectedActions.forEach { (name, mode) ->
            when (mode) {
                Mode.DELETE -> deleteSet.add(name)
                Mode.DEACTIVATE -> disableSet.add(name)
                else -> {}
            }
        }

        val resultData = CustomPatchData(deleteSet, disableSet)

        val intent = Intent()
        intent.putExtra("PATCH_DATA", resultData)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}