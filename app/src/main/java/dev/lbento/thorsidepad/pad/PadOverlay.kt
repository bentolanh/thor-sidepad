package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.lbento.thorsidepad.inject.Action
import dev.lbento.thorsidepad.inject.Catalog
import dev.lbento.thorsidepad.inject.isActionCode
import dev.lbento.thorsidepad.inject.isSliderCode
import dev.lbento.thorsidepad.inject.isDpadCode
import dev.lbento.thorsidepad.inject.isMediaUnit
import dev.lbento.thorsidepad.inject.isVideoUnit
import dev.lbento.thorsidepad.inject.isStickCode
import dev.lbento.thorsidepad.inject.isTrackpadUnit
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Owns every overlay window on one display. Play mode uses one small window per button so the
 * screen between buttons still belongs to whatever app is on that display. Edit mode uses a
 * single full-screen window.
 *
 * All windows are non-focusable: that is what keeps the top screen's game the focused window,
 * which is where the kernel-level button events get delivered.
 */
class PadOverlay(private val app: Context, val displayId: Int) {

    private val display: Display = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        ?: throw IllegalStateException("display $displayId not found")
    private val ctx: Context = app.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val themed: Context = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_DayNight)
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val views = ArrayList<View>()          // pad windows (play or edit)
    private val catchers = ArrayList<View>()
    private var panel: View? = null
    private var panelUpdate: ((PanelState) -> Unit)? = null
    private var panelSheet: View? = null
    private var panelScrim: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelHeight = 0

    /**
     * One consistent look around the panel. Shield off: a plain dim. Shield on with the pad up: the
     * shield window already supplies tint and blur, so the panel adds only a little. Shield on with
     * the pad hidden: the panel supplies the shield's tint and blur itself, to the same total.
     */
    private fun applyPanelLook(scrim: View, lp: WindowManager.LayoutParams, shieldOn: Boolean, backdrop: String) {
        val frosted = backdrop == "frosted" && blurSupported
        val color = when {
            !shieldOn -> 0x88000000.toInt()
            isShowing -> 0x44000000
            else -> when (backdrop) { "dark" -> 0xFF000000.toInt(); "dim" -> 0xB0000000.toInt(); else -> 0x88000000.toInt() }
        }
        scrim.setBackgroundColor(color)
        val blur = shieldOn && !isShowing && frosted
        lp.flags = if (blur) lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        lp.blurBehindRadius = if (blur) 48 else 0
    }

    /** The shield switch or backdrop changed inside the open panel: restyle it in place. */
    fun updatePanelLook(shieldOn: Boolean, backdrop: String) {
        val root = panel ?: return; val scrim = panelScrim ?: return; val lp = panelParams ?: return
        applyPanelLook(scrim, lp, shieldOn, backdrop)
        try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }
    /** What the playing session is doing; the service refreshes it, the video units read it. */
    val video = NowPlaying()

    /** Called when the service has fresh session state, so any video unit redraws. */
    fun updateVideo(playing: Boolean, position: Long, duration: Long, app: String, pkg: String,
                    title: String = "", subtitle: String = "") {
        video.playing = playing; video.position = position; video.duration = duration
        video.app = app; video.pkg = pkg; video.title = title; video.subtitle = subtitle
        invalidatePad()
    }

    private var shieldView: ShieldPadView? = null
    private var shieldParams: WindowManager.LayoutParams? = null

    /** Called after the one focusable window (the name dialog) closes, so focus can be handed back to the top screen. */
    var onFocusReturn: (() -> Unit)? = null

    val width: Int
    val height: Int

    init {
        val b = wm.maximumWindowMetrics.bounds
        width = b.width(); height = b.height()
        Log.i(TAG, "overlay on display $displayId (${display.name}) ${width}x$height")
    }

    /**
     * True when the pad is on the machine's only screen, and so shares every edge with Android.
     *
     * Android hands out exactly one edge: [View.setSystemGestureExclusionRects] covers the back
     * gesture down the sides and nothing else. The home swipe and the notification shade are not
     * an app's to take at any price, so on the top and bottom Android's detector and ours both
     * watch the same finger and neither is told about the other. Whichever's thresholds are met
     * first appears to win, which is not a gesture — it is a coin toss the player has to keep
     * making. No threshold we could pick would settle it, because their detector runs upstream
     * of ours.
     *
     * So on one screen the edges are Android's and the bubble is ours.
     */
    val singleScreen: Boolean get() = displayId == Display.DEFAULT_DISPLAY

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    /** A root that swallows Back and runs [onBack] instead, so Back closes our window. */
    private fun backFrame(onBack: () -> Unit): FrameLayout = object : FrameLayout(themed) {
        init { isFocusable = true; isFocusableInTouchMode = true }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /**
     * Claims the side edges from the system's back gesture for one of our full-screen windows.
     *
     * Without this the system's edge-swipe monitor steals the touch from us mid-swipe and fires a
     * back. On the pad's screen that back has nothing to act on, since none of our windows take
     * focus, so the system sends it to whatever does hold focus, which is the app on the top
     * screen. Watched happening: the monitor stole the stream from the panel, back navigation came
     * back null, and the press landed on the app above. Claiming the edges stops the steal, and the
     * swipe simply does nothing, which is what it should do over a pad.
     */
    private fun claimBackEdges(v: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val strip = (ShieldPadView.BACK_EDGE_DP * view.resources.displayMetrics.density).toInt()
            view.systemGestureExclusionRects =
                listOf(Rect(0, 0, strip, view.height), Rect(view.width - strip, 0, view.width, view.height))
        }
    }

    private fun isFocusableWindow(v: View) =
        ((v.layoutParams as? WindowManager.LayoutParams)?.flags ?: 0) and
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0

    /** True while one of our windows still holds input focus on the pad's screen. */
    private fun anyFocusableWindow() = views.any { isFocusableWindow(it) }

    /**
     * Takes one of our windows down. Focus goes back to the top screen only when we were holding
     * it and are now letting go: handing it back costs an activity launch up there, so doing it
     * after a window that never took focus would disturb the game for nothing.
     */
    private fun dropWindow(w: View) {
        val held = isFocusableWindow(w)
        try { wm.removeViewImmediate(w) } catch (_: Exception) {}
        views.remove(w)
        if (held && !anyFocusableWindow()) onFocusReturn?.invoke()
    }

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissView: View? = null

    private val dp get() = ctx.resources.displayMetrics.density
    private val dismissSizePx get() = (72 * dp).toInt()
    private val dismissLiftPx get() = (72 * dp).toInt()

    /**
     * The target a bubble is dragged onto to throw it away, shown only while a bubble is moving
     * and only when throwing it away is allowed. It sits bottom-centre, where a thumb already is.
     */
    private fun showDismissTarget() {
        if (dismissView != null) return
        val size = dismissSizePx
        val tv = TextView(themed).apply {
            text = "\u2715"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xB3202020.toInt())
                setStroke((2 * dp).toInt(), 0x66FFFFFF)
            }
        }
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = dismissLiftPx
        lp.title = "SidePad dismiss"
        lp.windowAnimations = dev.lbento.thorsidepad.R.style.NoWindowAnimation
        try { wm.addView(tv, lp); dismissView = tv } catch (e: Exception) { Log.w(TAG, "dismiss target", e) }
    }

    private fun removeDismissTarget() {
        dismissView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        dismissView = null
    }

    /** True when the bubble's centre has reached the target. Generous: it is a thrown gesture. */
    private fun overDismiss(cx: Float, cy: Float): Boolean {
        val v = dismissView ?: return false
        val size = dismissSizePx
        val tx = width / 2f
        val ty = height - dismissLiftPx - size / 2f
        val near = kotlin.math.hypot(cx - tx, cy - ty) < size
        v.alpha = if (near) 1f else 0.75f
        v.scaleX = if (near) 1.15f else 1f
        v.scaleY = v.scaleX
        return near
    }

    /**
     * A floating button that shows and hides the pad.
     *
     * Kept out of [views] on purpose: everything in there is torn down when the pad is hidden,
     * and this is the way back. It is deliberately small and half-transparent — it sits on top of
     * whatever is playing, so it has to be findable without being in the way.
     */
    fun showBubble(x: Int, y: Int, onMoved: (Int, Int) -> Unit, onLongPress: () -> Unit, onTap: () -> Unit,
                   canDismiss: () -> Boolean = { true }, onDismiss: () -> Unit = {}) {
        if (bubble != null) return
        val d = themed.resources.displayMetrics.density
        val size = (52 * d).toInt()
        val v = View(themed).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC31507E.toInt())
                setStroke((2 * d).toInt(), 0x66FFFFFF)
            }
        }
        val lp = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.title = "SidePad bubble"
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.x = x; lp.y = y
        // A drag has to be told from a tap, or the button fires every time it is moved.
        val slop = android.view.ViewConfiguration.get(themed).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        var held = false
        // Holding it opens the panel. On a single-screen handheld the panel's own pull-down is
        // Android's notification shade, so this is the only way to reach it.
        val hold = Runnable {
            if (!dragged) {
                held = true
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongPress()
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragged = false; held = false
                    v.postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragged && kotlin.math.hypot(dx, dy) > slop) {
                        dragged = true; v.removeCallbacks(hold)
                        // Only offered when it can actually be taken. With the shield up the
                        // whole screen is ours and this button is the only way out of it, so
                        // there is no target to drag onto and nothing to discover.
                        if (canDismiss()) showDismissTarget()
                    }
                    if (dragged) {
                        lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        overDismiss(lp.x + size / 2f, lp.y + size / 2f)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold)
                    val thrownAway = dragged && overDismiss(lp.x + size / 2f, lp.y + size / 2f)
                    removeDismissTarget()
                    when {
                        thrownAway -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onDismiss() }
                        dragged -> onMoved(lp.x, lp.y)
                        !held -> onTap()
                    }
                }
            }
            true
        }
        try { wm.addView(v, lp); bubble = v; bubbleParams = lp } catch (e: Exception) { Log.w(TAG, "bubble", e) }
    }

    fun removeBubble() {
        bubble?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        bubble = null; bubbleParams = null
        removeDismissTarget()
    }

    /**
     * Put the bubble back on top of whatever was just built.
     *
     * Windows of one type stack in the order they were added, and showing the pad adds a
     * full-screen shield, so a bubble created earlier ends up underneath it — reachable only by
     * the gesture the shield exists to swallow. On a single-screen handheld the bubble is the one
     * way back to the panel, so it is the last thing that can afford to be covered. Re-adding it
     * makes it the newest window again, which is the only ordering the window manager offers.
     */
    private fun raiseBubble() {
        val v = bubble ?: return
        val lp = bubbleParams ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        try { wm.addView(v, lp) } catch (e: Exception) { Log.w(TAG, "raise bubble", e); bubble = null; bubbleParams = null }
    }

    private fun add(v: View, lp: WindowManager.LayoutParams) {
        lp.windowAnimations = dev.lbento.thorsidepad.R.style.NoWindowAnimation
        // Anything of ours that fills the screen has to hold the edges, or the system's edge-swipe
        // monitor takes the touch and the back lands on the other screen. The small per-button
        // windows are left alone: between them the screen still belongs to the app underneath.
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.height == ViewGroup.LayoutParams.MATCH_PARENT) claimBackEdges(v)
        wm.addView(v, lp); views.add(v)
    }

    /** Removes the previous pad windows once the new ones have had two frames to draw, so nothing goes blank. */
    private fun retireAfterDraw(anchor: View, old: List<View>) {
        if (old.isEmpty()) return
        anchor.post { anchor.post { old.forEach { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {} } } }
    }

    fun removeAll() {
        val held = anyFocusableWindow()
        views.forEach { v -> try { wm.removeViewImmediate(v) } catch (_: Exception) {} }
        views.clear()
        shieldView = null; shieldParams = null
        if (held) onFocusReturn?.invoke()
    }

    val isShowing get() = views.isNotEmpty()

    private fun fullScreenParams(title: String) = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT).also { it.title = title }

    /** Thin strips on the top (pull down = panel) and bottom (pull up = show pad) edges. Idempotent. */
    fun showCatchers(onPullDown: () -> Unit, onPullUp: () -> Unit, tracker: PullListener? = null) {
        if (catchers.isNotEmpty()) return
        // Not built at all on one screen: a strip that only sometimes beats the shade is worse
        // than no strip, because the player cannot learn it. See [singleScreen].
        if (singleScreen) return
        for ((down, cb) in listOf(true to onPullDown, false to onPullUp)) {
            val v = EdgeCatcherView(ctx, down, cb, if (down) tracker else null)
            val lp = WindowManager.LayoutParams((width * 0.6f).roundToInt(), EdgeCatcherView.HEIGHT_PX,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
            lp.gravity = (if (down) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            lp.title = if (down) "SidePad catcher top" else "SidePad catcher bottom"
            lp.windowAnimations = dev.lbento.thorsidepad.R.style.NoWindowAnimation
            wm.addView(v, lp); catchers.add(v)
        }
    }

    private var linkBadge: View? = null

    /**
     * Says the link to the other machine is down, and offers to try again.
     *
     * Only shown when something is wrong. A working remote pad needs no chrome, but a dropped one
     * looks exactly like a working one from the player's side: you press, nothing happens, and
     * there is nothing to tell you why. Passing null takes it away.
     */
    fun showLinkBadge(text: String?, onTap: () -> Unit) {
        linkBadge?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {}; linkBadge = null }
        if (text == null) return
        val v = TextView(themed).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(26, 12, 26, 12)
            setBackgroundColor(0xEE8A4B2E.toInt())
            setOnClickListener { onTap() }
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        // Below the top edge strip, so pulling the panel down still works over it.
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = EdgeCatcherView.HEIGHT_PX + 8
        lp.title = "SidePad link"
        lp.windowAnimations = dev.lbento.thorsidepad.R.style.NoWindowAnimation
        wm.addView(v, lp); linkBadge = v
    }

    fun removeCatchers() { catchers.forEach { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; catchers.clear() }

    /**
     * Opens the panel like the notification shade. With [dragged] the sheet starts tucked above
     * the screen and follows the finger through [dragPanel] until [endPanelDrag]; otherwise it
     * slides in on its own.
     */
    fun showPanel(state: PanelState, actions: PanelActions, dragged: Boolean = false, shieldOn: Boolean = false, backdrop: String = "clear") {
        removePanel(animated = false)
        // Full height. The panel was held to 82% so a strip of the screen behind it stayed
        // tappable as the way out, which cost a fifth of the screen to buy one gesture. The
        // close button and the scroll-past-the-end both work from anywhere in the panel, so the
        // strip bought nothing and the list now has room to breathe.
        panelHeight = height
        val h = ControlPanel.build(themed, state, actions, panelHeight)
        // The panel does not take focus. It is the one thing meant to be used mid-game, for volume
        // and brightness, and a focusable window on this screen pulls focus off the top one, which
        // an app up there reads as being sent to the background: GameNative and the like pause. It
        // costs only the Back key as a way out, and tapping outside already closes the panel and
        // says so on the panel itself. Nothing in here needs key input; see ControlPanel.
        val wrap = FrameLayout(themed)
        wrap.addView(h.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        claimBackEdges(wrap)
        val lp = fullScreenParams("SidePad panel")
        lp.windowAnimations = dev.lbento.thorsidepad.R.style.NoWindowAnimation
        applyPanelLook(h.scrim, lp, shieldOn, backdrop)
        panelParams = lp
        h.sheet.translationY = -panelHeight.toFloat()
        h.scrim.alpha = 0f
        wm.addView(wrap, lp); panel = wrap; panelUpdate = h.update; panelSheet = h.sheet; panelScrim = h.scrim
        // Past the end of the list the drag stops being a scroll and becomes the panel leaving,
        // the way the notification shade does it. A finger that has run out of list is asking for
        // something, and until now it got nothing.
        (h.sheet as? PanelScrollView)?.let { sv ->
            sv.dismissAfterPx = panelHeight * 0.18f
            sv.onDismissDrag = { dy -> moveSheet(dy) }
            sv.onDismissEnd = { away -> if (away) removePanel(animated = true) else settlePanel(open = true) }
        }
        if (!dragged) settlePanel(open = true)
    }

    /** Finger progress while pulling the shade down: [dy] pixels from where the pull began. */
    fun dragPanel(dy: Float) = moveSheet(dy - panelHeight)

    /** Puts the sheet at [y] (0 fully open, -panelHeight gone) and matches the scrim to it. */
    private fun moveSheet(y: Float) {
        val sheet = panelSheet ?: return
        val c = y.coerceIn(-panelHeight.toFloat(), 0f)
        sheet.translationY = c
        panelScrim?.alpha = (1f + c / panelHeight).coerceIn(0f, 1f)
    }

    /** Finger lifted: settle open, or spring back up and go away. */
    fun endPanelDrag(commit: Boolean) { if (commit) settlePanel(open = true) else removePanel(animated = true) }

    private fun settlePanel(open: Boolean, then: (() -> Unit)? = null) {
        val sheet = panelSheet ?: return
        val target = if (open) 0f else -panelHeight.toFloat()
        sheet.animate().translationY(target).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).withEndAction { then?.invoke() }.start()
        panelScrim?.animate()?.alpha(if (open) 1f else 0f)?.setDuration(220)?.start()
    }

    /** Re-renders the open panel with new state; the window stays, so nothing flashes. */
    fun updatePanel(state: PanelState) { panelUpdate?.invoke(state) }

    fun removePanel(animated: Boolean = true) {
        val root = panel ?: return
        panel = null; panelUpdate = null; panelParams = null
        val sheet = panelSheet; val scrim = panelScrim; panelSheet = null; panelScrim = null
        fun drop() {
            try { wm.removeViewImmediate(root) } catch (_: Exception) {}
        }
        if (animated && sheet != null) {
            sheet.animate().translationY(-panelHeight.toFloat()).setDuration(180).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { drop() }.start()
            scrim?.animate()?.alpha(0f)?.setDuration(180)?.start()
        } else drop()
    }

    private fun backdropColor(backdrop: String): Int {
        val frosted = backdrop == "frosted" && blurSupported
        return when (backdrop) {
            "dim" -> 0x99000000.toInt()
            "dark" -> 0xFF000000.toInt()
            "frosted" -> if (frosted) 0x55000000 else 0xB0000000.toInt()   // no blur: fall back to a heavier dim
            else -> 0
        }
    }

    /** Applies opacity and backdrop to the pad that is already up, without re-adding any window. */
    fun updateLooks(opacity: Float, backdrop: String) {
        val sv = shieldView; val lp = shieldParams
        if (sv != null && lp != null) {
            sv.opacity = opacity
            sv.backdropColor = backdropColor(backdrop)
            val frosted = backdrop == "frosted" && blurSupported
            val flags = if (frosted) lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND else lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            if (flags != lp.flags || (frosted && lp.blurBehindRadius != 48)) {
                lp.flags = flags; lp.blurBehindRadius = if (frosted) 48 else 0
                try { wm.updateViewLayout(sv, lp) } catch (_: Exception) {}
            }
        } else {
            views.forEach { it.alpha = opacity }
        }
    }

    /** Redraws pad views so enabled/disabled states follow a changed target. */
    fun invalidatePad() { views.forEach { it.invalidate() } }

    val isPanelShowing get() = panel != null

    private var guide: View? = null

    /** One step of the interactive guide over the whole pad screen. */
    fun showGuide(step: Int, onGesture: (EdgeGesture) -> Unit, onSkip: () -> Unit, pull: PullListener? = null) {
        removeGuide()
        val v = GuideView(ctx, step, onGesture, onSkip, pull, edges = !singleScreen)
        val lp = fullScreenParams("SidePad guide")
        lp.windowAnimations = dev.lbento.thorsidepad.R.style.NoWindowAnimation
        wm.addView(v, lp); guide = v
    }

    fun removeGuide() { guide?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; guide = null }



    fun tearDown() { removeAll(); removeCatchers(); removePanel(); removeGuide(); showLinkBadge(null) {} }

    /** Whether the compositor can blur what is behind a window (needed for the frosted backdrop). */
    val blurSupported: Boolean get() = try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false }

    /**
     * Shows the pad in play mode. The previous pad windows are removed only after the new ones
     * are up, so switching shield/islands or changing looks does not flash the screen.
     */
    /** Set by the service: the shade-style pull that opens the panel. */
    var pullTracker: PullListener? = null

    fun showPlay(layout: PadLayout, opacity: Float, engine: PadEngine, shield: Boolean,
                 backdrop: String, onGesture: (EdgeGesture) -> Unit, onAction: (Int) -> Unit,
                 levels: MutableMap<Int, Float> = HashMap(), onSlider: (Int, Float, Boolean) -> Unit = { _, _, _ -> },
                 pointer: PointerSink? = null) {
        val old = ArrayList(views)
        views.clear()
        shieldView = null; shieldParams = null
        if (shield) {
            val frosted = backdrop == "frosted" && blurSupported
            val color = backdropColor(backdrop)
            val v = ShieldPadView(ctx, layout, engine, onGesture, onAction, pullTracker, shieldOn = true, opacity = opacity, backdropColor = color, levels = levels, onSlider = onSlider, video = video, edges = !singleScreen)
            val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
            if (frosted) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = 48
            }
            lp.title = "SidePad shield"
            add(v, lp)
            shieldView = v; shieldParams = lp
            retireAfterDraw(v, old)
            raiseBubble()
            return
        }
        val short = min(width, height)
        val session = LatchSession()
        val holdViews = ArrayList<View>()
        session.onArmChanged = { holdViews.forEach { it.invalidate() } }
        for (b in layout.buttons) {
            val px = (b.size * short).roundToInt().coerceAtLeast(48)
            val enabled = engine.enabled(b.code)
            val v: View = when {
                isStickCode(b.code) -> StickView(ctx, b.code, enabled, engine)
                isDpadCode(b.code) -> DpadView(ctx, enabled, engine)
                isTrackpadUnit(b.code) -> TrackpadView(ctx,
                    onMove = { dx, dy -> pointer?.move(dx, dy) },
                    onButton = { c, down -> pointer?.button(c, down) },
                    onWheel = { n -> pointer?.wheel(n) },
                    enabled = pointer != null)
                isSliderCode(b.code) -> SliderView(ctx, b.code, levels[b.code] ?: 0.5f, onSlider)
                b.code == Action.HOLD || b.code == Action.TURBO ->
                    PadButtonView(ctx, b.code, true, {}, {}, layout.style, session = session).also { holdViews.add(it) }
                isMediaUnit(b.code) -> MediaPadView(ctx, levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] ?: 0.5f, video,
                    { code -> onAction(code) }, onSlider)
                isVideoUnit(b.code) -> VideoPadView(ctx, video,
                    levels[dev.lbento.thorsidepad.inject.Slider.VOLUME_MEDIA] ?: 0.5f,
                    { code -> onAction(code) }, onSlider)
                isActionCode(b.code) -> PadButtonView(ctx, b.code, true, {}, { code -> onAction(code) })
                else -> PadButtonView(ctx, b.code, enabled, engine::press, engine::release, layout.style, b.sticky, session,
                    b.turbo, { c -> engine.startTurbo(c, b.turboMs) }, engine::stopTurbo)
            }
            v.alpha = opacity
            // Sliders are narrow and hang their symbol and screen tag below the track; everything else is square.
            val w = when {
                isSliderCode(b.code) -> (px * 0.5f).roundToInt()
                isMediaUnit(b.code) -> (px * ButtonPainter.MEDIA_HALF_W).roundToInt()
                isVideoUnit(b.code) -> (px * ButtonPainter.VIDEO_HALF_W).roundToInt()
                isTrackpadUnit(b.code) -> (px * TrackpadView.HALF_W).roundToInt()
                else -> px
            }
            val h = when {
                isSliderCode(b.code) -> (px * 1.2f).roundToInt()
                isMediaUnit(b.code) -> (px * ButtonPainter.MEDIA_HALF_H).roundToInt()
                isVideoUnit(b.code) -> (px * ButtonPainter.VIDEO_HALF_H).roundToInt()
                isTrackpadUnit(b.code) -> (px * TrackpadView.HALF_H).roundToInt()
                else -> px
            }
            val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (b.cx * width - w / 2f).roundToInt()
            // Sliders hang from their top edge, so they keep the square offset; everything else,
            // including the wide media bar, is centred on its own height.
            lp.y = (b.cy * height - (if (isSliderCode(b.code)) px / 2f else h / 2f)).roundToInt()
            lp.title = "SidePad ${Catalog.byCode(b.code).label}"
            add(v, lp)
        }
        views.lastOrNull()?.let { retireAfterDraw(it, old) } ?: old.forEach { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {} }
        raiseBubble()
    }

    /**
     * The editor. `activeName` is the preset the layout came from. Save writes the layout back
     * into that preset when it is the user's own; for a built-in it asks for a name and creates
     * the user's copy. `onSaved` receives the layout and the (possibly new) active preset name.
     */
    fun showEdit(layout: PadLayout, activeName: String, onSaved: (PadLayout, String) -> Unit, onCancel: () -> Unit) {
        removeAll()
        val working = layout.copy()
        var active = activeName
        var backAction: () -> Unit = {}
        val root = backFrame { backAction() }
        root.setBackgroundColor(0xE0101010.toInt())
        val editor = EditPadView(ctx, working).apply { setBackgroundColor(0xFF1A1A1A.toInt()) }
        val bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC101010.toInt())
            setPadding(6, 4, 6, 4)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun btn(label: String, onClick: () -> Unit): Button = Button(themed).apply {
            text = label; isAllCaps = false; textSize = 13f
            minWidth = 0; minimumWidth = 0; setPadding(6, 6, 6, 6)
            setOnClickListener { onClick() }
            bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        // The profile picker rides in the toolbar row itself, so it costs no extra height. The
        // background and chevron keep it reading as a picker rather than another button.
        val profileValue = TextView(themed).apply {
            setTextColor(Color.WHITE); textSize = 13f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val profileChip = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2A2D31.toInt())
            setPadding(14, 8, 12, 8)
            addView(profileValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(themed).apply { text = "▾"; setTextColor(0xFF8AB4F8.toInt()); textSize = 14f },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = 10 })
        }
        bar.addView(profileChip, 0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { marginEnd = 8 })
        val hintText = "Tap to select · drag to move · pinch to resize"
        val hint = TextView(themed).apply {
            setTextColor(0xFFDCE4EC.toInt()); textSize = 11f; setPadding(20, 7, 20, 7)
            setBackgroundColor(0xCC0E1114.toInt())
            text = hintText
        }
        // The helper text belongs to an idle screen: it steps aside as soon as a finger works in the
        // pad area, and comes back once the finger has been still for a moment.
        val showHint = Runnable { hint.animate().cancel(); hint.animate().alpha(1f).setDuration(250).start() }
        editor.onTouched = {
            hint.removeCallbacks(showHint)
            hint.animate().cancel()
            if (hint.alpha > 0f) hint.animate().alpha(0f).setDuration(150).start()
            hint.postDelayed(showHint, 2200)
        }
        fun refreshProfile() {
            profileValue.text = if (PresetStore.isBuiltin(active)) "$active  (built-in)" else active
        }
        refreshProfile()

        // ---- saving and leaving ----
        /** The profile as it is stored. A built-in is "stored" in code, so this is never null in practice. */
        fun stored(): PadLayout? = PresetStore.find(app, active)?.layout
        /** Does the pad on screen differ from the profile it came from? */
        fun dirty(): Boolean = stored()?.toJson() != working.toJson()
        fun flash(msg: String) {
            hint.removeCallbacks(showHint); hint.animate().cancel()
            hint.alpha = 1f; hint.text = msg
            hint.postDelayed({ hint.text = hintText }, 1600)
        }
        /**
         * Save into the profile being edited. A built-in cannot be written to, so that one case asks
         * for a name and the new copy becomes the profile you are editing.
         */
        fun saveCurrent(then: () -> Unit) {
            if (PresetStore.isBuiltin(active)) askName(PresetStore.nextName(app)) { name ->
                PresetStore.upsert(app, Preset(name, "Your preset", working.copy(), false))
                active = name; refreshProfile(); then()
            } else {
                PresetStore.upsert(app, Preset(active, "Your preset", working.copy(), false))
                then()
            }
        }
        // removeAll hands focus back if anything was holding it; the editor itself never is.
        fun leave(adopt: PadLayout) { removeAll(); onSaved(adopt, active) }
        /** Leaving always asks about unsaved work, from the Exit button and from the back gesture alike. */
        fun exit() {
            if (!dirty()) { leave(working); return }
            showChoice("Leave the editor?", listOf("Save changes to \"$active\"", "Leave without saving")) { pick ->
                if (pick == 0) saveCurrent { leave(working) } else leave(stored() ?: working)
            }
        }
        /** Anything that replaces what is on screen asks about unsaved work first. */
        fun guarded(what: String, action: () -> Unit) {
            if (!dirty()) { action(); return }
            val plain = what.replaceFirstChar { it.uppercase() }
            showChoice("Unsaved changes to \"$active\"", listOf("Save, then $what", "$plain without saving")) { pick ->
                if (pick == 0) saveCurrent { action() } else action()
            }
        }
        backAction = { exit() }

        fun switchTo(name: String, layoutOf: PadLayout) {
            active = name; working.buttons.clear(); working.buttons.addAll(layoutOf.buttons.map { it.copy() })
            working.style = layoutOf.style; editor.selected = -1; editor.invalidate(); refreshProfile()
        }
        profileChip.setOnClickListener {
            showPresets(active,
                onOpen = { p -> guarded("open") { switchTo(p.name, p.layout) } },
                onOverwrite = { p ->
                    // Store what is on screen into that profile and carry on editing it there.
                    PresetStore.upsert(app, Preset(p.name, "Your profile", working.copy(), false))
                    active = p.name; refreshProfile(); flash("Saved to \"$active\"")
                },
                onNew = { name ->
                    // A brand-new, empty profile to build from scratch. It is stored straight away, so
                    // it is a real profile even before the first Save.
                    guarded("start it") {
                        val blank = PadLayout(mutableListOf(), working.style)
                        PresetStore.upsert(app, Preset(name, "Your preset", blank, false))
                        switchTo(name, blank)
                    }
                })
        }
        btn("Add") { showGroupedChoice("Add to the pad", Catalog.groups.map { g -> g.first to g.second.map { "${it.label}   (${it.androidName})" } }) { g, pos ->
            val code = Catalog.groups[g].second[pos].code
            working.buttons.add(PadButton(code, 0.5f, 0.5f, if (isStickCode(code) || isDpadCode(code)) 0.28f else if (isSliderCode(code)) 0.34f else if (isMediaUnit(code)) 0.24f else if (isVideoUnit(code)) 0.30f else 0.15f))
            editor.selected = working.buttons.size - 1
        } }
        val delete = btn("Delete") { if (editor.selected >= 0) { working.buttons.removeAt(editor.selected); editor.selected = -1 } }
        // How a button presses: normally, held by a tap, repeating while held, or both. One chooser
        // rather than a toggle each, so the toolbar stays on a single row.
        val behavior = btn("Behavior") {
            val b = sel(editor)
            if (b == null || b.code <= 0) return@btn
            val now = (if (b.sticky) 1 else 0) + (if (b.turbo) 2 else 0)
            val labels = listOf(
                "Normal",
                "Sticky · a tap holds it down",
                "Turbo · repeats while held",
                "Sticky + Turbo · a tap starts it repeating",
            )
            showChoice("How this button presses", labels.mapIndexed { i, t -> (if (i == now) "●  " else "") + t }) { pick ->
                b.sticky = pick == 1 || pick == 3
                b.turbo = pick == 2 || pick == 3
                editor.invalidate()
                if (b.turbo) askTurboRate(b) { editor.invalidate() }
            }
        }
        // Per-preset button glyphs: the presses stay the same, the labels read like that console's pad.
        btn("Glyphs") { showChoice("Button glyphs for this preset", Glyphs.styles.map { (key, name) -> (if (key == working.style) "●  " else "") + name }) { pos ->
            working.style = Glyphs.styles[pos].first; editor.invalidate()
        } }
        btn("Exit") { exit() }
        btn("Save") { saveCurrent { flash("Saved to \"$active\"") } }
        editor.onSelectionChanged = { i -> val has = i >= 0; delete.isEnabled = has
            behavior.isEnabled = has && (working.buttons.getOrNull(i)?.code ?: 0) > 0 }
        editor.selected = -1

        val top = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; addView(bar) }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // The whole pad area, scaled to fit under the toolbar, so a button placed near the top
        // of the screen is still reachable in the editor. A thin frame marks the screen edge.
        top.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val gap = 8
        val avail = height - top.measuredHeight - gap * 2
        val scale = min(avail.toFloat() / height, 1f)
        val frame = FrameLayout(themed).apply { setBackgroundColor(Color.WHITE); setPadding(2, 2, 2, 2) }
        frame.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, FrameLayout.LayoutParams((width * scale).roundToInt() + 4, (height * scale).roundToInt() + 4, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = top.measuredHeight + gap })
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 18 })

        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.title = "SidePad editor"
        add(root, lp)
    }

    private fun sel(editor: EditPadView): PadButton? = editor.layout.buttons.getOrNull(editor.selected)

    /**
     * The preset chooser: one card per preset with a miniature, name and description. Use
     * switches the editor to it; the user's own presets can also be deleted. The active one is marked.
     */
    /**
     * The profile list. [onOpen] loads a profile; [onOverwrite], when given, writes what is on
     * screen into it. Built-ins live in code, so they can be opened but never written to.
     */
    private fun showPresets(active: String, onOpen: (Preset) -> Unit, primaryLabel: String = "Open",
                            onOverwrite: ((Preset) -> Unit)? = null, onNew: ((String) -> Unit)? = null,
                            allowDelete: Boolean = false) {
        var onBack: () -> Unit = {}
        val root = backFrame { onBack() }
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> dropWindow(w) } }
        onBack = { dismiss() }
        root.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) dismiss(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0181818.toInt())
            isClickable = true
        }
        val header = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 8, 8, 8) }
        header.addView(TextView(themed).apply { text = "Profiles"; setTextColor(Color.WHITE); textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        if (onNew != null) header.addView(Button(themed).apply { text = "＋ New"; isAllCaps = false; setOnClickListener { dismiss(); askName(PresetStore.nextName(app)) { name -> onNew(name) } } })
        header.addView(Button(themed).apply { text = "Close"; isAllCaps = false; setOnClickListener { dismiss() } })
        card.addView(header)

        val list = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 16, 16) }
        val aspect = width.toFloat() / height
        fun rebuild() {
            list.removeAllViews()
            for (p in PresetStore.all(app)) {
                val isActive = p.name == active
                val row = LinearLayout(themed).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(if (isActive) 0xFF2A3A2E.toInt() else 0xFF24282C.toInt())
                    setPadding(16, 12, 16, 12)
                }
                row.addView(LayoutPreviewView(themed, p.layout, aspect), LinearLayout.LayoutParams(220, ViewGroup.LayoutParams.WRAP_CONTENT))
                val text = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 0, 12, 0) }
                text.addView(TextView(themed).apply { this.text = p.name + (if (isActive) "   • active" else ""); setTextColor(Color.WHITE); textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                text.addView(TextView(themed).apply { this.text = if (p.builtin) p.description else "Your profile."; setTextColor(0xFFB0B8C0.toInt()); textSize = 13f })
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
                val actions = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
                actions.addView(Button(themed).apply { this.text = primaryLabel; isAllCaps = false; isEnabled = !isActive; setOnClickListener { dismiss(); onOpen(p) } })
                // Overwrite replaces that profile's saved layout with the one on screen, so it asks first.
                if (onOverwrite != null && !p.builtin) {
                    actions.addView(Button(themed).apply { this.text = "Overwrite"; isAllCaps = false; setOnClickListener {
                        dismiss()
                        showChoice("Overwrite \"${p.name}\"?", listOf("Overwrite it with what is on screen")) { onOverwrite(p) }
                    } })
                }
                // Deleting a profile is permanent, so it asks first. The profile in use cannot be
                // deleted, and built-ins live in code.
                if (allowDelete && !p.builtin) {
                    actions.addView(Button(themed).apply {
                        this.text = "Delete"; isAllCaps = false; isEnabled = !isActive
                        setOnClickListener {
                            showChoice("Delete \"${p.name}\"?", listOf("Delete it for good")) {
                                PresetStore.delete(app, p.name); rebuild()
                            }
                        }
                    })
                }
                row.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_VERTICAL })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 })
            }
        }
        rebuild()
        val scroll = ScrollView(themed).apply { addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(card, FrameLayout.LayoutParams((width * 0.86f).roundToInt(), (height * 0.9f).roundToInt(), Gravity.CENTER))
        window = root
        add(root, fullScreenParams("SidePad presets"))
    }

    /** The panel's profile chooser: pick one and the pad switches to it. No overwriting from here. */
    fun showProfilePicker(active: String, onOpen: (Preset) -> Unit) =
        showPresets(active, onOpen = onOpen, primaryLabel = "Use", allowDelete = true)

    private fun rateText(ms: Int) = "$ms ms · about ${(1000f / ms).roundToInt()} a second"

    /** How fast a turbo button presses: three named rates, or a slider for anything in between. */
    private fun askTurboRate(b: PadButton, onChanged: () -> Unit) {
        val presets = listOf(200 to "Slow", TURBO_DEFAULT_MS to "Medium", 40 to "Fast")
        val cur = if (b.turboMs > 0) b.turboMs else TURBO_DEFAULT_MS
        val labels = presets.map { (ms, name) -> (if (ms == cur) "●  " else "") + "$name · ${rateText(ms)}" } +
            ((if (presets.none { it.first == cur }) "●  " else "") + "Custom…")
        showChoice("How fast it repeats", labels) { pick ->
            if (pick < presets.size) { b.turboMs = presets[pick].first; onChanged() }
            else askRate(cur) { ms -> b.turboMs = ms; onChanged() }
        }
    }

    /** The custom rate: a slider in milliseconds from one press to the next. */
    private fun askRate(current: Int, onOk: (Int) -> Unit) {
        var onBack: () -> Unit = {}
        val root = backFrame { onBack() }
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> dropWindow(w) } }
        onBack = { dismiss() }
        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF0181818.toInt()); setPadding(28, 20, 28, 20); isClickable = true
        }
        card.addView(TextView(themed).apply { text = "Time per press"; setTextColor(Color.WHITE); textSize = 18f })
        var ms = current.coerceIn(TURBO_MIN_MS, TURBO_MAX_MS)
        val read = TextView(themed).apply { setTextColor(0xFFB8C0C8.toInt()); textSize = 14f; text = rateText(ms) }
        card.addView(read)
        card.addView(SeekBar(themed).apply {
            max = TURBO_MAX_MS - TURBO_MIN_MS
            progress = ms - TURBO_MIN_MS
            setPadding(24, 24, 24, 24)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { ms = p + TURBO_MIN_MS; read.text = rateText(ms) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(themed).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { dismiss() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(themed).apply { text = "Save"; isAllCaps = false; setOnClickListener { dismiss(); onOk(ms) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        root.addView(card, FrameLayout.LayoutParams((width * 0.7f).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.title = "SidePad rate"
        window = root
        add(root, lp)
    }

    /** A small focusable window with a text field. The only place the pad takes window focus. */
    /**
     * Asks for a preset name with a keypad of our own rather than the system keyboard.
     *
     * The system keyboard cannot be had here. A floating window like ours never reports itself to
     * the input-method service the way an app's own window does: the service ends up pointing at a
     * different window, our side never asks for a keyboard at all, and asking by hand is refused
     * because the field was never adopted. An app sitting on this screen has no such trouble, which
     * is the difference between a window that belongs to a screen and one that merely floats over
     * it. Rather than fight that, the letters are drawn here and typing is ours to handle.
     *
     * It also means this window takes no focus, so naming a preset now disturbs the top screen no
     * more than anything else on the pad does.
     */
    private fun askName(defaultName: String, onOk: (String) -> Unit) {
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> dropWindow(w) } }

        var caps = false

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0181818.toInt()); setPadding(22, 16, 22, 16); isClickable = true
        }
        val header = TextView(themed).apply { text = "Preset name"; setTextColor(0xFF9AA0A6.toInt()); textSize = 14f }
        card.addView(header)
        val field = NameField(themed, defaultName).apply {
            setPadding(14, 8, 14, 8); setBackgroundColor(0xFF2A2D31.toInt())
        }
        // Say so when the cap is reached, or a key that does nothing looks like a missed press.
        fun afterKey() {
            header.text = if (field.atLimit) "Preset name — ${NameField.MAX} characters is the most it takes"
                          else "Preset name"
        }
        afterKey()
        card.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 8; bottomMargin = 12 })

        fun key(label: String, onTap: () -> Unit) = Button(themed).apply {
            text = label; isAllCaps = false; textSize = 15f
            minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener { onTap() }
        }
        fun keyRow(chars: String): LinearLayout {
            val r = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in chars) r.addView(
                key(c.toString()) {
                    // 26 letters would otherwise need a key each for both cases; Caps picks the case.
                    field.insert((if (caps || !c.isLetter()) c else c.lowercaseChar()).toString()); afterKey()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return r
        }
        for (rowChars in listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")) {
            card.addView(keyRow(rowChars), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val tools = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var capsKey: Button
        capsKey = key("Caps: off") { caps = !caps; capsKey.text = if (caps) "Caps: on" else "Caps: off" }
        tools.addView(capsKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        // The caret moves, so a mistake in the middle of a name is a fix rather than a retype.
        tools.addView(key("\u25C0") { field.left() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        tools.addView(key("\u25B6") { field.right() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        tools.addView(key("space") { field.insert(" "); afterKey() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f))
        tools.addView(key("\u232B") { field.backspace(); afterKey() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        card.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(themed).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { dismiss() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(themed).apply {
            text = "Save"; isAllCaps = false
            setOnClickListener { val name = field.value.trim().ifEmpty { defaultName }; dismiss(); onOk(name) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 10 })

        root.addView(card, FrameLayout.LayoutParams((width * 0.82f).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val lp = fullScreenParams("SidePad name")
        window = root
        add(root, lp)
    }

    /** A list chooser: a card over a scrim. Pick an entry, or Cancel / tap outside to back out. */
    /** A picker with a row of page tabs above the list; [onPick] gets (page, position). */
    private fun showGroupedChoice(title: String, groups: List<Pair<String, List<String>>>, onPick: (Int, Int) -> Unit) {
        var onBack: () -> Unit = {}
        val root = backFrame { onBack() }
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> dropWindow(w) } }
        onBack = { dismiss() }
        root.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) dismiss(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0181818.toInt())
            isClickable = true
        }
        val header = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 8, 8, 8) }
        header.addView(TextView(themed).apply { text = title; setTextColor(Color.WHITE); textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(Button(themed).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { dismiss() } })
        card.addView(header)

        val tabs = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; setPadding(16, 0, 16, 0) }
        val list = ListView(themed)
        val tabButtons = ArrayList<TextView>()
        var current = 0
        fun select(i: Int) {
            current = i
            tabButtons.forEachIndexed { j, b ->
                b.setTextColor(if (j == i) 0xFF64B5F6.toInt() else 0xFFB0B0B0.toInt())
                b.setBackgroundColor(if (j == i) 0xFF2A2A2A.toInt() else Color.TRANSPARENT)
            }
            list.adapter = ArrayAdapter(themed, android.R.layout.simple_list_item_1, groups[i].second)
        }
        groups.forEachIndexed { i, g ->
            val b = TextView(themed).apply {
                text = g.first; textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 22, 0, 22)
                setOnClickListener { select(i) }
            }
            tabButtons.add(b)
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(tabs)
        list.setOnItemClickListener { _, _, pos, _ -> dismiss(); onPick(current, pos) }
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        select(0)

        root.addView(card, FrameLayout.LayoutParams((width * 0.7f).roundToInt(), (height * 0.85f).roundToInt(), Gravity.CENTER))
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.title = "SidePad picker"
        window = root
        add(root, lp)
    }

    private fun showChoice(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        var onBack: () -> Unit = {}
        val root = backFrame { onBack() }
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> dropWindow(w) } }
        onBack = { dismiss() }
        root.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) dismiss(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0181818.toInt())
            isClickable = true
        }
        val header = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 8, 8, 8) }
        header.addView(TextView(themed).apply { text = title; setTextColor(Color.WHITE); textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(Button(themed).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { dismiss() } })
        card.addView(header)

        val list = ListView(themed)
        list.adapter = ArrayAdapter(themed, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, pos, _ -> dismiss(); onPick(pos) }
        card.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(card, FrameLayout.LayoutParams((width * 0.7f).roundToInt(), (height * 0.85f).roundToInt(), Gravity.CENTER))
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.title = "SidePad picker"
        window = root
        add(root, lp)
    }

    companion object {
        private const val TAG = "SidePadOverlay"

        /** True when this machine has no screen of its own to give the pad. */
        fun isSingleScreen(app: Context): Boolean =
            resolveDisplayId(app, -1) == Display.DEFAULT_DISPLAY

        /** The user's chosen display, or the first display that is not the main one. */
        fun resolveDisplayId(app: Context, preferred: Int): Int {
            val dm = app.getSystemService(DisplayManager::class.java)
            if (preferred >= 0 && dm.getDisplay(preferred) != null) return preferred
            val second = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.state != Display.STATE_OFF }
                ?: dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
            return second?.displayId ?: Display.DEFAULT_DISPLAY
        }
    }
}
