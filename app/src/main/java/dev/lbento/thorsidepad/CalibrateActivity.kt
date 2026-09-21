package dev.lbento.thorsidepad

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import dev.lbento.thorsidepad.inject.Ev
import dev.lbento.thorsidepad.inject.IInjector
import dev.lbento.thorsidepad.inject.IPadEvents
import dev.lbento.thorsidepad.inject.Injector
import dev.lbento.thorsidepad.pad.PadCalibration
import org.json.JSONArray

/**
 * Teaches the pad which physical control is which, and which device each one lives on.
 *
 * Two things this cannot assume, both learned the hard way. That a handheld puts all its
 * controls on one input device — the Odin 2 Mini does not, and a reader that opens one node
 * loses Start and Select with no sign that it has. And that a Linux code means the same thing on
 * every handheld; it usually does, and when it does not there is nothing to notice it by.
 *
 * So every candidate device is listened to at once, without grabbing any of them: a press still
 * reaches Android as usual, which keeps this screen usable and means a half-finished run leaves
 * nothing captured. What gets written down is the device and the code together.
 */
class CalibrateActivity : Activity() {

    private lateinit var prefs: Prefs
    private var svc: IInjector? = null
    private var paths: List<String> = emptyList()

    private val keys = mutableListOf<PadCalibration.KeyEntry>()
    private val axes = mutableListOf<PadCalibration.AxisEntry>()
    private var step = -1
    private var listening = false

    /** Each axis's resting value per device, so a push is measured from where it sits. */
    private val axisRest = HashMap<Long, Int>()
    private val axisSeen = HashMap<Long, Int>()

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastAcceptedAt = 0L

    private val events = object : IPadEvents.Stub() {
        override fun onEvent(type: Int, code: Int, value: Int) = onEventAt(-1, type, code, value)

        override fun onEventAt(node: Int, type: Int, code: Int, value: Int) {
            if (node < 0 || node >= paths.size) return
            val key = (node.toLong() shl 32) or (code.toLong() and 0xffffffffL)
            if (type == Ev.ABS) {
                // The first value seen for an axis is taken as its rest. A stick that is already
                // off-centre when this starts would otherwise register itself immediately.
                axisSeen[key] = value
                if (!axisRest.containsKey(key)) { axisRest[key] = value; return }
            }
            if (!listening || step < 0 || step >= PadCalibration.STEPS.size) return
            // A short deadness after each capture, so the release of one press cannot answer the
            // next question before the player has read it.
            if (System.currentTimeMillis() - lastAcceptedAt < 400) return

            val want = PadCalibration.STEPS[step]
            if (want.axis) {
                if (type != Ev.ABS) return
                val rest = axisRest[key] ?: return
                val swing = value - rest
                // Generous, because a stick's range is not known here and a trigger's rest is at
                // one end of its own. Anything that moves this far was pushed deliberately.
                if (kotlin.math.abs(swing) < 4096 && kotlin.math.abs(swing) < 24) return
                accept(PadCalibration.AxisEntry(want.canonical, paths[node], code, flip = swing < 0), null)
            } else {
                if (type != Ev.KEY || value == 0) return
                accept(null, PadCalibration.KeyEntry(want.canonical, paths[node], code))
            }
        }
    }

    private fun accept(axis: PadCalibration.AxisEntry?, key: PadCalibration.KeyEntry?) {
        lastAcceptedAt = System.currentTimeMillis()
        ui.post {
            val label = PadCalibration.STEPS[step].label
            if (axis != null) {
                axes.removeAll { it.canonical == axis.canonical }
                axes.add(axis)
                hint("$label  ←  ${short(axis.path)} axis ${axis.code}${if (axis.flip) " (reversed)" else ""}")
            }
            if (key != null) {
                keys.removeAll { it.canonical == key.canonical }
                keys.add(key)
                hint("$label  ←  ${short(key.path)} code ${key.code}")
            }
            renderTable()
            ui.postDelayed({ next() }, 350)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_calibrate)
        prefs = Prefs(this)
        title = "Calibrate controls"

        // Start from whatever is already saved, so a run can correct one control without redoing
        // the lot.
        PadCalibration.parse(prefs.calibration).let { keys.addAll(it.keys); axes.addAll(it.axes) }
        renderTable()

        findViewById<Button>(R.id.calBegin).setOnClickListener {
            if (listening) { listening = false; render(); return@setOnClickListener }
            if (step < 0 || step >= PadCalibration.STEPS.size) step = 0
            listening = true; render()
        }
        findViewById<Button>(R.id.calSkip).setOnClickListener { if (step >= 0) next() }
        findViewById<Button>(R.id.calBack).setOnClickListener {
            if (step <= 0) return@setOnClickListener
            step--
            val c = PadCalibration.STEPS[step].canonical
            keys.removeAll { it.canonical == c }; axes.removeAll { it.canonical == c }
            renderTable(); render()
        }
        findViewById<Button>(R.id.calSave).setOnClickListener { save() }
        findViewById<Button>(R.id.calClear).setOnClickListener {
            keys.clear(); axes.clear(); step = -1; listening = false
            prefs.calibration = ""
            renderTable(); render()
            toast("Cleared. The pad will use the standard codes again.")
        }
        render()
        Injector.connect(this) { s -> ui.post { onInjector(s) } }
    }

