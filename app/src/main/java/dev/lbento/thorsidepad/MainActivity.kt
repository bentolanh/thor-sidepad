package dev.lbento.thorsidepad

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
import android.content.ComponentName
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.lbento.thorsidepad.inject.Injector
import dev.lbento.thorsidepad.pad.OverlayService
import dev.lbento.thorsidepad.pad.PresetStore
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

    /** While the system's draw-over-apps page is open, watch for the grant and come back on our own. */
    private val overlayWatch = object : Runnable {
        var until = 0L
        override fun run() {
            if (overlayOk()) {
                startActivity(Intent(this@MainActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return
            }
            if (System.currentTimeMillis() < until) window.decorView.postDelayed(this, 500)
        }
    }
    private fun startOverlayWatch() {
        overlayWatch.until = System.currentTimeMillis() + 3 * 60 * 1000
        window.decorView.removeCallbacks(overlayWatch)
        window.decorView.postDelayed(overlayWatch, 500)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        displaySpinner = findViewById(R.id.displaySpinner)

        findViewById<Button>(R.id.step1Button).setOnClickListener { shizukuAction() }
        findViewById<Button>(R.id.step2Button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            // Some firmware (the Thor's included) opens the full app list instead of SidePad's own page.
            Toast.makeText(this, "Find Thor SidePad in the list and turn it on. SidePad comes back by itself.", Toast.LENGTH_LONG).show()
            startOverlayWatch()
        }
        findViewById<Button>(R.id.step3Button).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val firstRun = !prefs.guideShown
            OverlayService.send(this, OverlayService.ACTION_START)
            if (firstRun) Toast.makeText(this, "Look at the bottom screen: the guide is waiting there.", Toast.LENGTH_LONG).show()
            // The service comes up a moment later; refresh once it has.
            findViewById<View>(R.id.btnStart).postDelayed({ refresh() }, 900)
        }
        findViewById<Button>(R.id.btnTogglePad).setOnClickListener {
            OverlayService.send(this, OverlayService.ACTION_TOGGLE)
        }
        findViewById<Button>(R.id.btnPanel).setOnClickListener {
            OverlayService.send(this, OverlayService.ACTION_PANEL)
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportPresets.launch("thor-sidepad-presets.json") }
        findViewById<Button>(R.id.btnImport).setOnClickListener { importPresets.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
        findViewById<Button>(R.id.btnGuide).setOnClickListener {
            OverlayService.send(this, OverlayService.ACTION_GUIDE)
            Toast.makeText(this, "Look at the bottom screen.", Toast.LENGTH_SHORT).show()
        }

        // The two launcher icons, drawn the way a round launcher shows them; tap one to use it.
        findViewById<android.widget.ImageView>(R.id.iconFamicom).setImageDrawable(roundIcon(R.mipmap.ic_launcher_famicom))
        findViewById<android.widget.ImageView>(R.id.iconGameBoy).setImageDrawable(roundIcon(R.mipmap.ic_launcher_gameboy))
        findViewById<android.widget.ImageView>(R.id.iconFamicom).setOnClickListener { setIcon("LauncherFamicom"); markIcon() }
        findViewById<android.widget.ImageView>(R.id.iconGameBoy).setOnClickListener { setIcon("LauncherGameBoy"); markIcon() }
        markIcon()

        val boot = findViewById<Switch>(R.id.bootSwitch)
        boot.isChecked = prefs.startAtBoot
        boot.setOnCheckedChangeListener { _, on -> prefs.startAtBoot = on }

        val bubble = findViewById<Switch>(R.id.bubbleSwitch)
        bubble.isChecked = prefs.bubble
        bubble.setOnCheckedChangeListener { _, on ->
            prefs.bubble = on
            // START puts the button up or takes it down straight away, rather than at the next
            // time something else happens to ask.
            OverlayService.send(this, OverlayService.ACTION_START)
        }

        fillDisplays()
        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) { prefs.displayId = displayIds.getOrNull(pos) ?: -1 }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    override fun onResume() { super.onResume(); lastResumedAt = System.currentTimeMillis(); window.decorView.removeCallbacks(overlayWatch); refresh() }

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
        val why = "Shizuku is needed to press buttons on this device: Android does not let one app do that for another, and Shizuku lends SidePad the same access a computer has over USB debugging. No root needed.\n\n" +
            "It is not needed to use the Thor as a controller for another machine. That goes out over Bluetooth, which any app may do, so you can skip this step if that is all you want. The brightness and volume sliders and the media controls need it either way.\n\n"
        when {
            !shizukuInstalled() -> {
                shizukuReady = false
                s1status.text = "Not installed. Only needed to press buttons on this device."
                s1help.text = why + "Shizuku is a small free app. Install it, then come back here."
                s1btn.text = "Install Shizuku"
            }
            Injector.state() == Injector.ShizukuState.NOT_RUNNING -> {
                shizukuReady = false
                s1status.text = "Installed but not running."
                s1help.text = why + "Open Shizuku and choose “Start via Wireless debugging”. The first time it asks you to enable Wireless debugging in Developer options and to pair once with a code; after that it is one tap. Then come back here."
                s1btn.text = "Open Shizuku"
            }
            Injector.state() == Injector.ShizukuState.NO_PERMISSION -> {
                shizukuReady = false
                s1status.text = "Running, not allowed yet."
                s1help.text = why + "Shizuku will ask whether SidePad may use it. Choose “Allow all the time”."
                s1btn.text = "Grant SidePad access to Shizuku"
            }
            else -> {
                shizukuReady = true
                s1status.text = "Running and allowed."
                s1help.text = "Shizuku stops when the Thor reboots. In Shizuku, turn on “Start on boot (wireless debugging)” so it comes back on its own."
            }
        }
        s1btn.visibility = if (shizukuReady) View.GONE else View.VISIBLE
        setDone(R.id.step1, R.id.step1Title, R.id.step1Status, shizukuReady, locked = false)

        // Step 2: overlay.
        findViewById<TextView>(R.id.step2Status).text = if (overlayOk()) "Allowed." else "Not allowed yet."
        findViewById<Button>(R.id.step2Button).visibility = if (overlayOk()) View.GONE else View.VISIBLE
        setDone(R.id.step2, R.id.step2Title, R.id.step2Status, overlayOk(), locked = false)

        // Step 3: notifications, optional; never locks anything.
        findViewById<TextView>(R.id.step3Status).text = if (notifOk()) "Allowed." else "Not allowed yet. You can skip this."
        findViewById<Button>(R.id.step3Button).visibility = if (notifOk()) View.GONE else View.VISIBLE
        setDone(R.id.step3, R.id.step3Title, R.id.step3Status, notifOk(), locked = !overlayOk())

        // Step 4: start. Only the overlay permission is required; Shizuku decides what the pad can
        // drive, not whether it can run, because sending to another machine does not use it.
        val canRun = overlayOk()
        findViewById<TextView>(R.id.serviceStatus).text = when {
            !canRun -> "Finish step 2 first."
            OverlayService.running && !shizukuReady ->
                "SidePad is running. Without Shizuku it can only send to another machine."
            OverlayService.running -> "SidePad is running in the background."
            !shizukuReady -> "SidePad is not running. Without Shizuku it will only send to another machine."
            else -> "SidePad is not running."
        }
        findViewById<Button>(R.id.btnStart).isEnabled = canRun && !OverlayService.running
        findViewById<Button>(R.id.btnGuide).isEnabled = canRun && OverlayService.running
        setDone(R.id.step4, R.id.step4Title, R.id.serviceStatus, canRun && OverlayService.running, locked = !canRun)
    }

    /**
     * Three clear looks: a finished step is green with a tick and no button, the step to do next is
     * highlighted in blue, and a locked step is greyed out.
     */
    private fun setDone(cardId: Int, titleId: Int, statusId: Int, done: Boolean, locked: Boolean) {
        val card = findViewById<View>(cardId)
        val title = findViewById<TextView>(titleId)
        val status = findViewById<TextView>(statusId)
        val base = title.text.toString().removeSuffix("  ✓")
        // A locked step's buttons must not react either (step 4 manages its own two buttons).
        if (cardId != R.id.step4 && card is android.view.ViewGroup) for (i in 0 until card.childCount) (card.getChildAt(i) as? Button)?.let { b -> if (!done) b.isEnabled = !locked }
        when {
            done -> {
                card.alpha = 1f; card.setBackgroundColor(0xFF1E3A2A.toInt())
                title.text = "$base  ✓"; title.setTextColor(0xFF7BE495.toInt()); status.setTextColor(0xFF7BE495.toInt())
            }
            locked -> {
                card.alpha = 0.4f; card.setBackgroundColor(0xFF1F262B.toInt())
                title.text = base; title.setTextColor(0xFFFFFFFF.toInt()); status.setTextColor(0xFFB0B8C0.toInt())
            }
            else -> {
                card.alpha = 1f; card.setBackgroundColor(0xFF1F2F45.toInt())
                title.text = base; title.setTextColor(0xFF8FC1FF.toInt()); status.setTextColor(0xFFFFFFFF.toInt())
            }
        }
    }

    // ---- preset files. The system file picker does the choosing; we only read and write the bytes.
    private val exportPresets = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(PresetStore.exportJson(this).toByteArray()) }
            Toast.makeText(this, "Presets exported.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not write the file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private val importPresets = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val n = PresetStore.importJson(this, text)
            Toast.makeText(this, if (n == 1) "1 preset imported." else "$n presets imported.", Toast.LENGTH_SHORT).show()
            // The pad reads its layout when it shows, so a running SidePad re-shows with the imported one.
            if (OverlayService.running) OverlayService.send(this, OverlayService.ACTION_SHOW)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read that file: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    /** Renders an adaptive icon inside a circle, like most launchers do. */
    private fun roundIcon(resId: Int): android.graphics.drawable.Drawable {
        val d = androidx.core.content.res.ResourcesCompat.getDrawable(resources, resId, theme)!!
        val px = (56 * resources.displayMetrics.density).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply { addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW) }
        c.clipPath(path)
        // Adaptive icons carry 1/3 of extra canvas; draw the full 108 so the middle 72 fills the circle.
        val full = (px * 1.5f).toInt(); val off = (full - px) / 2
        d.setBounds(-off, -off, full - off, full - off); d.draw(c)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }

    /** Rings the icon that is currently in use. */
    private fun markIcon() {
        val gb = iconEnabled("LauncherGameBoy")
        fun ring(on: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x00000000); setStroke((3 * resources.displayMetrics.density).toInt(), if (on) 0xFF8FC1FF.toInt() else 0x00000000)
        }
        findViewById<android.widget.ImageView>(R.id.iconFamicom).background = ring(!gb)
        findViewById<android.widget.ImageView>(R.id.iconGameBoy).background = ring(gb)
    }

    // ---- launcher icon: two aliases of the same activity, one enabled at a time

    private fun alias(name: String) = ComponentName(this, "$packageName.$name")

    private fun iconEnabled(name: String): Boolean =
        packageManager.getComponentEnabledSetting(alias(name)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setIcon(chosen: String) {
        val pm = packageManager
        for (name in listOf("LauncherFamicom", "LauncherGameBoy")) {
            pm.setComponentEnabledSetting(alias(name),
                if (name == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
        Toast.makeText(this, "Icon changed. Some launchers take a moment to notice.", Toast.LENGTH_SHORT).show()
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
