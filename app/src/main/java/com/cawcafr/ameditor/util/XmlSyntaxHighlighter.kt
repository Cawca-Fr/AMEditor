package com.cawcafr.ameditor.util

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.regex.Pattern

object XmlSyntaxHighlighter {

    // Couleurs style "Android Studio / IntelliJ Light"
    const val COLOR_TAG        = 0xFF000080.toInt()  // Bleu foncé (<tag>)
    const val COLOR_ATTR_NAME  = 0xFF660E7A.toInt()  // Violet (android:name)
    const val COLOR_ATTR_VALUE = 0xFF008000.toInt()  // Vert ("valeur")
    const val COLOR_COMMENT    = 0xFF808080.toInt()  // Gris (<!-- comment -->)

    // Pre-compiled patterns (shared, thread-safe for read-only use)
    private val commentPattern = Pattern.compile("<!--[\\s\\S]*?-->")
    private val tagPattern     = Pattern.compile("</?[a-zA-Z0-9_\\-:]+(\\s|/?>)")
    private val attrPattern    = Pattern.compile("([a-zA-Z0-9_\\-:]+)=")
    private val valuePattern   = Pattern.compile("\"([^\"]*)\"")

    /**
     * Highlights the full XML string and returns a SpannableStringBuilder.
     * Suitable for small files where the full text is processed at once.
     */
    fun highlight(xml: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(xml)

        val commentMatcher = commentPattern.matcher(xml)
        while (commentMatcher.find()) {
            spannable.setSpan(ForegroundColorSpan(COLOR_COMMENT),
                commentMatcher.start(), commentMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val tagMatcher = tagPattern.matcher(xml)
        while (tagMatcher.find()) {
            spannable.setSpan(ForegroundColorSpan(COLOR_TAG),
                tagMatcher.start(), tagMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val attrMatcher = attrPattern.matcher(xml)
        while (attrMatcher.find()) {
            spannable.setSpan(ForegroundColorSpan(COLOR_ATTR_NAME),
                attrMatcher.start(1), attrMatcher.end(1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val valueMatcher = valuePattern.matcher(xml)
        while (valueMatcher.find()) {
            spannable.setSpan(ForegroundColorSpan(COLOR_ATTR_VALUE),
                valueMatcher.start(), valueMatcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return spannable
    }

    /**
     * Efficiently computes syntax-highlighting data for a sub-range of [xml] without
     * allocating a SpannableStringBuilder for the full document.
     *
     * Only the region [from, to) is scanned (with a small context window on each side
     * so that tokens that straddle a chunk boundary are still captured correctly).
     * Returns a list of (absoluteStart, absoluteEnd, color) triples whose start
     * positions fall inside [from, to).  The list is sorted by start position.
     *
     * This is the preferred method for large-file, chunk-based highlighting.
     *
     * @param xml    The full XML string (used as read-only source).
     * @param from   First character position to include in results (inclusive).
     * @param to     One-past-last character position to include (exclusive).
     */
    fun computeSpans(xml: String, from: Int = 0, to: Int = xml.length): List<Triple<Int, Int, Int>> {
        // Extend the scan window slightly so tokens at the chunk boundary are not missed.
        val ctxFrom = maxOf(0, from - CONTEXT_PADDING)
        val ctxTo   = minOf(xml.length, to + CONTEXT_PADDING)
        val sub     = xml.substring(ctxFrom, ctxTo)   // sub-string to scan

        val result = ArrayList<Triple<Int, Int, Int>>(512)

        fun addIfInRange(localStart: Int, localEnd: Int, color: Int) {
            val gs = ctxFrom + localStart   // global start
            val ge = ctxFrom + localEnd     // global end
            // Only emit spans whose start falls within [from, to)
            if (gs >= from && gs < to) result.add(Triple(gs, minOf(ge, to), color))
        }

        val cm = commentPattern.matcher(sub)
        while (cm.find()) addIfInRange(cm.start(), cm.end(), COLOR_COMMENT)

        val tm = tagPattern.matcher(sub)
        while (tm.find()) addIfInRange(tm.start(), tm.end(), COLOR_TAG)

        val am = attrPattern.matcher(sub)
        while (am.find()) addIfInRange(am.start(1), am.end(1), COLOR_ATTR_NAME)

        val vm = valuePattern.matcher(sub)
        while (vm.find()) addIfInRange(vm.start(), vm.end(), COLOR_ATTR_VALUE)

        result.sortWith(compareBy { it.first })
        return result
    }

    /** Extra characters scanned on each side of a chunk boundary. */
    private const val CONTEXT_PADDING = 512
}