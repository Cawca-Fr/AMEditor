package com.cawcafr.ameditor

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.graphics.toColorInt
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.cawcafr.ameditor.util.SpanRecord
import com.cawcafr.ameditor.util.ViewportSpanApplier
import com.cawcafr.ameditor.util.XmlSyntaxHighlighter
import org.lsposed.lsparanoid.Obfuscate
import androidx.core.graphics.drawable.toDrawable

@Obfuscate
class XmlPreviewActivity : AppCompatActivity() {

    private lateinit var codeTextView: TextView
    private lateinit var xmlScrollView: NestedScrollView
    private lateinit var xmlHorizontalScroll: android.widget.HorizontalScrollView
    private lateinit var scrollbarThumb: View
    private lateinit var searchBar: View
    private lateinit var searchDivider: View
    private lateinit var etSearch: AppCompatEditText
    private lateinit var tvSearchCount: TextView
    private lateinit var btnSearchPrev: ImageButton
    private lateinit var btnSearchNext: ImageButton
    private lateinit var btnSearchClose: ImageButton

    private var xmlContent = ""

    // ── Viewport colorization ─────────────────────────────────────────────────
    //
    // allSpanInfos  – all computed spans, sorted by start (background produces
    //                 chunks in order, so each addAll() keeps the list sorted).
    // appliedSpans  – index-in-allSpanInfos → span object currently set on the
    //                 Spannable.  Lets us evict and re-apply efficiently.
    // spanApplier   – micro-batch applier: 60 spans per Handler.post() frame so
    //                 the main thread is never blocked for more than ~1 ms at a
    //                 time → scrolling remains fluid even after stopping.

    @Volatile private var allSpanInfos: List<SpanRecord> = emptyList()
    private val appliedSpans = mutableMapOf<Int, Any>()
    private val spanApplier  = ViewportSpanApplier()

    private val PLAIN_THRESHOLD = 300_000
    private val HIGHLIGHT_CHUNK = 80_000
    private val VIEWPORT_BUFFER = 60_000
    private val VIEWPORT_EVICT  = VIEWPORT_BUFFER * 2

    private val viewportHandler  = Handler(Looper.getMainLooper())
    private var viewportRunnable: Runnable? = null

    // ── Search ────────────────────────────────────────────────────────────────
    private val searchResults    = mutableListOf<Int>()
    private var currentResult    = -1
    private var lastQuery        = ""
    private val COLOR_MATCH_BG   = 0x55FFD600.toInt()
    private val COLOR_CURRENT_BG = 0xCCFF6F00.toInt()
    private val activeMatchSpans = mutableListOf<BackgroundColorSpan>()
    private val currentMatchSpan = mutableListOf<BackgroundColorSpan>()

