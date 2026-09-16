package dev.linhhan.thorsidepad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.linhhan.thorsidepad.inject.Btn
import dev.linhhan.thorsidepad.inject.Catalog
import dev.linhhan.thorsidepad.inject.Injector
import dev.linhhan.thorsidepad.pad.OverlayService
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var shizukuStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var probeStatus: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var displaySpinner: Spinner
    private var devicePaths: List<String> = emptyList()
    private var displayIds: List<Int> = emptyList()

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshStatus()
        if (Injector.state() == Injector.ShizukuState.READY) probe()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        probeStatus = findViewById(R.id.probeStatus)
        deviceSpinner = findViewById(R.id.deviceSpinner)
        displaySpinner = findViewById(R.id.displaySpinner)

        findViewById<Button>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { probe() }
        findViewById<Button>(R.id.btnShow).setOnClickListener { OverlayService.send(this, OverlayService.ACTION_SHOW) }
        findViewById<Button>(R.id.btnHide).setOnClickListener { OverlayService.send(this, OverlayService.ACTION_HIDE) }
        findViewById<Button>(R.id.btnEdit).setOnClickListener { OverlayService.send(this, OverlayService.ACTION_EDIT) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { OverlayService.send(this, OverlayService.ACTION_STOP) }
        findViewById<Button>(R.id.btnTestA).setOnClickListener { testPress(Btn.A) }
        findViewById<Button>(R.id.btnTestM1).setOnClickListener { testPress(Btn.C) }

        val mode = findViewById<RadioGroup>(R.id.modeGroup)
        mode.check(if (prefs.targetMode == Prefs.MODE_VIRTUAL) R.id.modeVirtual else R.id.modePhysical)
        mode.setOnCheckedChangeListener { _, id ->
            prefs.targetMode = if (id == R.id.modeVirtual) Prefs.MODE_VIRTUAL else Prefs.MODE_PHYSICAL
        }

        val opacity = findViewById<SeekBar>(R.id.opacity)
        opacity.progress = (prefs.opacity * 100).toInt()
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) prefs.opacity = p / 100f }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val shield = findViewById<Switch>(R.id.shieldSwitch)
        shield.isChecked = prefs.shield
        shield.setOnCheckedChangeListener { _, on -> prefs.shield = on }
        val gestures = findViewById<Switch>(R.id.gestureSwitch)
        gestures.isChecked = prefs.gestures
        gestures.setOnCheckedChangeListener { _, on -> prefs.gestures = on }

        val chord = findViewById<Switch>(R.id.chordSwitch)
        chord.isChecked = prefs.chordEnabled
        chord.setOnCheckedChangeListener { _, on -> prefs.chordEnabled = on }

        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                devicePaths.getOrNull(pos)?.let { prefs.physicalPath = it }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        fillDisplays()
        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                prefs.displayId = displayIds.getOrNull(pos) ?: -1
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (Injector.state() == Injector.ShizukuState.READY && devicePaths.isEmpty()) probe()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun refreshStatus() {
        shizukuStatus.text = when (Injector.state()) {
            Injector.ShizukuState.NOT_RUNNING -> "Shizuku: not running. Start it in the Shizuku app (wireless debugging)."
            Injector.ShizukuState.NO_PERMISSION -> "Shizuku: running, permission not granted."
            Injector.ShizukuState.READY -> "Shizuku: ready."
        }
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "Draw over other apps: granted." else "Draw over other apps: NOT granted."
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) { Toast.makeText(this, "Start Shizuku first.", Toast.LENGTH_LONG).show(); return }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1)
            else refreshStatus()
        } catch (e: Throwable) {
            Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun fillDisplays() {
        val dm = getSystemService(DisplayManager::class.java)
        val displays = dm.displays.toList()
        val labels = ArrayList<String>(); val ids = ArrayList<Int>()
        labels.add("Auto: first display that is not the main one"); ids.add(-1)
        for (d in displays) {
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") d.getRealMetrics(m)
            val tag = if (d.displayId == Display.DEFAULT_DISPLAY) " (main)" else ""
            labels.add("Display ${d.displayId}: ${d.name} ${m.widthPixels}x${m.heightPixels}$tag"); ids.add(d.displayId)
        }
        displayIds = ids
        displaySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = ids.indexOf(prefs.displayId); if (idx >= 0) displaySpinner.setSelection(idx)
    }

    private fun probe() {
        probeStatus.text = "Probing…"
        Injector.connect(this) { svc ->
            if (svc == null) { probeStatus.text = "Injector not available (Shizuku)."; return@connect }
            try {
                val o = JSONObject(svc.probe())
                val devs = o.getJSONArray("devices")
                val labels = ArrayList<String>(); val paths = ArrayList<String>()
                var gamepads = 0
                for (i in 0 until devs.length()) {
                    val d = devs.getJSONObject(i)
                    if (d.has("error")) continue
                    val isPad = d.optBoolean("gamepad", false)
                    if (isPad) gamepads++
                    labels.add((if (isPad) "🎮 " else "") + d.getString("name") + "  " + d.getString("path").removePrefix("/dev/input/"))
                    paths.add(d.getString("path"))
                }
                // Gamepads first so the Thor controller is the obvious pick.
                val order = labels.indices.sortedByDescending { labels[it].startsWith("🎮") }
                devicePaths = order.map { paths[it] }
                deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, order.map { labels[it] })
                val saved = devicePaths.indexOf(prefs.physicalPath)
                if (saved >= 0) deviceSpinner.setSelection(saved) else if (devicePaths.isNotEmpty()) { deviceSpinner.setSelection(0); prefs.physicalPath = devicePaths[0] }
                probeStatus.text = "Injector uid ${o.getInt("uid")}. /dev/uinput: ${o.getString("uinput")}. Gamepad devices: $gamepads."
            } catch (e: Exception) {
                probeStatus.text = "Probe failed: ${e.message}"
            }
        }
    }

    /** Presses one button for 150 ms on the configured target. Watch it in a gamepad tester on the top screen. */
    private fun testPress(code: Int) {
        Injector.connect(this) { svc ->
            if (svc == null) { Toast.makeText(this, "Shizuku not ready.", Toast.LENGTH_SHORT).show(); return@connect }
            Thread {
                try {
                    val err = if (prefs.targetMode == Prefs.MODE_VIRTUAL) {
                        val abs = Catalog.virtualAbs
                        svc.openVirtual("Thor SidePad", Catalog.virtualKeys,
                            abs.map { it.first }.toIntArray(), abs.map { it.second }.toIntArray(), abs.map { it.third }.toIntArray())
                    } else svc.openPhysical(prefs.physicalPath)
                    if (err.isNotEmpty()) { runOnUiThread { Toast.makeText(this, err, Toast.LENGTH_LONG).show() }; return@Thread }
                    if (prefs.targetMode == Prefs.MODE_VIRTUAL) Thread.sleep(400) // let Android enumerate the new device
                    svc.key(code, true); Thread.sleep(150); svc.key(code, false)
                    runOnUiThread { Toast.makeText(this, "Sent ${Catalog.byCode(code).androidName}", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Test failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
    }
}
