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
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.linhhan.thorsidepad.inject.Injector
import dev.linhhan.thorsidepad.pad.OverlayService
import rikka.shizuku.Shizuku

/**
 * Setup only: permissions and a few device-level settings. Everything about the pad itself
 * (target controller, looks, layout) lives in the pull-down panel on the pad's screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var shizukuStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var serviceStatus: TextView
    private lateinit var displaySpinner: Spinner
    private var displayIds: List<Int> = emptyList()

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        shizukuStatus = findViewById(R.id.shizukuStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        notifStatus = findViewById(R.id.notifStatus)
        serviceStatus = findViewById(R.id.serviceStatus)
        displaySpinner = findViewById(R.id.displaySpinner)

        findViewById<Button>(R.id.btnShizuku).setOnClickListener { requestShizuku() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            OverlayService.send(this, OverlayService.ACTION_START)
            Toast.makeText(this, "SidePad is running. On the second screen: pull down for its panel, pull up to show the pad.", Toast.LENGTH_LONG).show()
            refreshStatus()
        }

        val boot = findViewById<Switch>(R.id.bootSwitch)
        boot.isChecked = prefs.startAtBoot
        boot.setOnCheckedChangeListener { _, on -> prefs.startAtBoot = on }

        fillDisplays()
        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                prefs.displayId = displayIds.getOrNull(pos) ?: -1
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun refreshStatus() {
        shizukuStatus.text = when (Injector.state()) {
            Injector.ShizukuState.NOT_RUNNING -> "Shizuku: not running. Start it in the Shizuku app (wireless debugging), or from a computer with adb (see README)."
            Injector.ShizukuState.NO_PERMISSION -> "Shizuku: running, access not granted yet."
            Injector.ShizukuState.READY -> "Shizuku: ready."
        }
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "Draw over other apps: granted." else "Draw over other apps: NOT granted."
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notifStatus.text = if (notifOk) "Notifications: granted (the pad's notification has Show / Edit / Stop)." else "Notifications: NOT granted."
        serviceStatus.text = if (OverlayService.running) "SidePad is running in the background." else "SidePad is not running."
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
        val labels = ArrayList<String>(); val ids = ArrayList<Int>()
        labels.add("Auto: the display that is not the main one"); ids.add(-1)
        for (d in dm.displays) {
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") d.getRealMetrics(m)
            val tag = if (d.displayId == Display.DEFAULT_DISPLAY) " (main)" else ""
            labels.add("Display ${d.displayId}: ${d.name} ${m.widthPixels}x${m.heightPixels}$tag"); ids.add(d.displayId)
        }
        displayIds = ids
        displaySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = ids.indexOf(prefs.displayId); if (idx >= 0) displaySpinner.setSelection(idx)
    }
}
