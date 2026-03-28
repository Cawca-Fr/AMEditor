package com.cawcafr.ameditor.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.text.PrecomputedTextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import com.cawcafr.ameditor.R
import com.cawcafr.ameditor.XmlContentHolder
import org.lsposed.lsparanoid.Obfuscate
import java.util.Stack
import java.util.regex.Pattern

@Obfuscate
class CustomPatchActivity : AppCompatActivity() {

    private lateinit var xmlTextView: TextView
    private lateinit var btnDelete: com.google.android.material.button.MaterialButton
    private lateinit var btnDeactivate: com.google.android.material.button.MaterialButton
    private lateinit var xmlScrollView: androidx.core.widget.NestedScrollView
    private lateinit var xmlHorizontalScroll: android.widget.HorizontalScrollView
    private lateinit var scrollbarThumb: android.view.View

    private var minThumbPx   = 0
    private val fadeHandler  = Handler(Looper.getMainLooper())
    private val fadeRunnable = Runnable { scrollbarThumb.animate().alpha(0f).setDuration(600).start() }

    private var currentMode   = Mode.DELETE
    private var xmlContent    = ""
    private var displayedText = ""

    @Volatile private var isParsed = false

    private val allNodes    = mutableListOf<XmlNode>()
    private val nodeStates  = mutableMapOf<Int, Mode>()
    private val activeSpans = mutableMapOf<Int, BackgroundColorSpan>()

    private val undoStack    = ArrayDeque<Map<Int, Mode>>()
    private val redoStack    = ArrayDeque<Map<Int, Mode>>()
    private var undoMenuItem: MenuItem? = null
    private var redoMenuItem: MenuItem? = null

    private var loadingDialog: AlertDialog? = null

    // ── Viewport colorization ─────────────────────────────────────────────────
    // Same architecture as XmlPreviewActivity: chunked background loading +
    // micro-batched span application via ViewportSpanApplier.

    @Volatile private var allSpanInfos: List<SpanRecord> = emptyList()
    private val appliedHighlightSpans = mutableMapOf<Int, Any>()
    private val spanApplier           = ViewportSpanApplier()

    private val HIGHLIGHT_CHUNK = 80_000
    private val VIEWPORT_BUFFER = 60_000
    private val VIEWPORT_EVICT  = VIEWPORT_BUFFER * 2
    private val viewportHandler  = Handler(Looper.getMainLooper())
    private var viewportRunnable: Runnable? = null

    companion object {
        private const val TAG      = "CustomPatchActivity"
        private const val MAX_UNDO = 30
        private val PROTECTED_TAGS   = setOf("manifest", "application")
        private val COLOR_DELETE     = 0x40D32F2F.toInt()
        private val COLOR_DEACTIVATE = 0x40FBC02D.toInt()
    }

    enum class Mode { DELETE, DEACTIVATE, NONE }

    data class XmlNode(
        val index: Int, val tagName: String, val start: Int, var end: Int,
        var parent: XmlNode? = null, val children: MutableList<XmlNode> = mutableListOf(),
        var androidName: String? = null
    ) {
        fun length() = if (end > start) end - start else 0
        override fun hashCode() = index
        override fun equals(other: Any?) = other is XmlNode && index == other.index
        override fun toString() = "XmlNode(#$index <$tagName> $start..$end)"
    }

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        setContentView(R.layout.activity_custom_patch)
        setupToolbar(); setupViews(); setupButtons(); setupTouchListener()

