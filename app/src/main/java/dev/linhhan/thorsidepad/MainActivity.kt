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
import android.view.View
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
 * Setup only, as an ordered checklist: Shizuku (install, start, grant), draw over other apps,
 * notifications (optional), start. Everything about the pad itself lives in the pull-down panel
 * on the pad's screen.
 */
class MainActivity : AppCompatActivity() {
    companion object {
        @Volatile var lastResumedAt = 0L
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }

    private lateinit var prefs: Prefs
    private lateinit var displaySpinner: Spinner
    private var displayIds: List<Int> = emptyList()

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        displaySpinner = findViewById(R.id.displaySpinner)

        findViewById<Button>(R.id.step1Button).setOnClickListener { shizukuAction() }
        findViewById<Button>(R.id.step2Button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.step3Button).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val firstRun = !prefs.guideShown
            OverlayService.send(this, OverlayService.ACTION_START)
            if (firstRun) Toast.makeText(this, "Look at the bottom screen: the guide is waiting there.", Toast.LENGTH_LONG).show()
            refresh()
        }
        findViewById<Button>(R.id.btnGuide).setOnClickListener {
            OverlayService.send(this, OverlayService.ACTION_GUIDE)
            Toast.makeText(this, "Look at the bottom screen.", Toast.LENGTH_SHORT).show()
        }

        val boot = findViewById<Switch>(R.id.bootSwitch)
        boot.isChecked = prefs.startAtBoot
        boot.setOnCheckedChangeListener { _, on -> prefs.startAtBoot = on }

        fillDisplays()
        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) { prefs.displayId = displayIds.getOrNull(pos) ?: -1 }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() { super.onResume(); lastResumedAt = System.currentTimeMillis(); refresh() }

    /** Leaving the app on the second screen would leave focus there; hand it back to the game's screen. */
    override fun onPause() {
        super.onPause()
        val onSecondScreen = (display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY
        if (onSecondScreen && OverlayService.running) OverlayService.send(this, OverlayService.ACTION_FOCUS_TOP)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh()
    }

    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permListener); super.onDestroy() }

    // ---- state

    private fun shizukuInstalled(): Boolean = try { packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true } catch (_: Exception) { false }
    private fun overlayOk() = Settings.canDrawOverlays(this)
    private fun notifOk() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        // Step 1: Shizuku, three sub-states.
        val s1status = findViewById<TextView>(R.id.step1Status)
        val s1help = findViewById<TextView>(R.id.step1Help)
        val s1btn = findViewById<Button>(R.id.step1Button)
        val shizukuReady: Boolean
        when {
            !shizukuInstalled() -> {
                shizukuReady = false
                s1status.text = "Not installed."
                s1help.text = "Shizuku is a small free app that lets SidePad send controller presses without root. Install it, then come back here."
                s1btn.text = "Install Shizuku"
            }
            Injector.state() == Injector.ShizukuState.NOT_RUNNING -> {
                shizukuReady = false
                s1status.text = "Installed, but not running."
                s1help.text = "Open Shizuku and choose “Start via Wireless debugging”. The first time it asks you to enable Wireless debugging in Developer options and to pair once with a code; after that it is one tap. Then come back here."
                s1btn.text = "Open Shizuku"
            }
            Injector.state() == Injector.ShizukuState.NO_PERMISSION -> {
                shizukuReady = false
                s1status.text = "Running. SidePad needs your permission to use it."
                s1help.text = "Shizuku will ask whether SidePad may use it. Choose “Allow all the time”."
                s1btn.text = "Grant SidePad access to Shizuku"
            }
            else -> {
                shizukuReady = true
                s1status.text = "Ready."
                s1help.text = "Shizuku stops when the Thor reboots. In Shizuku, turn on “Start on boot (wireless debugging)” so it comes back on its own."
                s1btn.text = "Open Shizuku"
            }
        }
        setDone(R.id.step1, R.id.step1Title, shizukuReady, locked = false)

        // Step 2: overlay.
        findViewById<TextView>(R.id.step2Status).text = if (overlayOk()) "Allowed." else "Not allowed yet."
        findViewById<Button>(R.id.step2Button).isEnabled = !overlayOk()
        setDone(R.id.step2, R.id.step2Title, overlayOk(), locked = !shizukuReady)

        // Step 3: notifications, optional; never locks anything.
        findViewById<TextView>(R.id.step3Status).text = if (notifOk()) "Allowed." else "Not allowed. Optional."
        findViewById<Button>(R.id.step3Button).isEnabled = !notifOk()
        setDone(R.id.step3, R.id.step3Title, notifOk(), locked = !(shizukuReady && overlayOk()))

        // Step 4: start, needs 1 and 2.
        val canRun = shizukuReady && overlayOk()
        findViewById<TextView>(R.id.serviceStatus).text = when {
            !canRun -> "Finish steps 1 and 2 first."
            OverlayService.running -> "SidePad is running in the background."
            else -> "SidePad is not running."
        }
        findViewById<Button>(R.id.btnStart).isEnabled = canRun
        findViewById<Button>(R.id.btnGuide).isEnabled = canRun
        setDone(R.id.step4, R.id.step4Title, canRun && OverlayService.running, locked = !canRun)
    }

    /** Greys a step that is locked, and marks a finished one with a tick in its title. */
    private fun setDone(cardId: Int, titleId: Int, done: Boolean, locked: Boolean) {
        val card = findViewById<View>(cardId)
        val title = findViewById<TextView>(titleId)
        card.alpha = if (locked) 0.45f else 1f
        val base = title.text.toString().removeSuffix("  ✓")
        title.text = if (done) "$base  ✓" else base
    }

    private fun shizukuAction() {
        when {
            !shizukuInstalled() -> {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))) }
                catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
            }
            Injector.state() == Injector.ShizukuState.NO_PERMISSION -> {
                try { Shizuku.requestPermission(1) } catch (e: Throwable) { Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            else -> {
                val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                if (launch != null) startActivity(launch) else Toast.makeText(this, "Could not open Shizuku.", Toast.LENGTH_SHORT).show()
            }
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
