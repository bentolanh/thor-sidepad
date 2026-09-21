package dev.lbento.thorsidepad.pad

import android.content.Context
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
 * The handover happens once per touch and only at the very end of the list, so an ordinary scroll
 * is never interrupted: until the list is exhausted every event goes to [ScrollView] untouched.
 */
class PanelScrollView(ctx: Context) : ScrollView(ctx) {

    /** Finger past the end: [dy] is negative pixels, how far the panel should ride up. */
    var onDismissDrag: ((Float) -> Unit)? = null

    /** Finger lifted: true to put the panel away, false to settle it back open. */
    var onDismissEnd: ((Boolean) -> Unit)? = null

    /** How far past the end the finger must travel before the panel goes rather than springs back. */
    var dismissAfterPx: Float = 0f

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downY = 0f
    private var baseY = 0f
    private var taking = false
    private var drag = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; taking = false; drag = 0f }

            MotionEvent.ACTION_MOVE -> {
                // canScrollVertically(1) is false only when there is no list left below.
                if (!taking && (downY - e.y) > slop && !canScrollVertically(1)) {
                    taking = true
                    baseY = e.y
                    // Tell the scroller the touch is over, or it keeps its own fling state and
                    // fights the drag on the way back up.
                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel)
                    cancel.recycle()
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
