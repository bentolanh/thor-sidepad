package dev.lbento.thorsidepad.pad

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView

/**
 * The panel's scroller, with the shade's own closing gesture.
 *
 * The panel arrives from the top like the notification shade, so it should leave the same way.
 * Once the list has run out, the next upward drag is not scrolling any more — there is nothing
 * left to scroll — and treating it as scrolling strands a finger that is plainly asking for the
 * panel to go away. Past the end, the drag moves the panel instead.
 *
 * Two things about this were wrong the first time, both invisible from reading it:
 *
 * The gesture was anchored on [ACTION_DOWN] arriving here. It usually does not. The panel is
 * full of buttons, so a touch that lands on one is delivered to that child; this view only
 * starts receiving events once the scroller decides to intercept, and interception hands over
 * the MOVE stream with no DOWN in front of it. The anchor was therefore stale on almost every
 * real touch. [onInterceptTouchEvent] is the one place a DOWN always arrives, so it is recorded
 * there as well.
 *
 * And the distance was measured from the start of the touch, which counts the scrolling that got
 * the list to its end. Only travel after the list has run out should count, so it is accumulated
 * step by step and reset the moment the finger scrolls or there is list left again.
 */
class PanelScrollView(ctx: Context) : ScrollView(ctx) {

    /** Finger past the end: [dy] is negative pixels, how far the panel should ride up. */
    var onDismissDrag: ((Float) -> Unit)? = null

    /** Finger lifted: true to put the panel away, false to settle it back open. */
    var onDismissEnd: ((Boolean) -> Unit)? = null

    /** How far past the end the finger must travel before the panel goes rather than springs back. */
    var dismissAfterPx: Float = 0f

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var lastY = 0f
    private var past = 0f          // travelled up with no list left
    private var baseY = 0f
    private var taking = false
    private var drag = 0f

    private fun begin(y: Float) { lastY = y; past = 0f; taking = false; drag = 0f }

    /**
     * Where the gesture is actually caught.
     *
     * Waiting for [ScrollView] to hand the touch over does not work, because it declines to
     * intercept anything when there is nothing to scroll:
     *
     * ```java
     * // Don't try to intercept touch if we can't scroll anyway.
     * if (getScrollY() == 0 && !canScrollVertically(1)) return false;
     * ```
     *
     * A panel whose contents fit the screen is exactly that case, and the card underneath
     * swallows touches of its own, so [onTouchEvent] was never reached and the panel could not be
     * closed at all. A parent is offered every event before its children, so the decision is made
     * here instead and the scroller's opinion is not needed.
     */
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e.y)
            MotionEvent.ACTION_MOVE -> if (!taking && past(e)) { start(e.y); return true }
        }
        return super.onInterceptTouchEvent(e)
    }

    /**
     * Accumulates how far the finger has travelled up with no list left, and says when that is
     * far enough to take the gesture.
     *
     * One event can pass through both [onInterceptTouchEvent] and [onTouchEvent], which shows up
     * here as a second call with no movement in it. A zero step therefore leaves the total alone;
     * only travelling back down clears it.
     */
    private fun past(e: MotionEvent): Boolean {
        val step = e.y - lastY
        lastY = e.y
        when {
            step < 0 && !canScrollVertically(1) -> past -= step
            step > 0 -> past = 0f
        }
        return past > slop
    }

    private fun start(y: Float) {
        taking = true
        baseY = y
        // Tell the scroller its touch is over, or it keeps its own fling state and fights the drag.
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.onTouchEvent(cancel)
        cancel.recycle()
        Log.i("SidePadPanel", "past the end; the drag now moves the panel")
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e.y)

            MotionEvent.ACTION_MOVE -> {
                if (!taking && past(e)) start(e.y)
                if (taking) {
                    drag = (e.y - baseY).coerceAtMost(0f)
                    onDismissDrag?.invoke(drag)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (taking) {
                    taking = false
                    onDismissEnd?.invoke(-drag >= dismissAfterPx)
                    return true
                }
            }
        }
        return super.onTouchEvent(e)
    }
}