        xmlContent = XmlContentHolder.get() ?: intent.getStringExtra("XML_CONTENT") ?: ""
        if (xmlContent.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_xml_received), Toast.LENGTH_LONG).show()
            finish(); return
        }
        startRender()
    }

    override fun onDestroy() {
        super.onDestroy()
        spanApplier.cancel()
        viewportHandler.removeCallbacksAndMessages(null)
        fadeHandler.removeCallbacksAndMessages(null)
        loadingDialog?.dismiss(); loadingDialog = null
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        val tb = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(tb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupViews() {
        xmlTextView         = findViewById(R.id.xmlTextView)
        xmlTextView.highlightColor = android.graphics.Color.TRANSPARENT
        // Disable system selection — triggers makeNewLayout() over all syntax
        // spans → freeze. Node highlights use BackgroundColorSpan directly.
        xmlTextView.setTextIsSelectable(false)
        btnDelete           = findViewById(R.id.btnModeDelete)
        btnDeactivate       = findViewById(R.id.btnModeDeactivate)
        xmlScrollView       = findViewById(R.id.xmlScrollView)
        xmlHorizontalScroll = findViewById(R.id.xmlHorizontalScroll)
        scrollbarThumb      = findViewById(R.id.scrollbarThumb)
        minThumbPx     = (56 * resources.displayMetrics.density).toInt()
        setupCustomScrollbar(); refreshButtonLabels()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Render
    //
    // POURQUOI LE FREEZE DANS CustomPatchActivity (mais pas XmlPreviewActivity) :
    //
    // CustomPatchActivity affiche un loading dialog pendant parseNodes().
    // Quand le dialog se ferme, le thread background enchaîne immédiatement
    // 25 runOnUiThread { allSpanInfos = …; updateViewportSpans() } en rafale
    // (un par chunk de 80KB sur un XML de 2MB).
    //
    // Chaque updateViewportSpans() applique 60 spans synchrones + layout pass.
    // L'utilisateur voit le dialog se fermer et essaie de scroller, mais le
    // main thread est saturé par 25 × (60 setSpan + layout) = 1500 setSpan.
    //
    // FIX : dans le chunk loop, seul allSpanInfos est mis à jour (pure
    // assignment, 0 layout work). Les viewport updates viennent UNIQUEMENT de :
    //   1. xmlScrollView.post { updateViewportSpans() } juste après setText
    //      → colorise la zone visible initiale
    //   2. Le scroll listener (debounce 100ms après chaque arrêt)
    //   3. Un seul post final quand tous les chunks sont prêts
    //      → colorise si l'utilisateur n'a pas encore scrollé
    // ════════════════════════════════════════════════════════════════════════

    private fun startRender() {
        loadingDialog = AlertDialog.Builder(this)
            .setView(layoutInflater.inflate(R.layout.dialog_importing, null))
            .setCancelable(false)
            .create().also { it.show() }

        val params = TextViewCompat.getTextMetricsParams(xmlTextView)   // main thread only

        Thread {
            try {
                // Step 1 — parse XML nodes for tap detection
                parseNodes(xmlContent)
                displayedText = xmlContent

                // Step 2 — plain text layout (instantaneous setText on main thread)
                val precomputed = PrecomputedTextCompat.create(SpannableString(xmlContent), params)
                runOnUiThread {
                    TextViewCompat.setPrecomputedText(xmlTextView, precomputed)
                    isParsed = true
                    loadingDialog?.dismiss(); loadingDialog = null
                    // Post ONE initial viewport update — colorises the visible area on open.
                    // No further viewport updates are posted from the chunk loop.
                    xmlScrollView.post { updateViewportSpans() }
                }

                // Step 3 — 80 KB chunks: update allSpanInfos ONLY, no layout work.
                // The scroll listener drives viewport updates as the user navigates.
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

                    // Pure pointer assignment — zero layout work on main thread.
                    val snapshot: List<SpanRecord> = accumulated.toList()
                    runOnUiThread { allSpanInfos = snapshot }
                }

                // Step 4 — all spans are now ready: one final viewport update.
                // This covers the case where the user hasn't scrolled yet but
                // new spans outside the initial viewport are now available.
                runOnUiThread { updateViewportSpans() }

            } catch (e: Exception) {
                Log.e(TAG, "startRender error", e)
                runOnUiThread {
                    isParsed = true
                    loadingDialog?.dismiss(); loadingDialog = null
                    Toast.makeText(this, getString(R.string.generic_error_exception, e.message),
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Viewport colorization — BATCHED
    // See XmlPreviewActivity for full architectural notes.
    // ════════════════════════════════════════════════════════════════════════

    private fun updateViewportSpans() {
        spanApplier.cancel()

        val snapshot = allSpanInfos
        if (snapshot.isEmpty()) return
        val target = xmlTextView.text as? Spannable ?: return
        val layout = xmlTextView.layout              ?: return

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

        spanApplier.evictSpans(target, snapshot, appliedHighlightSpans, evictStart, evictEnd)

        var lo = 0; var hi = snapshot.size - 1; var first = snapshot.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (snapshot[mid].start >= buffStart) { first = mid; hi = mid - 1 } else lo = mid + 1
        }

        val toApply = ArrayList<Int>()
        val len = target.length
        for (i in first until snapshot.size) {
            val info = snapshot[i]
            if (info.start > buffEnd) break
            if (!appliedHighlightSpans.containsKey(i) && info.end <= len) toApply.add(i)
        }

        spanApplier.startBatch(target, snapshot, appliedHighlightSpans, toApply)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scrollbar custom
    // ════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCustomScrollbar() {
        var dragStartRawY = 0f; var dragStartScrollY = 0

        xmlScrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                updateThumbPosition(scrollY); showThumb(); scheduleFade()
                // Cancel stale batch on new scroll, then debounce 100 ms
                spanApplier.cancel()
                viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
                val r = Runnable { updateViewportSpans() }.also { viewportRunnable = it }
                viewportHandler.postDelayed(r, 100)
            }
        )

        // ── Horizontal scroll ──────────────────────────────────────────────
        // HorizontalScrollView has no scroll change listener pre-API 23.
        // Touch listener: cancel batch on MOVE, reschedule on UP/CANCEL.
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
                    val vis    = xmlScrollView.height; val range = total - vis
                    if (range <= 0) return@setOnTouchListener true
                    val track  = (v.parent as? android.view.View)?.height?.minus(8) ?: return@setOnTouchListener true
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
            scrollbarThumb.visibility = android.view.View.VISIBLE
        }
    }

    private fun setThumbColor(hex: String) {
        val dp6 = 6f * resources.displayMetrics.density
        scrollbarThumb.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE; cornerRadius = dp6
            setColor(hex.toColorInt())
        }
    }

    private fun updateThumbPosition(scrollY: Int) {
        val parent = scrollbarThumb.parent as? android.view.View ?: return
        val total  = xmlScrollView.getChildAt(0)?.height ?: return
        val vis    = xmlScrollView.height
        if (total <= vis) { scrollbarThumb.visibility = android.view.View.INVISIBLE; return }
        scrollbarThumb.visibility = android.view.View.VISIBLE
        val track  = parent.height - 8
        val thumbH = (vis.toFloat() / total * track).toInt().coerceAtLeast(minThumbPx)
        val ratio  = scrollY.toFloat() / (total - vis)
        val top    = (ratio * (track - thumbH) + 4).toInt().coerceIn(4, track - thumbH + 4)
        if (scrollbarThumb.height != thumbH) {
            val lp = scrollbarThumb.layoutParams; lp.height = thumbH; scrollbarThumb.layoutParams = lp
        }
        scrollbarThumb.translationY = top.toFloat()
    }

    private fun showThumb()    { scrollbarThumb.animate().cancel(); scrollbarThumb.alpha = 1f }
    private fun scheduleFade() { fadeHandler.removeCallbacks(fadeRunnable); fadeHandler.postDelayed(fadeRunnable, 1500) }

    // ════════════════════════════════════════════════════════════════════════
    // Button labels / styles
    // ════════════════════════════════════════════════════════════════════════

    private fun refreshButtonLabels() {
        val dc = nodeStates.values.count { it == Mode.DELETE }
        val ac = nodeStates.values.count { it == Mode.DEACTIVATE }
        btnDelete.text     = if (dc > 0) getString(R.string.btn_delete_count_label, dc)     else getString(R.string.btn_delete_label)
        btnDeactivate.text = if (ac > 0) getString(R.string.btn_deactivate_count_label, ac) else getString(R.string.btn_deactivate_label)
    }

    private fun setupButtons() {
        updateButtonStyles()
        btnDelete.setOnClickListener     { currentMode = if (currentMode == Mode.DELETE)     Mode.NONE else Mode.DELETE;     updateButtonStyles() }
        btnDeactivate.setOnClickListener { currentMode = if (currentMode == Mode.DEACTIVATE) Mode.NONE else Mode.DEACTIVATE; updateButtonStyles() }
    }

    private fun updateButtonStyles() {
        btnDelete.setBackgroundColor(Color.TRANSPARENT);     btnDelete.setTextColor("#D32F2F".toColorInt())
        btnDeactivate.setBackgroundColor(Color.TRANSPARENT); btnDeactivate.setTextColor("#F9A825".toColorInt())
        when (currentMode) {
            Mode.DELETE     -> btnDelete.setBackgroundColor("#FFCDD2".toColorInt())
            Mode.DEACTIVATE -> btnDeactivate.setBackgroundColor("#FFF9C4".toColorInt())
            else -> {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Undo / Redo
    // ════════════════════════════════════════════════════════════════════════

    private fun pushUndoState() {
        undoStack.addLast(nodeStates.toMap())
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear(); updateUndoRedoMenuItems()
    }
    private fun undo() { if (undoStack.isEmpty()) return; redoStack.addLast(nodeStates.toMap()); restoreState(undoStack.removeLast()); updateUndoRedoMenuItems(); Toast.makeText(this, getString(R.string.toast_undo), Toast.LENGTH_SHORT).show() }
    private fun redo() { if (redoStack.isEmpty()) return; undoStack.addLast(nodeStates.toMap()); restoreState(redoStack.removeLast()); updateUndoRedoMenuItems(); Toast.makeText(this, getString(R.string.toast_redo), Toast.LENGTH_SHORT).show() }

    private fun restoreState(snap: Map<Int, Mode>) {
        val changed = (nodeStates.keys + snap.keys).toSet()
            .filter { nodeStates[it] != snap[it] }
            .mapNotNull { k -> allNodes.find { it.index == k } }.toSet()
        nodeStates.clear(); nodeStates.putAll(snap); updateVisuals(changed); refreshButtonLabels()
    }
    private fun updateUndoRedoMenuItems() {
        undoMenuItem?.isEnabled = undoStack.isNotEmpty()
        redoMenuItem?.isEnabled = redoStack.isNotEmpty()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Reset / Bulk select
    // ════════════════════════════════════════════════════════════════════════

    private fun resetAll() {
        if (nodeStates.isEmpty()) { Toast.makeText(this, getString(R.string.toast_nothing_to_reset), Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_reset_title)).setMessage(getString(R.string.dialog_reset_message))
            .setPositiveButton(getString(R.string.btn_reset)) { _, _ ->
                pushUndoState()
                val changed = nodeStates.keys.mapNotNull { k -> allNodes.find { it.index == k } }.toSet()
                nodeStates.clear(); updateVisuals(changed); refreshButtonLabels()
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    private fun showSelectAllDialog(tagName: String) {
        if (currentMode == Mode.NONE) { Toast.makeText(this, getString(R.string.error_select_mode_first), Toast.LENGTH_SHORT).show(); return }
        val same = allNodes.filter { it.tagName.equals(tagName, ignoreCase = true) && !PROTECTED_TAGS.contains(it.tagName.lowercase()) }
        if (same.isEmpty()) return
        val allSet    = same.all { nodeStates[it.index] == currentMode }
        val modeLabel = if (currentMode == Mode.DELETE) getString(R.string.mode_delete) else getString(R.string.mode_deactivate)
        val msg = if (allSet) getString(R.string.dialog_bulk_deselect_msg, same.size, tagName)
        else getString(R.string.dialog_bulk_select_msg, modeLabel, same.size, tagName)
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_bulk_select_title, tagName))
            .setMessage(msg)
            .setPositiveButton(if (allSet) getString(R.string.btn_deselect_all) else getString(R.string.btn_select_all)) { _, _ ->
                pushUndoState()
                val changed = mutableSetOf<XmlNode>(); val mode: Mode? = if (allSet) null else currentMode
                same.forEach { applyModeToNodeAndChildren(it, mode, changed) }
                updateVisuals(changed); refreshButtonLabels()
                val resLabel = when(mode) {
                    Mode.DELETE     -> getString(R.string.toast_marked_deletion)
                    Mode.DEACTIVATE -> getString(R.string.toast_marked_deactivation)
                    else            -> getString(R.string.toast_deselected)
                }
                Toast.makeText(this, getString(R.string.toast_bulk_result, resLabel, same.size, tagName), Toast.LENGTH_SHORT).show()
            }.setNegativeButton(getString(R.string.btn_cancel), null).show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Touch listener
    // ════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        var isScrolling = false
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { isScrolling = false; return true }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!isScrolling) try { handleTap(e, false) } catch (ex: Exception) { Log.e(TAG, "tap", ex) }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!isScrolling) runOnUiThread { try { handleTap(e, true) } catch (ex: Exception) { Log.e(TAG, "lp", ex) } }
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                isScrolling = true; xmlTextView.parent?.requestDisallowInterceptTouchEvent(false); return false
            }
        })
        xmlTextView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Cancel any pending span batch immediately.
                    // Without this, ongoing setSpan() calls accumulate dirty flags;
                    // handleTap() → updateVisuals() → setSpan() then forces a full
                    // makeNewLayout() over all existing spans → freeze on large files.
                    spanApplier.cancel()
                    viewportRunnable?.let { viewportHandler.removeCallbacks(it) }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            gd.onTouchEvent(event); true
        }
    }

    private fun handleTap(event: MotionEvent, longPress: Boolean) {
        if (!isParsed) return
        if (currentMode == Mode.NONE && !longPress) return
        val layout = xmlTextView.layout ?: return
        val x = event.x.toInt() - xmlTextView.totalPaddingLeft
        val y = event.y.toInt() - xmlTextView.totalPaddingTop
        if (layout.lineCount == 0) return
        val clampedY = y.coerceIn(0, layout.getLineBottom(layout.lineCount - 1))
        val line     = layout.getLineForVertical(clampedY)
        if (line < 0 || line >= layout.lineCount) return
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        if (x > 0 && x > layout.getLineWidth(line)) return
        val candidates = allNodes.filter { it.start >= 0 && it.end > it.start && offset >= it.start && offset <= it.end }
        if (candidates.isEmpty()) return
        val target = candidates.minByOrNull { it.length() } ?: return
        if (PROTECTED_TAGS.contains(target.tagName.lowercase())) return
        if (longPress) showSelectAllDialog(target.tagName)
        else { pushUndoState(); onNodeClicked(target); refreshButtonLabels() }
    }

    // ════════════════════════════════════════════════════════════════════════
    // XML Parsing
    // ════════════════════════════════════════════════════════════════════════

    private fun parseNodes(source: String) {
        allNodes.clear()
        val tagPat  = Pattern.compile("<(/)?([a-zA-Z0-9_\\-:]+)((?:\\s[^>]*?)?)(/)?>", Pattern.DOTALL)
        val namePat = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"")
        val matcher = tagPat.matcher(source)
        val stack   = Stack<XmlNode>(); var counter = 0

        while (matcher.find()) {
            val isClose = matcher.group(1) == "/"
            val tag     = matcher.group(2) ?: continue
            val attrs   = matcher.group(3) ?: ""
            val isSelf  = matcher.group(4) == "/"
            val nm      = namePat.matcher(attrs)
            val aName   = if (nm.find()) nm.group(1) else null

            if (isClose) {
                val idx = (0 until stack.size).lastOrNull { stack[it].tagName == tag }
                if (idx != null) { while (stack.size > idx + 1) stack.pop(); stack.pop().end = matcher.end() }
            } else {
                val node = XmlNode(counter++, tag, matcher.start(), matcher.end(), null, mutableListOf(), aName)
                if (stack.isNotEmpty()) { val p = stack.peek(); node.parent = p; p.children.add(node) }
                allNodes.add(node); if (!isSelf) stack.push(node)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Node visuals
    // ════════════════════════════════════════════════════════════════════════

    private fun onNodeClicked(node: XmlNode) {
        val mode = if (nodeStates[node.index] == currentMode) null else currentMode
        val changed = mutableSetOf<XmlNode>()
        applyModeToNodeAndChildren(node, mode, changed); updateParentState(node.parent, changed); updateVisuals(changed)
    }

    private fun applyModeToNodeAndChildren(node: XmlNode, mode: Mode?, s: MutableSet<XmlNode>) {
        if (nodeStates[node.index] != mode) {
            if (mode == null) nodeStates.remove(node.index) else nodeStates[node.index] = mode; s.add(node)
        }
        node.children.forEach { applyModeToNodeAndChildren(it, mode, s) }
    }

    private fun updateParentState(parent: XmlNode?, changed: MutableSet<XmlNode>) {
        if (parent == null || parent.children.isEmpty()) return
        val first = nodeStates[parent.children[0].index]
        val same  = parent.children.all { nodeStates[it.index] == first }
        val new   = if (same && first != null) first else null
        if (nodeStates[parent.index] != new) {
            if (new == null) nodeStates.remove(parent.index) else nodeStates[parent.index] = new
            changed.add(parent); updateParentState(parent.parent, changed)
        }
    }

    private fun updateVisuals(nodes: Set<XmlNode>) {
        try {
            val sp = xmlTextView.text as? Spannable ?: return; val len = sp.length
            for (node in nodes) {
                activeSpans.remove(node.index)?.let { try { sp.removeSpan(it) } catch (_: Exception) {} }
                val mode = nodeStates[node.index] ?: continue
                val s = node.start.coerceIn(0, len); val e = node.end.coerceIn(s, len)
                if (s >= e) continue
                val span = BackgroundColorSpan(if (mode == Mode.DELETE) COLOR_DELETE else COLOR_DEACTIVATE)
                try { sp.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); activeSpans[node.index] = span }
                catch (ex: Exception) { Log.e(TAG, "setSpan $s..$e len=$len", ex) }
            }
        } catch (ex: Exception) { Log.e(TAG, "updateVisuals", ex) }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Summary / Apply
    // ════════════════════════════════════════════════════════════════════════

    private fun computeOccurrenceMap(): Map<Int, Int> {
        val c = mutableMapOf<String, Int>(); val r = mutableMapOf<Int, Int>()
        allNodes.sortedBy { it.index }.forEach { node ->
            val i = c.getOrDefault(node.tagName, 0); r[node.index] = i; c[node.tagName] = i + 1
        }
        return r
    }

    private fun visibleDepth(node: XmlNode): Int {
        var d = 0; var c = node.parent
        while (c != null) { if (!PROTECTED_TAGS.contains(c.tagName.lowercase())) d++; c = c.parent }
        return d
    }

    private fun buildHierarchicalSummary(nodes: List<XmlNode>, om: Map<Int, Int>): String {
        val sb = StringBuilder()
        nodes.sortedBy { it.start }.forEach { n ->
            val d = visibleDepth(n)
            sb.append("${"    ".repeat(d)}${if (d > 0) "└─ " else ""}<${n.tagName}>")
            n.androidName?.substringAfterLast('.')?.let { if (it.isNotEmpty()) sb.append("  $it") }
            sb.append("  [#${om[n.index] ?: 0}]\n")
        }
        return sb.toString()
    }

    private fun showSummaryDialog() {
        if (nodeStates.isEmpty()) { Toast.makeText(this, getString(R.string.error_no_elements_selected), Toast.LENGTH_SHORT).show(); return }
        val om  = computeOccurrenceMap()
        val del = allNodes.filter { nodeStates[it.index] == Mode.DELETE }.sortedBy { it.start }
        val dis = allNodes.filter { nodeStates[it.index] == Mode.DEACTIVATE }.sortedBy { it.start }
        val sb  = StringBuilder()
        if (del.isNotEmpty()) { sb.appendLine(getString(R.string.label_to_delete, del.size)); sb.appendLine("─────────────────────────"); sb.append(buildHierarchicalSummary(del, om)) }
        if (dis.isNotEmpty()) { if (sb.isNotEmpty()) sb.appendLine(); sb.appendLine(getString(R.string.label_to_disable, dis.size)); sb.appendLine("─────────────────────────"); sb.append(buildHierarchicalSummary(dis, om)) }
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_summary_title)).setMessage(sb.toString())
            .setPositiveButton(getString(R.string.btn_apply_now)) { _, _ -> applyPatch() }
            .setNegativeButton(getString(R.string.btn_keep_editing), null).show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Menu
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_custom_patch, menu)
        undoMenuItem = menu?.findItem(R.id.action_undo)
        redoMenuItem = menu?.findItem(R.id.action_redo)
        updateUndoRedoMenuItems(); return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home                      -> { finish(); true }
        R.id.action_undo                       -> { undo(); true }
        R.id.action_redo                       -> { redo(); true }
        R.id.action_reset                      -> { resetAll(); true }
        R.id.action_summary, R.id.action_apply -> { showSummaryDialog(); true }
        else                                   -> super.onOptionsItemSelected(item)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Apply patch
    // ════════════════════════════════════════════════════════════════════════

    private fun applyPatch() {
        if (nodeStates.isEmpty()) { Toast.makeText(this, getString(R.string.error_no_elements_selected), Toast.LENGTH_SHORT).show(); return }
        val targets  = mutableListOf<PatchTarget>()
        val counters = mutableMapOf<String, Int>()
        allNodes.sortedBy { it.index }.forEach { node ->
            val idx = counters.getOrDefault(node.tagName, 0)
            if (nodeStates.containsKey(node.index)) targets.add(
                PatchTarget(
                    type            = if (nodeStates[node.index] == Mode.DELETE) ActionType.DELETE else ActionType.DISABLE,
                    tagName         = node.tagName,
                    androidName     = node.androidName,
                    parentName      = node.parent?.tagName,
                    occurrenceIndex = idx
                )
            )
            counters[node.tagName] = idx + 1
        }
        setResult(Activity.RESULT_OK, Intent().also { it.putExtra("PATCH_DATA", CustomPatchData(targets)) })
        finish()
    }
}