package dev.linhhan.thorsidepad.pad

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import dev.linhhan.thorsidepad.inject.Catalog
import dev.linhhan.thorsidepad.inject.isActionCode
import dev.linhhan.thorsidepad.inject.isStickCode
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

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun add(v: View, lp: WindowManager.LayoutParams) {
        lp.windowAnimations = dev.linhhan.thorsidepad.R.style.NoWindowAnimation
        wm.addView(v, lp); views.add(v)
    }

    /** Removes the previous pad windows once the new ones have had two frames to draw, so nothing goes blank. */
    private fun retireAfterDraw(anchor: View, old: List<View>) {
        if (old.isEmpty()) return
        anchor.post { anchor.post { old.forEach { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {} } } }
    }

    fun removeAll() {
        views.forEach { v -> try { wm.removeViewImmediate(v) } catch (_: Exception) {} }
        views.clear()
        shieldView = null; shieldParams = null
    }

    val isShowing get() = views.isNotEmpty()

    private fun fullScreenParams(title: String) = WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT).also { it.title = title }

    /** Thin strips on the top (pull down = panel) and bottom (pull up = show pad) edges. Idempotent. */
    fun showCatchers(onPullDown: () -> Unit, onPullUp: () -> Unit) {
        if (catchers.isNotEmpty()) return
        for ((down, cb) in listOf(true to onPullDown, false to onPullUp)) {
            val v = EdgeCatcherView(ctx, down, cb)
            val lp = WindowManager.LayoutParams((width * 0.6f).roundToInt(), EdgeCatcherView.HEIGHT_PX,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
            lp.gravity = (if (down) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            lp.title = if (down) "SidePad catcher top" else "SidePad catcher bottom"
            lp.windowAnimations = dev.linhhan.thorsidepad.R.style.NoWindowAnimation
            wm.addView(v, lp); catchers.add(v)
        }
    }

    fun removeCatchers() { catchers.forEach { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; catchers.clear() }

    fun showPanel(state: PanelState, actions: PanelActions) {
        removePanel()
        val h = ControlPanel.build(themed, state, actions, (height * 0.82f).roundToInt())
        val lp = fullScreenParams("SidePad panel")
        lp.windowAnimations = dev.linhhan.thorsidepad.R.style.NoWindowAnimation
        wm.addView(h.root, lp); panel = h.root; panelUpdate = h.update
    }

    /** Re-renders the open panel with new state; the window stays, so nothing flashes. */
    fun updatePanel(state: PanelState) { panelUpdate?.invoke(state) }

    fun removePanel() { panel?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; panel = null; panelUpdate = null }

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

    /** Where the shield toggle sits on screen for the current layout, or null if the layout has none. */
    fun shieldSpot(layout: PadLayout): FloatArray? {
        val b = layout.buttons.firstOrNull { isActionCode(it.code) } ?: return null
        val short = min(width, height).toFloat()
        return floatArrayOf(b.cx * width, b.cy * height, b.size * short / 2f)
    }

    /** One step of the interactive guide over the whole pad screen. */
    fun showGuide(step: Int, spot: FloatArray?, onGesture: (EdgeGesture) -> Unit, onSpotTap: () -> Unit, onSkip: () -> Unit) {
        removeGuide()
        val v = GuideView(ctx, step, spot, onGesture, onSpotTap, onSkip)
        val lp = fullScreenParams("SidePad guide")
        lp.windowAnimations = dev.linhhan.thorsidepad.R.style.NoWindowAnimation
        wm.addView(v, lp); guide = v
    }

    fun removeGuide() { guide?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }; guide = null }

    /** Shows the green tick on the current guide step. */
    fun guideDone(msg: String) { (guide as? GuideView)?.showDone(msg) }

    fun tearDown() { removeAll(); removeCatchers(); removePanel(); removeGuide() }

    /** Whether the compositor can blur what is behind a window (needed for the frosted backdrop). */
    val blurSupported: Boolean get() = try { wm.isCrossWindowBlurEnabled } catch (_: Throwable) { false }

    /**
     * Shows the pad in play mode. The previous pad windows are removed only after the new ones
     * are up, so switching shield/islands or changing looks does not flash the screen.
     */
    fun showPlay(layout: PadLayout, opacity: Float, engine: PadEngine, shield: Boolean,
                 backdrop: String, onGesture: (EdgeGesture) -> Unit, onAction: (Int) -> Unit) {
        val old = ArrayList(views)
        views.clear()
        shieldView = null; shieldParams = null
        if (shield) {
            val frosted = backdrop == "frosted" && blurSupported
            val color = backdropColor(backdrop)
            val v = ShieldPadView(ctx, layout, engine, onGesture, onAction, shieldOn = true, opacity = opacity, backdropColor = color)
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
            return
        }
        val short = min(width, height)
        for (b in layout.buttons) {
            val px = (b.size * short).roundToInt().coerceAtLeast(48)
            val enabled = engine.enabled(b.code)
            val v: View = when {
                isStickCode(b.code) -> StickView(ctx, b.code, enabled, engine)
                isActionCode(b.code) -> PadButtonView(ctx, b.code, true, {}, { code -> onAction(code) })
                else -> PadButtonView(ctx, b.code, enabled, engine::press, engine::release)
            }
            v.alpha = opacity
            val lp = WindowManager.LayoutParams(px, px, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (b.cx * width - px / 2f).roundToInt()
            lp.y = (b.cy * height - px / 2f).roundToInt()
            lp.title = "SidePad ${Catalog.byCode(b.code).label}"
            add(v, lp)
        }
        views.lastOrNull()?.let { retireAfterDraw(it, old) } ?: old.forEach { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {} }
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
        val root = FrameLayout(themed)
        val editor = EditPadView(ctx, working)
        root.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC101010.toInt())
            setPadding(8, 8, 8, 8)
        }
        fun btn(label: String, onClick: () -> Unit): Button = Button(themed).apply {
            text = label; isAllCaps = false
            setOnClickListener { onClick() }
            bar.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val hint = TextView(themed).apply { setTextColor(Color.WHITE); setPadding(16, 8, 16, 8) }
        fun refreshHint() {
            hint.text = if (PresetStore.isBuiltin(active))
                "Editing \"$active\" (built-in). Save will ask for a name and keep your copy. Tap to select; drag to move; pinch to resize."
            else
                "Editing \"$active\". Save writes your changes into it. Tap to select; drag to move; pinch to resize."
        }
        refreshHint()
        btn("Add") { showChoice("Add a button or stick", Catalog.all.map { "${it.label}   (${it.androidName})" }) { pos ->
            val code = Catalog.all[pos].code
            working.buttons.add(PadButton(code, 0.5f, 0.5f, if (isStickCode(code)) 0.28f else 0.15f))
            editor.selected = working.buttons.size - 1
        } }
        val smaller = btn("−") { sel(editor)?.let { it.size = (it.size - 0.02f).coerceAtLeast(0.06f); editor.invalidate() } }
        val bigger = btn("+") { sel(editor)?.let { it.size = (it.size + 0.02f).coerceAtMost(0.6f); editor.invalidate() } }
        val delete = btn("Delete") { if (editor.selected >= 0) { working.buttons.removeAt(editor.selected); editor.selected = -1 } }
        btn("Presets") { showPresets(active,
            onUse = { p -> active = p.name; working.buttons.clear(); working.buttons.addAll(p.layout.buttons.map { it.copy() }); editor.selected = -1; editor.invalidate(); refreshHint() },
            onDeletedActive = { active = PresetStore.builtins[0].name; working.buttons.clear(); working.buttons.addAll(PresetStore.builtins[0].layout.buttons.map { it.copy() }); editor.selected = -1; editor.invalidate(); refreshHint() })
        }
        btn("Cancel") { removeAll(); onCancel() }
        btn("Save") {
            if (PresetStore.isBuiltin(active)) {
                askName(PresetStore.nextName(app)) { name ->
                    PresetStore.upsert(app, Preset(name, "Your preset", working.copy(), false))
                    removeAll(); onSaved(working, name)
                }
            } else {
                PresetStore.upsert(app, Preset(active, "Your preset", working.copy(), false))
                removeAll(); onSaved(working, active)
            }
        }
        editor.onSelectionChanged = { i -> val has = i >= 0; smaller.isEnabled = has; bigger.isEnabled = has; delete.isEnabled = has }
        editor.selected = -1

        val top = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; addView(bar); addView(hint) }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

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
    private fun showPresets(active: String, onUse: (Preset) -> Unit, onDeletedActive: () -> Unit) {
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {}; views.remove(w) } }
        root.setOnTouchListener { _, e -> if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) dismiss(); true }

        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0181818.toInt())
            isClickable = true
        }
        val header = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 8, 8, 8) }
        header.addView(TextView(themed).apply { text = "Presets"; setTextColor(Color.WHITE); textSize = 18f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        header.addView(Button(themed).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { dismiss() } })
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
                text.addView(TextView(themed).apply { this.text = if (p.builtin) p.description else "Your preset. Save in the editor writes into it."; setTextColor(0xFFB0B8C0.toInt()); textSize = 13f })
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
                val actions = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL }
                actions.addView(Button(themed).apply { this.text = "Use"; isAllCaps = false; isEnabled = !isActive; setOnClickListener { dismiss(); onUse(p) } })
                if (!p.builtin) {
                    actions.addView(Button(themed).apply { this.text = "Delete"; isAllCaps = false; setOnClickListener {
                        PresetStore.delete(app, p.name)
                        if (isActive) { dismiss(); onDeletedActive() } else rebuild()
                    } })
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

    /** A small focusable window with a text field. The only place the pad takes window focus. */
    private fun askName(defaultName: String, onOk: (String) -> Unit) {
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() {
            window?.let { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {}; views.remove(w) }
            onFocusReturn?.invoke()
        }
        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF0181818.toInt()); setPadding(28, 20, 28, 20); isClickable = true
        }
        card.addView(TextView(themed).apply { text = "Preset name"; setTextColor(Color.WHITE); textSize = 18f })
        val field = EditText(themed).apply {
            setText(defaultName); setSelectAllOnFocus(true); isSingleLine = true
            // Keep the dialog visible instead of the keyboard's full-screen text box, and let Done save.
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        card.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(themed).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun save() { val name = field.text.toString().trim().ifEmpty { defaultName }; dismiss(); onOk(name) }
        field.setOnEditorActionListener { _, _, _ -> save(); true }
        row.addView(Button(themed).apply { text = "Save"; isAllCaps = false; setOnClickListener { save() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        root.addView(card, FrameLayout.LayoutParams((width * 0.6f).roundToInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        lp.title = "SidePad name"
        lp.windowAnimations = dev.linhhan.thorsidepad.R.style.NoWindowAnimation
        window = root
        add(root, lp)
        field.requestFocus()
    }

    /** A list chooser: a card over a scrim. Pick an entry, or Cancel / tap outside to back out. */
    private fun showChoice(title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val root = FrameLayout(themed)
        root.setBackgroundColor(0x99000000.toInt())
        var window: View? = null
        fun dismiss() { window?.let { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {}; views.remove(w) } }
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
