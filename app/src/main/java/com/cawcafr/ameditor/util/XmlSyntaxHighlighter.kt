package com.cawcafr.ameditor.util

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object XmlSyntaxHighlighter {

    // Couleurs style "Android Studio / IntelliJ Light"
    private const val COLOR_TAG = 0xFF000080.toInt()      // Bleu foncé (<tag>)
    private const val COLOR_ATTR_NAME = 0xFF660E7A.toInt() // Violet (android:name)
    private const val COLOR_ATTR_VALUE = 0xFF008000.toInt() // Vert ("valeur")
    private const val COLOR_COMMENT = 0xFF808080.toInt()   // Gris (<!-- comment -->)

    fun highlight(xml: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(xml)

        // 1. Coloration des Commentaires
        val commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
        val commentMatcher = commentPattern.matcher(xml)
        while (commentMatcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(COLOR_COMMENT),
                commentMatcher.start(),
                commentMatcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 2. Coloration des Balises <...> (Excluant le contenu des commentaires déjà traité)
        // On cherche les chevrons < et > et le nom de la balise
        val tagPattern = Pattern.compile("</?[a-zA-Z0-9_\\-:]+(\\s|/?>)")
        val tagMatcher = tagPattern.matcher(xml)
        while (tagMatcher.find()) {
            // On vérifie qu'on n'est pas dans un commentaire (simplifié)
            spannable.setSpan(
                ForegroundColorSpan(COLOR_TAG),
                tagMatcher.start(),
                tagMatcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 3. Coloration des Attributs (nom="...")
        val attrPattern = Pattern.compile("([a-zA-Z0-9_\\-:]+)=")
        val attrMatcher = attrPattern.matcher(xml)
        while (attrMatcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(COLOR_ATTR_NAME),
                attrMatcher.start(1),
                attrMatcher.end(1),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // 4. Coloration des Valeurs ("...")
        val valuePattern = Pattern.compile("\"([^\"]*)\"")
        val valueMatcher = valuePattern.matcher(xml)
        while (valueMatcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(COLOR_ATTR_VALUE),
                valueMatcher.start(),
                valueMatcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }
}