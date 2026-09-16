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
import android.widget.ListView
import android.widget.TextView
import dev.linhhan.thorsidepad.inject.Catalog
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
class PadOverlay(app: Context, val displayId: Int) {

    private val display: Display = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        ?: throw IllegalStateException("display $displayId not found")
    private val ctx: Context = app.createDisplayContext(display)
        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    private val themed: Context = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault_DayNight)
    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val views = ArrayList<View>()

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
        wm.addView(v, lp); views.add(v)
    }

    fun removeAll() {
        views.forEach { v -> try { wm.removeViewImmediate(v) } catch (_: Exception) {} }
        views.clear()
    }

    val isShowing get() = views.isNotEmpty()

    fun showPlay(layout: PadLayout, opacity: Float, engine: PadEngine) {
        removeAll()
        val short = min(width, height)
        for (b in layout.buttons) {
            val px = (b.size * short).roundToInt().coerceAtLeast(48)
            val enabled = engine.plan(b.code).isNotEmpty()
            val v = PadButtonView(ctx, b.code, enabled, engine::press, engine::release)
            v.alpha = opacity
            val lp = WindowManager.LayoutParams(px, px, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (b.cx * width - px / 2f).roundToInt()
            lp.y = (b.cy * height - px / 2f).roundToInt()
            lp.title = "SidePad ${Catalog.byCode(b.code).label}"
            add(v, lp)
        }
    }

    fun showEdit(layout: PadLayout, onSave: (PadLayout) -> Unit, onCancel: () -> Unit) {
        removeAll()
        val working = layout.copy()
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
        val hint = TextView(themed).apply {
            text = "Tap a button to select it, drag to move."
            setTextColor(Color.WHITE); setPadding(16, 8, 16, 8)
        }
        btn("Add") { showPicker { code ->
            working.buttons.add(PadButton(code, 0.5f, 0.5f, 0.15f))
            editor.selected = working.buttons.size - 1
        } }
        val smaller = btn("−") { sel(editor)?.let { it.size = (it.size - 0.02f).coerceAtLeast(0.06f); editor.invalidate() } }
        val bigger = btn("+") { sel(editor)?.let { it.size = (it.size + 0.02f).coerceAtMost(0.6f); editor.invalidate() } }
        val delete = btn("Delete") { if (editor.selected >= 0) { working.buttons.removeAt(editor.selected); editor.selected = -1 } }
        btn("Reset") { working.buttons.clear(); working.buttons.addAll(PadLayout.default().buttons); editor.selected = -1 }
        btn("Cancel") { removeAll(); onCancel() }
        btn("Save") { removeAll(); onSave(working) }
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

    /** A second full-screen window with the catalogue; tapping an entry adds it and closes the picker. */
    private fun showPicker(onPick: (Int) -> Unit) {
        val list = ListView(themed)
        val labels = Catalog.all.map { "${it.label}   (${it.androidName})" }
        list.adapter = ArrayAdapter(themed, android.R.layout.simple_list_item_1, labels)
        list.setBackgroundColor(0xF0181818.toInt())
        var window: View? = null
        list.setOnItemClickListener { _, _, pos, _ ->
            window?.let { w -> try { wm.removeViewImmediate(w) } catch (_: Exception) {}; views.remove(w) }
            onPick(Catalog.all[pos].code)
        }
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, baseFlags(), PixelFormat.TRANSLUCENT)
        lp.title = "SidePad picker"
        window = list
        add(list, lp)
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