    private fun onInjector(s: IInjector?) {
        svc = s
        if (s == null) { state("Shizuku is not running. Start it, then open this again."); return }
        try {
            val json = s.controllerNodes()
            val arr = JSONArray(json)
            val found = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // Not our own devices: a calibration taught against the pad we created would
                // be teaching it about itself.
                if (o.optBoolean("gamepad", false) && !o.optString("name").contains("SidePad"))
                    found.add(o.getString("path"))
            }
            if (found.isEmpty()) { state("No input devices that look like controls."); return }
            paths = found
            // grab = false on purpose. Taking the devices would stop the buttons reaching this
            // screen, and a run abandoned halfway would leave the handheld's own controls captured.
            val res = s.forwardStartMulti(paths.toTypedArray(), false, events)
            if (res.isNullOrEmpty() || res.startsWith("error")) { state("Could not listen: $res"); return }
            state("Listening to ${paths.size} device(s). Press Begin.")
        } catch (e: Exception) {
            Log.w("SidePadCalibrate", "listen", e)
            state("This needs a newer helper. Stop and restart SidePad, then try again.")
        }
    }

    private fun next() { step++; if (step >= PadCalibration.STEPS.size) listening = false; render() }

    private fun render() {
        val prompt = findViewById<TextView>(R.id.calPrompt)
        val prog = findViewById<TextView>(R.id.calProgress)
        val begin = findViewById<Button>(R.id.calBegin)
        when {
            step < 0 -> {
                prompt.text = "Not started"
                prog.text = "${PadCalibration.STEPS.size} controls"
                begin.text = "Begin"
            }
            step >= PadCalibration.STEPS.size -> {
                prompt.text = "All done"
                prog.text = "${keys.size} button(s), ${axes.size} axis/axes — press Save"
                begin.text = "Start again"
            }
            else -> {
                prompt.text = "Press  ${PadCalibration.STEPS[step].label}"
                prog.text = "control ${step + 1} of ${PadCalibration.STEPS.size}" +
                    (if (listening) "" else "  ·  paused")
                begin.text = if (listening) "Pause" else "Resume"
            }
        }
    }

    private fun save() {
        if (keys.isEmpty() && axes.isEmpty()) { toast("Nothing to save yet."); return }
        val cal = PadCalibration.of(keys.toList(), axes.toList())
        prefs.calibration = cal.toJson()
        toast("Saved: ${cal.describe()}. Show the pad again for it to take effect.")
    }

    private fun renderTable() {
        val sb = StringBuilder()
        for (s in PadCalibration.STEPS) {
            val k = keys.firstOrNull { it.canonical == s.canonical }
            val a = axes.firstOrNull { it.canonical == s.canonical }
            val where = when {
                k != null -> "${short(k.path)}  code ${k.code}"
                a != null -> "${short(a.path)}  axis ${a.code}${if (a.flip) " rev" else ""}"
                else -> "—"
            }
            sb.append(s.label.padEnd(30).take(30)).append(' ').append(where).append('\n')
        }
        findViewById<TextView>(R.id.calTable).text = sb.toString().trimEnd()
    }

    private fun short(path: String) = path.substringAfterLast('/')
    private fun state(m: String) { findViewById<TextView>(R.id.calState).text = m }
    private fun hint(m: String) { findViewById<TextView>(R.id.calHint).text = m }
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        // Whatever happens, stop listening. Leaving the helper reading devices for a screen that
        // is gone would keep the pad from taking them when it next starts.
        try { svc?.forwardStop() } catch (_: Exception) {}
    }
}
