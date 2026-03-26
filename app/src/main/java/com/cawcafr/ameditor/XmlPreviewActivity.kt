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

        // Cancel any in-progress span batch the instant the user touches the
        // text view. Without this, setSpan() calls from the batch accumulate
        // dirty flags on the TextView; when Android then calls makeNewLayout()
        // to position the cursor/selection it has to iterate all spans → freeze.
        codeTextView.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                spanApplier.cancel()
                viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
            }
            v.onTouchEvent(event)   // let TextView handle selection normally
        }
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