    // ── Scrollbar ─────────────────────────────────────────────────────────────
    private var minThumbPx  = 0
    private val fadeHandler  = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        scrollbarThumb.animate().alpha(0f).setDuration(600).start()
    }

    // ── Custom text selection ─────────────────────────────────────────────────
    // • textIsSelectable=false → no system ActionMode, no makeNewLayout() freeze
    // • Long press on text → select token under finger, show popup above it
    // • Long press on whitespace → clear any active selection
    // • ▲ expands through 5 levels (see LEVEL_* constants below)
    // • ▼ shrinks back; hidden when at the original token level
    // • ▲ becomes "Select all" when at level 5 with no enclosing parent element
    //
    // LEVELS:
    //   0 = original token (the first thing the user touched; ▼ hidden at this level)
    //   1 = android:name=   (attribute name including namespace prefix and = sign)
    //   2 = "value"         (full quoted value including the quotes)
    //   3 = full line       (trimmed leading whitespace)
    //   4 = tag content     (everything inside <tag … > but without the tag name)
    //   5 = full element    (<tag … /> or <tag>…</tag>)
    //   6 = entire file     (▲ becomes "Select all")

    private var selectionStart     = -1
    private var selectionEnd       = -1
    private var selectionLevel     = 0     // current expansion level
    private var selectionOrigStart = -1   // saved on first long-press; restored by ▼ at level 0
    private var selectionOrigEnd   = -1
    private var selectionSpan: BackgroundColorSpan? = null
    private val COLOR_SELECTION    = 0x554FC3F7.toInt()

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        setContentView(R.layout.activity_xml_preview)
        setupToolbar(); setupViews(); setupScrollbar(); setupSearch()

        xmlContent = XmlContentHolder.get() ?: intent.getStringExtra("XML_CONTENT") ?: ""
        if (xmlContent.isEmpty()) { codeTextView.text = getString(R.string.error_no_content); return }

        startRender()
    }

    override fun onDestroy() {
        super.onDestroy()
        spanApplier.cancel()
        selectionPopup?.dismiss()
        viewportHandler.removeCallbacksAndMessages(null)
        fadeHandler.removeCallbacksAndMessages(null)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AndroidManifest.xml"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        codeTextView        = findViewById(R.id.codeTextView)
        xmlScrollView       = findViewById(R.id.xmlScrollView)
        xmlHorizontalScroll = findViewById(R.id.xmlHorizontalScroll)
        scrollbarThumb      = findViewById(R.id.scrollbarThumb)
        searchBar           = findViewById(R.id.searchBar)
        searchDivider       = findViewById(R.id.searchDivider)
        etSearch            = findViewById(R.id.etSearch)
        tvSearchCount       = findViewById(R.id.tvSearchCount)
        btnSearchPrev       = findViewById(R.id.btnSearchPrev)
        btnSearchNext       = findViewById(R.id.btnSearchNext)
        btnSearchClose      = findViewById(R.id.btnSearchClose)
        minThumbPx          = (56 * resources.displayMetrics.density).toInt()
        searchBar.visibility     = View.GONE
        searchDivider.visibility = View.GONE

        // Disable system text selection — it calls makeNewLayout() over all
        // spans on large files → freeze. We handle selection ourselves below.
        codeTextView.setTextIsSelectable(false)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            // Long press on text → select token; on whitespace → clear selection
            override fun onLongPress(e: MotionEvent) {
                val offset = offsetForEvent(e) ?: run { clearCustomSelection(); return }
                val range  = tokenRangeAt(offset)

                if (range == null) {
                    // Whitespace or out of bounds → dismiss current selection
                    clearCustomSelection()
                    return
                }

                // On sauvegarde toujours le jeton original comme référence de base
                selectionOrigStart = range.first
                selectionOrigEnd   = range.second
                selectionLevel     = 0

                val text = xmlContent

                // --- SÉLECTION INTELLIGENTE (SMART SELECTION) ---

                // 1. Détection automatique : Est-ce un nom de balise ? (<tag ou </tag)
                val isTagName = (range.first > 0 && text[range.first - 1] == '<') ||
                        (range.first > 1 && text[range.first - 1] == '/' && text[range.first - 2] == '<')

                // 2. Détection automatique : Est-ce une valeur entre guillemets ? ("value")
                val isInsideQuotes = (range.first > 0 && text[range.first - 1] == '"') &&
                        (range.second < text.length && text[range.second] == '"')

                if (isTagName) {
                    // Auto-élévation au Niveau 5 (Élément complet avec ses enfants)
                    val elementRange = selElement(selectionOrigStart)
                    if (elementRange != null) {
                        selectionLevel = 5
                        applySelection(elementRange.first, elementRange.second)
                        return
                    }
                } else if (isInsideQuotes) {
                    // Auto-élévation au Niveau 2 (Valeur incluant les guillemets)
                    val quoteRange = selQuotedValue(selectionOrigStart)
                    if (quoteRange != null) {
                        selectionLevel = 2
                        applySelection(quoteRange.first, quoteRange.second)
                        return
                    }
                }

                // 3. Fallback : Jeton standard (Niveau 0)
                applySelection(range.first, range.second)
            }
        })

        codeTextView.setOnTouchListener { _, event ->
            // Cancel any in-progress span batch immediately on touch.
            // Prevents accumulated dirty flags from triggering a full re-layout.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                spanApplier.cancel()
                viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
            }
            gd.onTouchEvent(event)
            true
        }
    }

    // ─── Custom selection helpers ─────────────────────────────────────────────

    /** Returns the character offset in the text corresponding to a touch event. */
    private fun offsetForEvent(e: MotionEvent): Int? {
        val layout = codeTextView.layout ?: return null
        val x = (e.x - codeTextView.totalPaddingLeft).toInt()
        val y = (e.y - codeTextView.totalPaddingTop).toInt()
        if (y < 0) return null
        val line = layout.getLineForVertical(y).coerceAtMost(layout.lineCount - 1)
        return layout.getOffsetForHorizontal(line, x.toFloat())
    }

    /**
     * Returns the start/end of the best token at [offset], or null if [offset]
     * falls on whitespace (caller should then clear the selection).
     *
     * Priority order:
     *  1. Whitespace / newline          → null  (clear selection)
     *  2. Special XML chars < > / = ?   → single character
     *  3. Inside a quoted value "…"     → the identifier run under the finger
     *     (dotted names like android.permission.CAMERA are returned whole)
     *  4. Outside quotes, on identifier → full identifier including dots, colons, hyphens
     *  5. Any other non-whitespace char → that character only
     */
    private fun tokenRangeAt(offset: Int): Pair<Int, Int>? {
        val text = xmlContent
        if (offset < 0 || offset >= text.length) return null

        val ch = text[offset]

        // Whitespace → clear selection
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') return null

        // Special XML characters → single char
        if (ch in "<>/=?!") return offset to offset + 1

        // Check if inside a quoted value "..."
        var qOpen = offset
        while (qOpen > 0 && text[qOpen] != '"' && text[qOpen] != '\n') qOpen--
        if (qOpen >= 0 && text[qOpen] == '"') {
            // CORRECTION ICI : qClose doit commencer APRÈS qOpen
            var qClose = qOpen + 1
            while (qClose < text.length && text[qClose] != '"' && text[qClose] != '\n') qClose++

            if (qClose < text.length && text[qClose] == '"') {
                // Si la valeur entre guillemet est vide (ex: ""), on retourne juste les guillemets
                if (qOpen + 1 >= qClose) return qOpen to (qClose + 1)

                return qOpen + 1 to qClose   // content without the surrounding quotes
            }
        }

        // Outside quotes — expand identifier chars (covers tag names, attr names, etc.)
        val isIdChar = { c: Char -> c.isLetterOrDigit() || c in ".-_:@" }
        if (isIdChar(ch)) {
            var s = offset; while (s > 0 && isIdChar(text[s - 1])) s--
            var e = offset; while (e < text.length && isIdChar(text[e])) e++
            return s to e
        }

        // Any other non-whitespace character (standalone symbol)
        return offset to offset + 1
    }

    /** Called by the gesture detector after setting origStart/origEnd/level. */
    private fun showCustomSelection(start: Int, end: Int) = applySelection(start, end)

    private var selectionPopup: android.widget.PopupWindow? = null

    private fun showSelectionPopup(start: Int, end: Int) {
        selectionPopup?.dismiss()
        val layout = codeTextView.layout ?: return
        val ctx = this
        val dm  = resources.displayMetrics
        val dp8  = (8  * dm.density).toInt()
        val dp12 = (12 * dm.density).toInt()
        val dp36 = (36 * dm.density).toInt()
        val dp1  = dm.density.toInt().coerceAtLeast(1)

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp8, dp8 / 2, dp8, dp8 / 2)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            elevation = 8f * dm.density
        }
        fun sep() = android.view.View(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp1,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                .also { it.setMargins(0, dp8, 0, dp8) }
            setBackgroundColor(0xFFCCCCCC.toInt())
        }
        fun btn(label: String, action: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1565C0.toInt())
            setPadding(dp12, dp8, dp12, dp8)
            minHeight = dp36
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setOnClickListener { selectionPopup?.dismiss(); action() }
        }

        // Copy / Copy line — always visible
        container.addView(btn("Copy") { copyToClipboard(xmlContent.substring(start, end)) })
        container.addView(sep())
        container.addView(btn("Copy line") {
            val line = layout.getLineForOffset(start.coerceIn(0, xmlContent.length - 1))
            val ls   = layout.getLineStart(line)
            val le   = layout.getLineEnd(line).let {
                if (it > ls && xmlContent[it - 1] == '\n') it - 1 else it
            }
            copyToClipboard(xmlContent.substring(ls, le))
        })
        // ▼ hidden at level 0 (original token)
        if (selectionLevel > 0) {
            container.addView(sep())
            container.addView(btn("▼") { shrinkSelection() })
        }
        // ▲ becomes "Select all" when at level 5 with no enclosing parent element
        container.addView(sep())
        val atMax = selectionLevel >= 5 && selElement(selectionOrigStart.coerceAtLeast(0)) == null
        if (atMax) {
            container.addView(btn("Select all") { applySelectionFull() })
        } else {
            container.addView(btn("▲") { expandSelection() })
        }
        container.addView(sep())
        container.addView(btn("✕") { clearCustomSelection() })

        container.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val popW = container.measuredWidth
        val popH = container.measuredHeight

        // getLocationOnScreen already includes all scrolling — no manual subtraction
        val tvPos   = IntArray(2); codeTextView.getLocationOnScreen(tvPos)
        val selLine = layout.getLineForOffset(start.coerceIn(0, xmlContent.length - 1))
        val selX    = layout.getPrimaryHorizontal(start).toInt()
        val selTop  = layout.getLineTop(selLine)
        val screenW = dm.widthPixels
        val screenX = tvPos[0] + codeTextView.paddingLeft + selX
        val screenY = tvPos[1] + codeTextView.paddingTop  + selTop
        val popX    = (screenX - popW / 2).coerceIn(0, (screenW - popW).coerceAtLeast(0))
        val popY    = (screenY - popH - dp8).coerceAtLeast(0)

        val popup = android.widget.PopupWindow(container,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true; isFocusable = false
            setBackgroundDrawable(
                android.graphics.Color.TRANSPARENT.toDrawable())
        }
        popup.showAtLocation(window.decorView, android.view.Gravity.NO_GRAVITY, popX, popY)
        selectionPopup = popup
    }

    // ── Hierarchical expand / shrink ──────────────────────────────────────────
    //
    //  Level 0 = original token (▼ hidden)
    //  Level 1 = android:name=   (attr name + namespace + equals sign)
    //  Level 2 = "value"         (full quoted value including quotes)
    //  Level 3 = full line       (trimmed leading spaces)
    //  Level 4 = tag content     (inside <tag … > but without the tag name)
    //  Level 5 = full element    (<tag … /> or <tag>…</tag>)
    //  Level 6 = entire file     (triggered by "Select all" when no parent)

    private fun expandSelection() {
        if (selectionStart < 0) return
        val ref = selectionOrigStart.coerceAtLeast(0)
        val (ns, ne, nl) = when (selectionLevel) {
            0 -> selAttrName(ref)?.let    { (s, e) -> Triple(s, e, 1) }
                ?: selQuotedValue(ref)?.let { (s, e) -> Triple(s, e, 2) }
                ?: run { val (s, e) = selLine(ref); Triple(s, e, 3) }
            1 -> selQuotedValue(ref)?.let { (s, e) -> Triple(s, e, 2) }
                ?: run { val (s, e) = selLine(ref); Triple(s, e, 3) }
            2 -> { val (s, e) = selLine(ref); Triple(s, e, 3) }
            3 -> selTagContent(ref)?.let  { (s, e) -> Triple(s, e, 4) }
                ?: selElement(ref)?.let    { (s, e) -> Triple(s, e, 5) }
                ?: Triple(0, xmlContent.length, 6)
            4 -> selElement(ref)?.let     { (s, e) -> Triple(s, e, 5) }
                ?: Triple(0, xmlContent.length, 6)
            else -> Triple(0, xmlContent.length, 6)
        }
        selectionLevel = nl
        applySelection(ns, ne)
    }

    private fun shrinkSelection() {
        if (selectionStart < 0 || selectionLevel <= 0) return
        val ref = selectionOrigStart.coerceAtLeast(0)
        val (ns, ne, nl) = when (selectionLevel) {
            1    -> Triple(selectionOrigStart, selectionOrigEnd, 0)
            2    -> selAttrName(ref)?.let    { (s, e) -> Triple(s, e, 1) }
                ?: Triple(selectionOrigStart, selectionOrigEnd, 0)
            3    -> selQuotedValue(ref)?.let { (s, e) -> Triple(s, e, 2) }
                ?: selAttrName(ref)?.let { (s, e) -> Triple(s, e, 1) }
                ?: Triple(selectionOrigStart, selectionOrigEnd, 0)
            4    -> { val (s, e) = selLine(ref); Triple(s, e, 3) }
            5    -> selTagContent(ref)?.let  { (s, e) -> Triple(s, e, 4) }
                ?: run { val (s, e) = selLine(ref); Triple(s, e, 3) }
            else -> Triple(selectionOrigStart, selectionOrigEnd, 0)
        }
        selectionLevel = nl
        applySelection(ns, ne)
    }

    private fun applySelectionFull() { selectionLevel = 6; applySelection(0, xmlContent.length) }

    // ── Level range helpers ───────────────────────────────────────────────────

    /** L1: find `namespace:localName=` or `name=` surrounding [ref]. */
    /** L1: find `namespace:localName=` surrounding or near [ref]. */
    private fun selAttrName(ref: Int): Pair<Int, Int>? {
        val text = xmlContent
        val isId = { c: Char -> c.isLetterOrDigit() || c in ":-_." }

        // Find the position of the `=` for the attribute at [ref].
        // We need to locate it without crossing tag boundaries (<, >)
        // and without treating the `=` inside a value as an attr `=`.
        var eqPos = -1

        // Case A: ref is on the attribute name itself — scan forward to find `=`
        var fe = ref
        while (fe < text.length && isId(text[fe])) fe++
        if (fe < text.length && text[fe] == '=') {
            eqPos = fe
        }

        // Case B: ref is inside "value" — find the `=` that precedes the opening `"`
        if (eqPos < 0) {
            // Walk backward to the opening `"` (stop at `<` or newline)
            var i = ref
            while (i >= 0 && text[i] != '"' && text[i] != '<' && text[i] != '\n') i--
            if (i >= 0 && text[i] == '"') {
                // i is now the opening quote of the value. The `=` must be right before it
                // (possibly with spaces between = and ").
                var j = i - 1
                while (j >= 0 && text[j] == ' ') j--   // skip optional spaces
                if (j >= 0 && text[j] == '=') eqPos = j
            }
        }

        if (eqPos < 0) return null

        // Find the identifier immediately before eqPos (skip any spaces)
        var attrEnd = eqPos - 1
        while (attrEnd >= 0 && text[attrEnd] == ' ') attrEnd--
        if (attrEnd < 0 || !isId(text[attrEnd])) return null
        val end = attrEnd + 1
        var start = attrEnd
        while (start > 0 && isId(text[start - 1])) start--
        return if (end > start) start to (eqPos + 1) else null
    }

    /** L2: find `"value"` enclosing [ref] on the same line. */
    private fun selQuotedValue(ref: Int): Pair<Int, Int>? {
        val text = xmlContent
        var open = ref
        while (open >= 0 && text[open] != '"' && text[open] != '\n') open--
        if (open < 0 || text[open] != '"') return null
        var close = ref + 1
        while (close < text.length && text[close] != '"' && text[close] != '\n') close++
        if (close >= text.length || text[close] != '"') return null
        return open to (close + 1)
    }

    /** L3: full line trimmed of leading whitespace. */
    private fun selLine(ref: Int): Pair<Int, Int> {
        val layout = codeTextView.layout ?: return ref to ref
        val clamp  = ref.coerceIn(0, xmlContent.length - 1)
        val line   = layout.getLineForOffset(clamp)
        val ls     = layout.getLineStart(line)
        val le     = layout.getLineEnd(line).let {
            if (it > ls && xmlContent[it - 1] == '\n') it - 1 else it
        }
        var s = ls; while (s < le && xmlContent[s] == ' ') s++
        return s to le
    }

    /** L4: everything inside `<tag … >` except the tag name itself. */
    private fun selTagContent(ref: Int): Pair<Int, Int>? {
        val text = xmlContent
        var tagOpen = ref; while (tagOpen > 0 && text[tagOpen] != '<') tagOpen--
        if (tagOpen < 0 || text[tagOpen] != '<') return null
        var tagClose = ref; while (tagClose < text.length && text[tagClose] != '>') tagClose++
        if (tagClose >= text.length) return null
        var ne = tagOpen + 1
        if (ne < text.length && text[ne] == '/') ne++
        while (ne < text.length && !text[ne].isWhitespace() && text[ne] != '>') ne++
        if (ne >= tagClose) return null
        var s = ne; while (s < tagClose && text[s] == ' ') s++
        val e = if (text[tagClose - 1] == '/') tagClose - 1 else tagClose
        return if (e > s) s to e else null
    }

    /** L5: full `<element … />` or `<element>…</element>` containing [ref]. */
    private fun selElement(ref: Int): Pair<Int, Int>? {
        val text   = xmlContent
        var s = ref; while (s > 0 && text[s] != '<') s--
        if (s < 0 || text[s] != '<') return null
        var ni = s + 1; if (ni < text.length && text[ni] == '/') ni++
        val isId = { c: Char -> c.isLetterOrDigit() || c in ":-_." }
        var ne = ni; while (ne < text.length && isId(text[ne])) ne++
        if (ne <= ni) return null
        val tagName = text.substring(ni, ne)
        var e = s; while (e < text.length && text[e] != '>') e++
        if (e >= text.length) return null
        if (e > 0 && text[e - 1] == '/') return s to (e + 1)   // self-closing
        val closeTag = "</$tagName>"
        val ci = text.indexOf(closeTag, e)
        return if (ci >= 0) s to (ci + closeTag.length) else s to (e + 1)
    }

    // ── Apply / clear / copy ──────────────────────────────────────────────────

    private fun applySelection(start: Int, end: Int) {
        val spannable = codeTextView.text as? Spannable ?: return

        // CORRECTION DE SÉCURITÉ : Empêche les crashs "end before start" ou "out of bounds"
        val safeStart = minOf(start, end).coerceAtLeast(0)
        val safeEnd = maxOf(start, end).coerceAtMost(xmlContent.length)

        if (safeStart >= safeEnd) {
            clearCustomSelection()
            return
        }

        selectionSpan?.let { spannable.removeSpan(it) }
        val span = BackgroundColorSpan(COLOR_SELECTION)
        spannable.setSpan(span, safeStart, safeEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        selectionSpan  = span
        selectionStart = safeStart
        selectionEnd   = safeEnd
        codeTextView.post { showSelectionPopup(safeStart, safeEnd) }
    }

    private fun clearCustomSelection() {
        selectionPopup?.dismiss(); selectionPopup = null
        val spannable = codeTextView.text as? Spannable ?: return
        selectionSpan?.let { spannable.removeSpan(it) }
        selectionSpan       = null
        selectionStart      = -1
        selectionEnd        = -1
        selectionLevel      = 0
        selectionOrigStart  = -1
        selectionOrigEnd    = -1
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(ClipboardManager::class.java)
        cb.setPrimaryClip(ClipData.newPlainText("xml", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        viewportHandler.postDelayed({ clearCustomSelection() }, 800)
    }


    // ════════════════════════════════════════════════════════════════════════
    // Render
    // ════════════════════════════════════════════════════════════════════════

    private fun startRender() {
        val params  = TextViewCompat.getTextMetricsParams(codeTextView)   // main thread only
        val isLarge = xmlContent.length > PLAIN_THRESHOLD

        Thread {
            try {
                if (isLarge) {
                    // Step 1 — plain text: user sees content immediately, 0 spans
                    val precomputed = PrecomputedTextCompat.create(SpannableString(xmlContent), params)
                    runOnUiThread {
                        TextViewCompat.setPrecomputedText(codeTextView, precomputed)
                        xmlScrollView.post { updateViewportSpans() }
                    }

                    // Step 2 — 80 KB chunks: colour fills in progressively
                    val len         = xmlContent.length
                    val numChunks   = (len + HIGHLIGHT_CHUNK - 1) / HIGHLIGHT_CHUNK
                    val accumulated = ArrayList<SpanRecord>()

                    for (chunkIdx in 0 until numChunks) {
                        val from = chunkIdx * HIGHLIGHT_CHUNK
                        val to   = minOf(from + HIGHLIGHT_CHUNK, len)

                        accumulated.addAll(
                            XmlSyntaxHighlighter.computeSpans(xmlContent, from, to)
                                .map { (s, e, c) ->
                                    SpanRecord(ForegroundColorSpan(c), s, e,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                        )

                        val snapshot: List<SpanRecord> = accumulated.toList()
                        runOnUiThread {
                            allSpanInfos = snapshot
                            updateViewportSpans()
                        }
                    }
                } else {
                    // Small file — full highlight + PrecomputedTextCompat
                    val highlighted = XmlSyntaxHighlighter.highlight(xmlContent)
                    val precomputed = PrecomputedTextCompat.create(
                        SpannableString.valueOf(highlighted), params
                    )
                    runOnUiThread { TextViewCompat.setPrecomputedText(codeTextView, precomputed) }
                }
            } catch (e: Exception) {
                val plain = PrecomputedTextCompat.create(SpannableString(xmlContent), params)
                runOnUiThread { runCatching { TextViewCompat.setPrecomputedText(codeTextView, plain) } }
            }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Viewport colorization — BATCHED
    //
    // Called:
    //   • after each chunk of allSpanInfos is ready (background → main thread)
    //   • 100 ms after each scroll stop (debounce in setupScrollbar)
    //
    // Work on the main thread:
    //   1. Bounds computation  — O(1), pure arithmetic
    //   2. Eviction            — O(applied) removeSpan calls, bounded set
    //   3. Binary search       — O(log n), negligible
    //   4. Collect toApply     — O(viewport/chunk), small
    //   5. spanApplier.startBatch() — schedules micro-batches, returns immediately
    //
    // The actual setSpan() calls are distributed across future frames by
    // ViewportSpanApplier, so the main thread is free between batches.
    // ════════════════════════════════════════════════════════════════════════

    private fun updateViewportSpans() {
        // Cancel any in-progress batch for a stale scroll position
        spanApplier.cancel()

        val snapshot = allSpanInfos          // local ref — immutable list
        if (snapshot.isEmpty()) return
        val target = codeTextView.text as? Spannable ?: return
        val layout = codeTextView.layout              ?: return

        // 1. Viewport bounds
        val scrollY    = xmlScrollView.scrollY
        val visH       = xmlScrollView.height
        val topLine    = layout.getLineForVertical(scrollY).coerceAtLeast(0)
        val botLine    = layout.getLineForVertical(scrollY + visH).coerceAtMost(layout.lineCount - 1)
        val charStart  = layout.getLineStart(topLine)
        val charEnd    = layout.getLineEnd(botLine)
        val buffStart  = (charStart - VIEWPORT_BUFFER).coerceAtLeast(0)
        val buffEnd    = (charEnd   + VIEWPORT_BUFFER).coerceAtMost(target.length)
        val evictStart = (charStart - VIEWPORT_EVICT).coerceAtLeast(0)
        val evictEnd   = (charEnd   + VIEWPORT_EVICT).coerceAtMost(target.length)

        // 2. Evict spans far from viewport (immediate, bounded set)
        spanApplier.evictSpans(target, snapshot, appliedSpans, evictStart, evictEnd)

        // 3. Binary search — first index in buffered window
        var lo = 0; var hi = snapshot.size - 1; var first = snapshot.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (snapshot[mid].start >= buffStart) { first = mid; hi = mid - 1 } else lo = mid + 1
        }

        // 4. Collect indices that need to be applied
        val toApply = ArrayList<Int>()
        val len = target.length
        for (i in first until snapshot.size) {
            val info = snapshot[i]
            if (info.start > buffEnd) break
            if (!appliedSpans.containsKey(i) && info.end <= len) toApply.add(i)
        }

        // 5. Schedule micro-batched application — non-blocking
        spanApplier.startBatch(target, snapshot, appliedSpans, toApply)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Search
    // ════════════════════════════════════════════════════════════════════════

    private fun setupSearch() {
        etSearch.doAfterTextChanged { text ->
            val q = text?.toString() ?: ""
            if (q != lastQuery) { lastQuery = q; performSearch(q) }
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { navigateResult(+1); true } else false
        }
        btnSearchNext.setOnClickListener  { navigateResult(+1) }
        btnSearchPrev.setOnClickListener  { navigateResult(-1) }
        btnSearchClose.setOnClickListener { closeSearch() }
    }

    private fun performSearch(query: String) {
        clearSearchSpans(); searchResults.clear(); currentResult = -1; tvSearchCount.text = ""
        if (query.length < 2) return
        val spannable = codeTextView.text as? Spannable ?: return
        val fullText  = spannable.toString()
        var idx = fullText.indexOf(query, ignoreCase = true)
        while (idx >= 0) { searchResults.add(idx); idx = fullText.indexOf(query, idx + 1, ignoreCase = true) }
        if (searchResults.isEmpty()) {
            tvSearchCount.text = "0"; tvSearchCount.setTextColor("#D32F2F".toColorInt()); return
        }
        tvSearchCount.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        for (pos in searchResults) {
            val span = BackgroundColorSpan(COLOR_MATCH_BG)
            spannable.setSpan(span, pos, pos + query.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            activeMatchSpans.add(span)
        }
        navigateResult(+1)
    }

    private fun navigateResult(direction: Int) {
        if (searchResults.isEmpty()) return
        currentResult = when {
            currentResult < 0  -> 0
            direction > 0      -> (currentResult + 1) % searchResults.size
            else               -> (currentResult - 1 + searchResults.size) % searchResults.size
        }
        tvSearchCount.text = getString(R.string.search_count, currentResult + 1, searchResults.size)
        val spannable = codeTextView.text as? Spannable ?: return
        currentMatchSpan.forEach { spannable.removeSpan(it) }; currentMatchSpan.clear()
        val pos  = searchResults[currentResult]
        val span = BackgroundColorSpan(COLOR_CURRENT_BG)
        spannable.setSpan(span, pos, pos + lastQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        currentMatchSpan.add(span)
        scrollToPosition(pos)
    }

    private fun scrollToPosition(charOffset: Int) {
        codeTextView.post {
            val layout = codeTextView.layout ?: return@post
            val line   = layout.getLineForOffset(charOffset)
            val lineY  = layout.getLineTop(line) + codeTextView.paddingTop
            xmlScrollView.smoothScrollTo(0, (lineY - xmlScrollView.height / 2).coerceAtLeast(0))
        }
    }

    private fun clearSearchSpans() {
        val sp = codeTextView.text as? Spannable ?: return
        activeMatchSpans.forEach { sp.removeSpan(it) }; activeMatchSpans.clear()
        currentMatchSpan.forEach { sp.removeSpan(it) }; currentMatchSpan.clear()
    }

    private fun openSearch() {
        searchBar.visibility = View.VISIBLE; searchDivider.visibility = View.VISIBLE
        etSearch.requestFocus()
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        clearSearchSpans(); searchResults.clear(); currentResult = -1; lastQuery = ""
        etSearch.text?.clear(); tvSearchCount.text = ""
        searchBar.visibility = View.GONE; searchDivider.visibility = View.GONE
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scrollbar
    // ════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollbar() {
        var dragStartRawY = 0f; var dragStartScrollY = 0

        // ── Vertical scroll ────────────────────────────────────────────────
        xmlScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            updateThumbPosition(scrollY); showThumb(); scheduleFade()
            spanApplier.cancel()
            viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
            val r = Runnable { updateViewportSpans() }.also { viewportRunnable = it }
            viewportHandler.postDelayed(r, 100)
        })

        // ── Horizontal scroll ──────────────────────────────────────────────
        // HorizontalScrollView has no OnScrollChangeListener in API < 23,
        // so we use a touch listener.
        // ACTION_MOVE  → cancel any in-progress span batch (prevents janky frames)
        // ACTION_UP/CANCEL → schedule updateViewportSpans after 100 ms idle
        xmlHorizontalScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    spanApplier.cancel()
                    viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
                    val r = Runnable { updateViewportSpans() }.also { viewportRunnable = it }
                    viewportHandler.postDelayed(r, 100)
                }
            }
            false   // don't consume — let HorizontalScrollView handle fling/scroll
        }

        scrollbarThumb.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawY = event.rawY; dragStartScrollY = xmlScrollView.scrollY
                    setThumbColor("#6200EE"); fadeHandler.removeCallbacks(fadeRunnable); v.alpha = 1f; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val total  = xmlScrollView.getChildAt(0)?.height ?: return@setOnTouchListener true
                    val vis    = xmlScrollView.height
                    val range  = total - vis; if (range <= 0) return@setOnTouchListener true
                    val track  = (v.parent as? View)?.height?.minus(8) ?: return@setOnTouchListener true
                    val tRange = track - v.height; if (tRange <= 0) return@setOnTouchListener true
                    val delta  = ((event.rawY - dragStartRawY) / tRange * range).toInt()
                    xmlScrollView.scrollTo(0, (dragStartScrollY + delta).coerceIn(0, range)); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setThumbColor("#66AAAAAA"); scheduleFade(); true
                }
                else -> false
            }
        }

        xmlScrollView.post {
            updateThumbPosition(0)
            scrollbarThumb.alpha = 0f
            scrollbarThumb.visibility = View.VISIBLE
        }
    }

    private fun updateThumbPosition(scrollY: Int) {
        val total = xmlScrollView.getChildAt(0)?.height ?: return
        val vis   = xmlScrollView.height
        if (total <= vis) { scrollbarThumb.visibility = View.INVISIBLE; return }
        scrollbarThumb.visibility = View.VISIBLE
        val parent   = scrollbarThumb.parent as? View ?: return
        val track    = parent.height - 8
        val thumbH   = ((vis.toFloat() / total) * track).toInt().coerceAtLeast(minThumbPx)
        val ratio    = scrollY.toFloat() / (total - vis)
        val thumbTop = (ratio * (track - thumbH) + 4).toInt().coerceIn(4, track - thumbH + 4)
        if (scrollbarThumb.height != thumbH) {
            val lp = scrollbarThumb.layoutParams; lp.height = thumbH; scrollbarThumb.layoutParams = lp
        }
        scrollbarThumb.translationY = thumbTop.toFloat()
    }

    private fun showThumb()    { scrollbarThumb.animate().cancel(); scrollbarThumb.alpha = 1f }
    private fun scheduleFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, 1500)
    }

    private fun setThumbColor(hex: String) {
        val dp6 = 6f * resources.displayMetrics.density
        scrollbarThumb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = dp6; setColor(hex.toColorInt())
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Menu
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.apply {
            add(Menu.NONE, MENU_SEARCH, Menu.NONE, "Search")
                .setIcon(android.R.drawable.ic_menu_search)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            add(Menu.NONE, MENU_COPY, Menu.NONE, "Copy XML")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_SEARCH -> { if (searchBar.isVisible) closeSearch() else openSearch(); true }
        MENU_COPY   -> {
            val cb = getSystemService(ClipboardManager::class.java)
            cb.setPrimaryClip(ClipData.newPlainText("AndroidManifest.xml", xmlContent))
            Toast.makeText(this, "XML copied to clipboard", Toast.LENGTH_SHORT).show(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val MENU_SEARCH = 1001
        private const val MENU_COPY   = 1002
    }
}