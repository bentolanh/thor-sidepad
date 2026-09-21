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

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) begin(e.y)
        return super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e.y)

            MotionEvent.ACTION_MOVE -> {
                val step = e.y - lastY              // negative while the finger moves up
                lastY = e.y
                if (!taking) {
                    past = if (step < 0 && !canScrollVertically(1)) past - step else 0f
                    if (past > slop) {
                        taking = true
                        baseY = e.y
                        // Tell the scroller its touch is over, or it keeps its own fling state
                        // and fights the drag.
                        val cancel = MotionEvent.obtain(e)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        Log.i("SidePadPanel", "past the end; the drag now moves the panel")
                    }
                }
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
