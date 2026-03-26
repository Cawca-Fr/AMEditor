package com.cawcafr.ameditor.util

import android.os.Handler
import android.os.Looper
import android.text.Spannable

/**
 * Applies syntax-highlight spans to a [Spannable] in micro-batches so that
 * the main thread is never blocked for more than one frame.
 *
 * PROBLEM SOLVED
 * --------------
 * Calling setSpan() hundreds of times in a single Runnable on the main thread
 * forces Android to re-measure/re-layout the whole TextView synchronously,
 * which freezes the UI for several hundred milliseconds after each scroll stop.
 *
 * SOLUTION
 * --------
 * 1.  evictSpans()  – remove spans that have scrolled far out of the viewport.
 *                     This is a small, bounded set → done immediately.
 * 2.  startBatch()  – queue the first micro-batch.  Each batch applies at most
 *                     [BATCH_SIZE] spans then posts the next batch via
 *                     Handler.post() (i.e., next message-queue slot ≈ next frame).
 *                     The main thread stays free between batches → scroll events
 *                     are processed without delay.
 * 3.  cancel()      – called at the start of every new scroll event so the old
 *                     batch (for a position the user has already left) is dropped
 *                     and a fresh one starts for the current position.
 *
 **/

class ViewportSpanApplier {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    /** Spans applied per Handler.post() call. 60 ≈ one frame at 60 fps. */
    private val BATCH_SIZE = 60

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Cancels any in-progress batch.  Call this at the start of each new
     * viewport calculation so stale work for the old scroll position is dropped.
     */
    fun cancel() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
    }

    /**
     * Removes spans that are farther than [evictEnd] or earlier than [evictStart]
     * from the current viewport.  Done immediately (small set, bounded by the
     * eviction window size).
     */
    fun evictSpans(
        target:       Spannable,
        snapshot:     List<SpanRecord>,
        appliedSpans: MutableMap<Int, Any>,
        evictStart:   Int,
        evictEnd:     Int
    ) {
        val toRemove = appliedSpans.keys.filter { i ->
            i >= snapshot.size ||
                    snapshot[i].end < evictStart ||
                    snapshot[i].start > evictEnd
        }
        toRemove.forEach { i ->
            try { target.removeSpan(appliedSpans[i]!!) } catch (_: Exception) {}
            appliedSpans.remove(i)
        }
    }

    /**
     * Schedules batched application of [toApply] span indices.
     * Returns immediately; actual setSpan() calls happen across future frames.
     */
    fun startBatch(
        target:       Spannable,
        snapshot:     List<SpanRecord>,
        appliedSpans: MutableMap<Int, Any>,
        toApply:      List<Int>
    ) {
        if (toApply.isEmpty()) return
        applyNextBatch(target, snapshot, appliedSpans, toApply, 0)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun applyNextBatch(
        target:       Spannable,
        snapshot:     List<SpanRecord>,
        appliedSpans: MutableMap<Int, Any>,
        toApply:      List<Int>,
        offset:       Int
    ) {
        val len = target.length
        val end = minOf(offset + BATCH_SIZE, toApply.size)

        for (pos in offset until end) {
            val i    = toApply[pos]
            if (appliedSpans.containsKey(i)) continue  // already applied by a parallel path
            if (i >= snapshot.size) continue
            val info = snapshot[i]
            if (info.end > len) continue
            try {
                target.setSpan(info.span, info.start, info.end, info.flags)
                appliedSpans[i] = info.span
            } catch (_: Exception) {}
        }

        if (end < toApply.size) {
            // Post next batch — yields to the message queue so scroll/touch events
            // can be processed before we continue.
            val r = Runnable { applyNextBatch(target, snapshot, appliedSpans, toApply, end) }
            pendingRunnable = r
            handler.post(r)
        } else {
            pendingRunnable = null
        }
    }
}

/** Lightweight value type replacing the per-activity SpanInfo data class. */
data class SpanRecord(
    val span:  Any,
    val start: Int,
    val end:   Int,
    val flags: Int
)