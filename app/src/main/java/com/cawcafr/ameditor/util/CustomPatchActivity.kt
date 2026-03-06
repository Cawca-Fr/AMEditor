package com.cawcafr.ameditor.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.style.BackgroundColorSpan
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
import androidx.core.view.WindowCompat
import com.cawcafr.ameditor.R
import java.util.Stack
import java.util.regex.Pattern

class CustomPatchActivity : AppCompatActivity() {

    private lateinit var xmlTextView: TextView
    private lateinit var btnDelete: com.google.android.material.button.MaterialButton
    private lateinit var btnDeactivate: com.google.android.material.button.MaterialButton
    private lateinit var xmlScrollView: androidx.core.widget.NestedScrollView
    private lateinit var scrollbarThumb: android.view.View

    private var minThumbPx = 0
    // Dernier handler pour le fade-out auto du scrollbar
    private val fadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        scrollbarThumb.animate().alpha(0f).setDuration(600).start()
    }
    private var currentMode = Mode.DELETE
    private var xmlContent = ""

    // ── FIX HASHCODE ────────────────────────────────────────────────────────
    // On parse les positions depuis le texte AFFICHÉ (après highlight),
    // pas depuis xmlContent brut. Les deux peuvent différer si le
    // highlighter modifie des caractères (newlines, espaces, etc.).
    private var displayedText = ""

    // Garde-fou : taps ignorés tant que le parsing n'est pas terminé
    @Volatile private var isParsed = false

    private val allNodes   = mutableListOf<XmlNode>()
    private val nodeStates = mutableMapOf<Int, Mode>()
    private val activeSpans = mutableMapOf<Int, BackgroundColorSpan>()

    // ── Undo / Redo ─────────────────────────────────────────────────────────
    private val undoStack = ArrayDeque<Map<Int, Mode>>()
    private val redoStack = ArrayDeque<Map<Int, Mode>>()
    private var undoMenuItem: MenuItem? = null
    private var redoMenuItem: MenuItem? = null

    companion object {
        private const val TAG     = "CustomPatchActivity"
        private const val MAX_UNDO = 30
        private val PROTECTED_TAGS   = setOf("manifest", "application")
        private val COLOR_DELETE     = 0x40D32F2F.toInt()
        private val COLOR_DEACTIVATE = 0x40FBC02D.toInt()
    }

    enum class Mode { DELETE, DEACTIVATE, NONE }

    data class XmlNode(
        val index: Int,
        val tagName: String,
        val start: Int,
        var end: Int,
        var parent: XmlNode? = null,
        val children: MutableList<XmlNode> = mutableListOf(),
        var androidName: String? = null
    ) {
        fun length(): Int = if (end > start) end - start else 0

        // FIX HASHCODE : on utilise uniquement 'index' (unique par nœud).
        // Sans ça, le hashCode() auto-généré par data class parcourt
        // children → leurs enfants → leurs parents → récursion infinie → StackOverflow.
        override fun hashCode(): Int = index

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is XmlNode) return false
            return index == other.index
        }

        // toString() minimal pour éviter le même problème si un nœud est loggué
        override fun toString(): String = "XmlNode(#$index <$tagName> $start..$end)"
    }

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Laisse l'AppBarLayout (fitsSystemWindows=true) gérer le padding
        // de la status bar. Nécessaire pour éviter le conflit toolbar/notifs.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_custom_patch)

        setupToolbar()
        setupViews()
        setupButtons()
        setupTouchListener()

        xmlContent = intent.getStringExtra("XML_CONTENT") ?: ""
        if (xmlContent.isEmpty()) {
            Toast.makeText(this, "No XML content received", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        xmlTextView.text = "Parsing XML structure…"
        Thread { parseAndRender() }.start()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Setup
    // ════════════════════════════════════════════════════════════════════════

    private fun setupToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    private fun setupViews() {
        xmlTextView       = findViewById(R.id.xmlTextView)
        xmlTextView.highlightColor = android.graphics.Color.TRANSPARENT
        btnDelete         = findViewById(R.id.btnModeDelete)
        btnDeactivate     = findViewById(R.id.btnModeDeactivate)
        xmlScrollView     = findViewById(R.id.xmlScrollView)
        scrollbarThumb    = findViewById(R.id.scrollbarThumb)

        // 56dp → px pour la hauteur minimale garantie
        minThumbPx = (56 * resources.displayMetrics.density).toInt()

        setupCustomScrollbar()
        refreshButtonLabels()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCustomScrollbar() {

        // ── Mise à jour du thumb au scroll ───────────────────────────────────
        xmlScrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                updateThumbPosition(scrollY)
                showThumb()
                scheduleFade()
            }
        )

        // ── Couleur au toucher ────────────────────────────────────────────────
        scrollbarThumb.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Violet au toucher
                    (v.background as? android.graphics.drawable.GradientDrawable)
                        ?.setColor(android.graphics.Color.parseColor("#6200EE"))
                    // Annule le fade pendant qu'on tient le thumb
                    fadeHandler.removeCallbacks(fadeRunnable)
                    v.alpha = 1f
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // Retour gris
                    (v.background as? android.graphics.drawable.GradientDrawable)
                        ?.setColor(android.graphics.Color.parseColor("#66AAAAAA"))
                    scheduleFade()
                }
            }
            // On ne consomme PAS l'événement ici car le drag du thumb
            // n'est pas implémenté (scroll via swipe sur le contenu).
            false
        }

        // ── Position initiale une fois le layout mesuré ───────────────────────
        xmlScrollView.post {
            updateThumbPosition(xmlScrollView.scrollY)
            // Caché par défaut, apparaît au premier scroll
            scrollbarThumb.alpha = 0f
            scrollbarThumb.visibility = android.view.View.VISIBLE
        }
    }

    private fun updateThumbPosition(scrollY: Int) {
        val parent = scrollbarThumb.parent as? android.view.View ?: return

        // Hauteur totale scrollable du contenu
        val totalHeight   = xmlScrollView.getChildAt(0)?.height ?: return
        val visibleHeight = xmlScrollView.height
        if (totalHeight <= visibleHeight) {
            // Pas besoin de scrollbar si tout le contenu est visible
            scrollbarThumb.visibility = android.view.View.INVISIBLE
            return
        }
        scrollbarThumb.visibility = android.view.View.VISIBLE

        // Zone disponible pour le thumb (hauteur du track - marges)
        val trackHeight = parent.height - 8 // 4dp marge top + 4dp marge bottom

        // Hauteur du thumb : proportionnelle, avec minimum garanti
        val rawThumbHeight = (visibleHeight.toFloat() / totalHeight * trackHeight).toInt()
        val thumbHeight    = rawThumbHeight.coerceAtLeast(minThumbPx)

        // Position Y du thumb dans le track
        val scrollRatio = scrollY.toFloat() / (totalHeight - visibleHeight)
        val thumbTop    = (scrollRatio * (trackHeight - thumbHeight) + 4).toInt()
            .coerceIn(4, trackHeight - thumbHeight + 4)

        // Mise à jour layout si la hauteur change
        if (scrollbarThumb.height != thumbHeight) {
            val lp = scrollbarThumb.layoutParams
            lp.height = thumbHeight
            scrollbarThumb.layoutParams = lp
        }

        scrollbarThumb.translationY = thumbTop.toFloat()
    }

    private fun showThumb() {
        scrollbarThumb.animate().cancel()
        scrollbarThumb.alpha = 1f
    }

    private fun scheduleFade() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, 1500)
    }

    private fun refreshButtonLabels() {
        val deleteCount  = nodeStates.values.count { it == Mode.DELETE }
        val disableCount = nodeStates.values.count { it == Mode.DEACTIVATE }

        btnDelete.text = if (deleteCount > 0) "🔴  Delete  ·  $deleteCount"
        else "🔴  Delete"

        btnDeactivate.text = if (disableCount > 0) "🟡  Deactivate  ·  $disableCount"
        else "🟡  Deactivate"
    }

    // ════════════════════════════════════════════════════════════════════════
    // Undo / Redo
    // ════════════════════════════════════════════════════════════════════════

    private fun pushUndoState() {
        undoStack.addLast(nodeStates.toMap())
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        updateUndoRedoMenuItems()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(nodeStates.toMap())
        restoreState(undoStack.removeLast())
        updateUndoRedoMenuItems()
        Toast.makeText(this, "Undo", Toast.LENGTH_SHORT).show()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(nodeStates.toMap())
        restoreState(redoStack.removeLast())
        updateUndoRedoMenuItems()
        Toast.makeText(this, "Redo", Toast.LENGTH_SHORT).show()
    }

    private fun restoreState(snapshot: Map<Int, Mode>) {
        val allKeys = (nodeStates.keys + snapshot.keys).toSet()
        val changedNodes = allKeys
            .filter { nodeStates[it] != snapshot[it] }
            .mapNotNull { key -> allNodes.find { it.index == key } }
            .toSet()
        nodeStates.clear()
        nodeStates.putAll(snapshot)
        updateVisuals(changedNodes)
        refreshButtonLabels()
    }

    private fun updateUndoRedoMenuItems() {
        undoMenuItem?.isEnabled = undoStack.isNotEmpty()
        redoMenuItem?.isEnabled = redoStack.isNotEmpty()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Reset all
    // ════════════════════════════════════════════════════════════════════════

    private fun resetAll() {
        if (nodeStates.isEmpty()) {
            Toast.makeText(this, "Nothing to reset", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Reset selection")
            .setMessage("Clear all selected elements?")
            .setPositiveButton("Reset") { _, _ ->
                pushUndoState()
                val changed = nodeStates.keys
                    .mapNotNull { key -> allNodes.find { it.index == key } }.toSet()
                nodeStates.clear()
                updateVisuals(changed)
                refreshButtonLabels()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Long press → Bulk select (même type de tag)
    // ════════════════════════════════════════════════════════════════════════

    private fun showSelectAllDialog(tagName: String) {
        if (currentMode == Mode.NONE) {
            Toast.makeText(this, "Select a mode first (🔴 or 🟡)", Toast.LENGTH_SHORT).show()
            return
        }
        val sameTagNodes = allNodes.filter {
            it.tagName.equals(tagName, ignoreCase = true) &&
                    !PROTECTED_TAGS.contains(it.tagName.lowercase())
        }
        if (sameTagNodes.isEmpty()) return

        val allAlreadySet = sameTagNodes.all { nodeStates[it.index] == currentMode }
        val modeLabel     = if (currentMode == Mode.DELETE) "delete" else "deactivate"

        AlertDialog.Builder(this)
            .setTitle("Bulk select: <$tagName>")
            .setMessage(
                if (allAlreadySet) "Deselect all ${sameTagNodes.size} <$tagName>?"
                else "Apply '$modeLabel' to all ${sameTagNodes.size} <$tagName>?"
            )
            .setPositiveButton(if (allAlreadySet) "Deselect all" else "Select all") { _, _ ->
                pushUndoState()
                val changedNodes = mutableSetOf<XmlNode>()
                val newMode: Mode? = if (allAlreadySet) null else currentMode
                for (node in sameTagNodes) applyModeToNodeAndChildren(node, newMode, changedNodes)
                updateVisuals(changedNodes)
                refreshButtonLabels()
                val action = when (newMode) {
                    Mode.DELETE     -> "Marked for deletion"
                    Mode.DEACTIVATE -> "Marked for deactivation"
                    else            -> "Deselected"
                }
                Toast.makeText(this, "$action: ${sameTagNodes.size} <$tagName>", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Touch system
    //
    // FIX LONG PRESS :
    // Le ScrollView parent intercepte ACTION_MOVE, annulant le timer long press
    // avant 500ms. Fix : on bloque l'interception sur ACTION_DOWN, et on la
    // relâche dès qu'un scroll est détecté (onScroll callback).
    //
    // FIX CRASH SUR TAP :
    // - Tout le code dans handleTap est wrappé dans try/catch
    // - On vérifie layout != null avant chaque accès
    // - On vérifie que isParsed == true avant de traiter le tap
    // - Toutes les opérations sur Spannable sont sécurisées (coerceIn)
    // ════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        var isScrolling = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {

            // Doit retourner true pour que les gestes suivants soient reçus
            override fun onDown(e: MotionEvent): Boolean {
                isScrolling = false
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!isScrolling) {
                    try { handleTap(e, longPress = false) }
                    catch (ex: Exception) { Log.e(TAG, "Tap error", ex) }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isScrolling) {
                    // runOnUiThread car onLongPress est parfois appelé
                    // depuis un handler interne du GestureDetector
                    runOnUiThread {
                        try { handleTap(e, longPress = true) }
                        catch (ex: Exception) { Log.e(TAG, "Long press error", ex) }
                    }
                }
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // L'utilisateur scrolle → relâche le verrou d'interception
                isScrolling = true
                xmlTextView.parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }
        })

        xmlTextView.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Bloque l'interception parent pendant la détection du long press
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // Relâche dans tous les cas pour ne pas bloquer le scroll ultérieur
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            gestureDetector.onTouchEvent(event)
            // true = on consomme l'événement (évite la sélection de texte native)
            true
        }
    }

    private fun handleTap(event: MotionEvent, longPress: Boolean) {
        if (!isParsed) return
        if (currentMode == Mode.NONE && !longPress) return

        // ── 1. Calcul de l'offset dans le texte ─────────────────────────────
        val layout = xmlTextView.layout ?: return  // pas encore mesuré

        val x = event.x.toInt() - xmlTextView.totalPaddingLeft
        val y = event.y.toInt() - xmlTextView.totalPaddingTop

        if (layout.lineCount == 0) return

        // Clamp y pour éviter les out-of-bounds au bord du TextView
        val maxY    = layout.getLineBottom(layout.lineCount - 1)
        val clampedY = y.coerceIn(0, maxY)

        val line = layout.getLineForVertical(clampedY)
        if (line < 0 || line >= layout.lineCount) return

        val offset = layout.getOffsetForHorizontal(line, x.toFloat())

        // Ignore si le tap est à droite de la dernière ligne (zone vide)
        if (x > 0 && x > layout.getLineWidth(line)) return

        // ── 2. Trouver le nœud cible ─────────────────────────────────────────
        // On filtre les nœuds dont les bornes encadrent l'offset
        val candidates = allNodes.filter { node ->
            node.start >= 0 && node.end > node.start &&
                    offset >= node.start && offset <= node.end
        }
        if (candidates.isEmpty()) return

        // Le plus précis = le plus petit (l'enfant le plus profond)
        val targetNode = candidates.minByOrNull { it.length() } ?: return

        // Protection des racines
        if (PROTECTED_TAGS.contains(targetNode.tagName.lowercase())) return

        // ── 3. Action ────────────────────────────────────────────────────────
        if (longPress) {
            showSelectAllDialog(targetNode.tagName)
        } else {
            pushUndoState()
            onNodeClicked(targetNode)
            refreshButtonLabels()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // XML Parsing — FIX HASHCODE
    //
    // On parse dans highlighted.toString() (texte exact du TextView),
    // pas dans xmlContent. Garanti que les positions des nœuds correspondent
    // au texte affiché, même si le highlighter transforme des caractères.
    // ════════════════════════════════════════════════════════════════════════

    private fun parseAndRender() {
        try {
            // 1. Coloration syntaxique
            val highlighted = XmlSyntaxHighlighter.highlight(xmlContent)

            // 2. FIX CRITIQUE : parser depuis le texte affiché
            displayedText = highlighted.toString()
            parseNodes(displayedText)

            isParsed = true

            runOnUiThread {
                xmlTextView.setText(highlighted, TextView.BufferType.SPANNABLE)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            runOnUiThread {
                xmlTextView.text = "Parse error: ${e.message}"
                Toast.makeText(this, "Failed to parse XML", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseNodes(source: String) {
        allNodes.clear()

        val tagPattern  = Pattern.compile(
            "<(/)?([a-zA-Z0-9_\\-:]+)((?:\\s[^>]*?)?)(/)?>",
            Pattern.DOTALL
        )
        val namePattern = Pattern.compile("android:name\\s*=\\s*\"([^\"]+)\"")
        val matcher     = tagPattern.matcher(source)
        val stack       = Stack<XmlNode>()
        var nodeCounter = 0

        while (matcher.find()) {
            val isClosingTag  = matcher.group(1) == "/"
            val tagName       = matcher.group(2) ?: continue
            val attributes    = matcher.group(3) ?: ""
            val isSelfClosing = matcher.group(4) == "/"
            val startPos      = matcher.start()
            val endPos        = matcher.end()

            val nameMatcher = namePattern.matcher(attributes)
            val androidName = if (nameMatcher.find()) nameMatcher.group(1) else null

            if (isClosingTag) {
                // Recherche dans la pile pour tolérer le XML légèrement malformé
                val stackIdx = (0 until stack.size).lastOrNull { stack[it].tagName == tagName }
                if (stackIdx != null) {
                    while (stack.size > stackIdx + 1) stack.pop()
                    stack.pop().end = endPos
                }
            } else {
                val newNode = XmlNode(
                    index       = nodeCounter++,
                    tagName     = tagName,
                    start       = startPos,
                    end         = endPos,
                    parent      = null,
                    children    = mutableListOf(),
                    androidName = androidName
                )
                if (stack.isNotEmpty()) {
                    val parent = stack.peek()
                    newNode.parent = parent
                    parent.children.add(newNode)
                }
                allNodes.add(newNode)
                if (!isSelfClosing) stack.push(newNode)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Node click logic
    // ════════════════════════════════════════════════════════════════════════

    private fun onNodeClicked(node: XmlNode) {
        val newMode = if (nodeStates[node.index] == currentMode) null else currentMode
        val changedNodes = mutableSetOf<XmlNode>()
        applyModeToNodeAndChildren(node, newMode, changedNodes)
        updateParentState(node.parent, changedNodes)
        updateVisuals(changedNodes)
    }

    private fun applyModeToNodeAndChildren(node: XmlNode, mode: Mode?, changedSet: MutableSet<XmlNode>) {
        val oldMode = nodeStates[node.index]
        if (oldMode != mode) {
            if (mode == null) nodeStates.remove(node.index) else nodeStates[node.index] = mode
            changedSet.add(node)
        }
        for (child in node.children) applyModeToNodeAndChildren(child, mode, changedSet)
    }

    private fun updateParentState(parent: XmlNode?, changedSet: MutableSet<XmlNode>) {
        if (parent == null || parent.children.isEmpty()) return
        val firstChildState = nodeStates[parent.children[0].index]
        val allSame         = parent.children.all { nodeStates[it.index] == firstChildState }
        val newParentState  = if (allSame && firstChildState != null) firstChildState else null
        val oldParentState  = nodeStates[parent.index]
        if (oldParentState != newParentState) {
            if (newParentState == null) nodeStates.remove(parent.index)
            else nodeStates[parent.index] = newParentState
            changedSet.add(parent)
            updateParentState(parent.parent, changedSet)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Visual update — FIX CRASH
    //
    // - Tout est dans un try/catch global
    // - coerceIn sur start/end pour éviter StringIndexOutOfBoundsException
    // - Vérification start < end avant setSpan
    // - Accès au Spannable uniquement sur le main thread
    // ════════════════════════════════════════════════════════════════════════

    private fun updateVisuals(nodesToUpdate: Set<XmlNode>) {
        try {
            val spannable = xmlTextView.text as? Spannable ?: return
            val len = spannable.length

            for (node in nodesToUpdate) {
                // Supprime l'ancien span via référence directe → O(1)
                activeSpans.remove(node.index)?.let {
                    try { spannable.removeSpan(it) } catch (_: Exception) {}
                }

                val mode = nodeStates[node.index] ?: continue

                // Bornes sécurisées — évite StringIndexOutOfBoundsException
                val safeStart = node.start.coerceIn(0, len)
                val safeEnd   = node.end.coerceIn(safeStart, len)
                if (safeStart >= safeEnd) continue

                val color   = if (mode == Mode.DELETE) COLOR_DELETE else COLOR_DEACTIVATE
                val newSpan = BackgroundColorSpan(color)

                try {
                    spannable.setSpan(newSpan, safeStart, safeEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    activeSpans[node.index] = newSpan
                } catch (ex: Exception) {
                    Log.e(TAG, "setSpan failed for node ${node.index}: $safeStart..$safeEnd / len=$len", ex)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "updateVisuals error", ex)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Summary avec hiérarchie
    // ════════════════════════════════════════════════════════════════════════

    private fun computeOccurrenceMap(): Map<Int, Int> {
        val tagCounters = mutableMapOf<String, Int>()
        val result      = mutableMapOf<Int, Int>()
        allNodes.sortedBy { it.index }.forEach { node ->
            val tag          = node.tagName
            val currentIndex = tagCounters.getOrDefault(tag, 0)
            result[node.index] = currentIndex
            tagCounters[tag]   = currentIndex + 1
        }
        return result
    }

    private fun visibleDepth(node: XmlNode): Int {
        var depth = 0
        var current = node.parent
        while (current != null) {
            if (!PROTECTED_TAGS.contains(current.tagName.lowercase())) depth++
            current = current.parent
        }
        return depth
    }

    private fun buildHierarchicalSummary(nodes: List<XmlNode>, occurrenceMap: Map<Int, Int>): String {
        val sb = StringBuilder()
        for (node in nodes.sortedBy { it.start }) {
            val depth  = visibleDepth(node)
            val indent = "    ".repeat(depth)
            val prefix = if (depth > 0) "└─ " else ""
            val name   = node.androidName?.substringAfterLast('.') ?: ""
            val idx    = occurrenceMap[node.index] ?: 0

            sb.append("$indent$prefix<${node.tagName}>")
            if (name.isNotEmpty()) sb.append("  $name")
            sb.append("  [#$idx]\n")
        }
        return sb.toString()
    }

    private fun showSummaryDialog() {
        if (nodeStates.isEmpty()) {
            Toast.makeText(this, "No elements selected", Toast.LENGTH_SHORT).show()
            return
        }
        val occurrenceMap = computeOccurrenceMap()
        val toDelete  = allNodes.filter { nodeStates[it.index] == Mode.DELETE }.sortedBy { it.start }
        val toDisable = allNodes.filter { nodeStates[it.index] == Mode.DEACTIVATE }.sortedBy { it.start }
        val sb = StringBuilder()

        if (toDelete.isNotEmpty()) {
            sb.appendLine("🔴  TO DELETE  (${toDelete.size})")
            sb.appendLine("─────────────────────────")
            sb.append(buildHierarchicalSummary(toDelete, occurrenceMap))
        }
        if (toDisable.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("🟡  TO DISABLE  (${toDisable.size})")
            sb.appendLine("─────────────────────────")
            sb.append(buildHierarchicalSummary(toDisable, occurrenceMap))
        }

        AlertDialog.Builder(this)
            .setTitle("Patch Summary")
            .setMessage(sb.toString())
            .setPositiveButton("Apply now") { _, _ -> applyPatch() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Buttons & Menu
    // ════════════════════════════════════════════════════════════════════════

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
        btnDelete.setTextColor("#D32F2F".toColorInt())
        btnDeactivate.setBackgroundColor(Color.TRANSPARENT)
        btnDeactivate.setTextColor("#F9A825".toColorInt())

        // Actif = fond coloré
        when (currentMode) {
            Mode.DELETE     -> btnDelete.setBackgroundColor("#FFCDD2".toColorInt())
            Mode.DEACTIVATE -> btnDeactivate.setBackgroundColor("#FFF9C4".toColorInt())
            else            -> {}
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_custom_patch, menu)
        undoMenuItem = menu?.findItem(R.id.action_undo)
        redoMenuItem = menu?.findItem(R.id.action_redo)
        updateUndoRedoMenuItems()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home   -> { finish(); true }
            R.id.action_undo    -> { undo(); true }
            R.id.action_redo    -> { redo(); true }
            R.id.action_reset   -> { resetAll(); true }
            R.id.action_summary -> { showSummaryDialog(); true }
            R.id.action_apply   -> { showSummaryDialog(); true }
            else                -> super.onOptionsItemSelected(item)
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Apply patch
    // ════════════════════════════════════════════════════════════════════════

    private fun applyPatch() {
        if (nodeStates.isEmpty()) {
            Toast.makeText(this, "No elements selected", Toast.LENGTH_SHORT).show()
            return
        }
        val targets     = mutableListOf<PatchTarget>()
        val tagCounters = mutableMapOf<String, Int>()

        allNodes.sortedBy { it.index }.forEach { node ->
            val tagName      = node.tagName
            val currentIndex = tagCounters.getOrDefault(tagName, 0)
            if (nodeStates.containsKey(node.index)) {
                val actionType = if (nodeStates[node.index] == Mode.DELETE) ActionType.DELETE else ActionType.DISABLE
                targets.add(PatchTarget(
                    type            = actionType,
                    tagName         = tagName,
                    androidName     = node.androidName,
                    parentName      = node.parent?.tagName,
                    occurrenceIndex = currentIndex
                ))
            }
            tagCounters[tagName] = currentIndex + 1
        }

        val intent = Intent()
        intent.putExtra("PATCH_DATA", CustomPatchData(targets))
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}