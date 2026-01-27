package com.cawcafr.ameditor

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toolbar
import androidx.appcompat.app.AppCompatActivity
import com.cawcafr.ameditor.util.XmlSyntaxHighlighter

class XmlPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_preview)

        // Configuration de la Toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_close_clear_cancel) // Croix ou flèche

        val codeTextView = findViewById<TextView>(R.id.codeTextView)

        // Récupération du XML passé par l'Intent
        val xmlContent = intent.getStringExtra("XML_CONTENT") ?: "Error: No content found."

        // Coloration Syntaxique (Async pour fluidité)
        codeTextView.text = "Rendering Highlighting..."

        Thread {
            try {
                // Appel à notre utilitaire de coloration
                val highlightedText = XmlSyntaxHighlighter.highlight(xmlContent)

                runOnUiThread {
                    codeTextView.text = highlightedText
                }
            } catch (e: Exception) {
                runOnUiThread {
                    codeTextView.text = xmlContent // Fallback texte brut si erreur
                }
            }
        }.start()
    }

    // Gestion du bouton retour de la toolbar
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish() // Ferme l'activité
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}