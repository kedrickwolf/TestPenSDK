package com.example.stylustest

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Request code for Shizuku shell permission. */
private const val REQUEST_SHIZUKU_PERMISSION = 1001

/**
 * Raw evdev monitor screen.
 *
 * Shows Shizuku status, auto-detects pen input devices, lets the user pick one,
 * streams `getevent -l <device>` through Shizuku, and displays every ABS_* axis
 * with its latest value and min/max range. A user-selectable axis (default
 * ABS_BRAKE) is shown as a 0..360 roll dial.
 */
class RawMonitorActivity : AppCompatActivity() {

    private lateinit var shizuku: ShizukuManager
    private lateinit var mainHandler: Handler

    // UI
    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var deviceLabel: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var axisContainer: LinearLayout
    private lateinit var rollDial: RollDialView
    private lateinit var rollAxisLabel: TextView

    // State
    private var reader: EvdevReader? = null
    private var detectedDevices: MutableList<String> = mutableListOf()
    private var selectedRollAxisCode: Int = AbsAxis.ABS_BRAKE
    private var axisViews: MutableMap<Int, TextView> = mutableMapOf()
    private var changedHighlightHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shizuku = ShizukuManager.get(this)
        mainHandler = Handler(Looper.getMainLooper())

        setContentView(buildUi())

        changedHighlightHandler = Handler(Looper.getMainLooper())

        refreshShizukuState()
    }

    override fun onResume() {
        super.onResume()
        refreshShizukuState()
    }

    override fun onDestroy() {
        super.onDestroy()
        reader?.stop()
        reader = null
        shizuku.unbind()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SHIZUKU_PERMISSION) {
            refreshShizukuState()
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        )
        root.setPadding(16, 16, 16, 16)

        // Title
        val title = TextView(this)
        title.text = "Raw Evdev Monitor (via Shizuku)"
        title.textSize = 20f
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(title)

        // Shizuku status
        statusText = TextView(this)
        statusText.text = "Checking Shizuku..."
        statusText.textSize = 16f
        root.addView(statusText)

        // Action button (install Shizuku / request permission / bind)
        actionButton = Button(this)
        actionButton.text = "Check Shizuku"
        actionButton.setOnClickListener { onActionClicked() }
        root.addView(actionButton)

        // Divider
        val div1 = View(this)
        div1.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        div1.setBackgroundColor(0xFF444444)
        root.addView(div1)

        // Device selection
        deviceLabel = TextView(this)
        deviceLabel.text = "Pen device: none detected"
        deviceLabel.textSize = 16f
        deviceLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(deviceLabel)

        deviceSpinner = Spinner(this)
        deviceSpinner.isEnabled = false
        root.addView(deviceSpinner)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        startButton = Button(this)
        startButton.text = "Start"
        startButton.isEnabled = false
        startButton.setOnClickListener { onStartClicked() }
        row.addView(startButton)

        stopButton = Button(this)
        stopButton.text = "Stop"
        stopButton.isEnabled = false
        stopButton.setOnClickListener { onStopClicked() }
        row.addView(stopButton)

        root.addView(row)

        // Roll axis selection
        val rollRow = LinearLayout(this)
        rollRow.orientation = LinearLayout.HORIZONTAL
        rollAxisLabel = TextView(this)
        rollAxisLabel.text = "Roll axis: ABS_BRAKE (default)"
        rollAxisLabel.textSize = 14f
        rollRow.addView(rollAxisLabel)

        val changeAxisBtn = Button(this)
        changeAxisBtn.text = "Change axis"
        changeAxisBtn.setOnClickListener { onChangeAxisClicked() }
        rollRow.addView(changeAxisBtn)
        root.addView(rollRow)

        // Roll dial
        rollDial = RollDialView(this)
        rollDial.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            300
        )
        root.addView(rollDial)

        // Axis list
        val axisLabel = TextView(this)
        axisLabel.text = "ABS axes:"
        axisLabel.textSize = 16f
        axisLabel.setTypeface(null, android.graphics.Typeface.BOLD)
        root.addView(axisLabel)

        val scroll = ScrollView(this)
        axisContainer = LinearLayout(this)
        axisContainer.orientation = LinearLayout.VERTICAL
        scroll.addView(axisContainer)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply { weight = 1f })

        return root
    }

    private fun onActionClicked() {
        val state = shizuku.state()
        when (state) {
            ShizukuState.NotInstalled -> {
                statusText.text = "Shizuku not installed. Install from Play Store or website."
                actionButton.text = "Install Shizuku"
                actionButton.setOnClickListener {
                    val intent = android.content.Intent(Intent.ACTION_VIEW)
                    intent.setData(android.net.Uri.parse("https://shizuku.rikka.app"))
                    startActivity(intent)
                }
            }
            ShizukuState.InstalledNotRunning -> {
                statusText.text = "Shizuku installed but not running. Enable it in developer options."
                actionButton.text = "Open Shizuku"
                actionButton.setOnClickListener {
                    val intent = android.content.Intent("dev.rikka.shizuku.START_ACTIVITY")
                    intent.package = "dev.rikka.shizuku"
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        statusText.text = "Could not open Shizuku app."
                    }
                }
            }
            ShizukuState.InstalledRunning -> {
                val perm = shizuku.checkShellPermission()
                when (perm) {
                    ShellPermissionState.Granted -> {
                        statusText.text = "Shizuku running, shell permission granted."
                        actionButton.text = "Detect devices"
                        actionButton.setOnClickListener { bindAndDetect() }
                    }
                    ShellPermissionState.Denied -> {
                        statusText.text = "Shell permission denied. Requesting..."
                        shizuku.requestShellPermission(this, REQUEST_SHIZUKU_PERMISSION)
                    }
                    ShellPermissionState.Unknown -> {
                        shizuku.requestShellPermission(this, REQUEST_SHIZUKU_PERMISSION)
                    }
                }
            }
            ShizukuState.Unknown -> {
                statusText.text = "Unknown Shizuku state."
            }
        }
    }

    private fun refreshShizukuState() {
        val state = shizuku.state()
        when (state) {
            ShizukuState.NotInstalled -> {
                statusText.text = "Shizuku not installed."
                actionButton.text = "Install Shizuku"
                deviceSpinner.isEnabled = false
                startButton.isEnabled = false
                stopButton.isEnabled = false
            }
            ShizukuState.InstalledNotRunning -> {
                statusText.text = "Shizuku installed but not running."
                actionButton.text = "Start Shizuku"
                deviceSpinner.isEnabled = false
                startButton.isEnabled = false
                stopButton.isEnabled = false
            }
            ShizukuState.InstalledRunning -> {
                val perm = shizuku.checkShellPermission()
                when (perm) {
                    ShellPermissionState.Granted -> {
                        statusText.text = "Shizuku running. Permission granted."
                        actionButton.text = "Detect devices"
                        actionButton.setOnClickListener { bindAndDetect() }
                        deviceSpinner.isEnabled = detectedDevices.isNotEmpty()
                        startButton.isEnabled = detectedDevices.isNotEmpty()
                    }
                    ShellPermissionState.Denied -> {
                        statusText.text = "Shell permission denied."
                        actionButton.text = "Request permission"
                        actionButton.setOnClickListener {
                            shizuku.requestShellPermission(this, REQUEST_SHIZUKU_PERMISSION)
                        }
                        deviceSpinner.isEnabled = false
                        startButton.isEnabled = false
                    }
                    ShellPermissionState.Unknown -> {
                        statusText.text = "Checking permission..."
                        shizuku.requestShellPermission(this, REQUEST_SHIZUKU_PERMISSION)
                    }
                }
            }
            ShizukuState.Unknown -> {
                statusText.text = "Unknown Shizuku state."
            }
        }
    }

    private fun bindAndDetect() {
        statusText.text = "Binding to Shizuku..."
        shizuku.bind { success ->
            if (!success) {
                mainHandler.post {
                    statusText.text = "Failed to bind to Shizuku."
                }
                return@bind
            }
            mainHandler.post {
                statusText.text = "Bound. Detecting pen devices..."
            }
            detectPenDevices()
        }
    }

    private fun detectPenDevices() {
        statusText.text = "Detecting pen devices..."
        // Run on background thread since runCommand blocks.
        Thread {
            // List event devices and read their names via sysfs.
            // \$d and \$(basename \$d) are escaped so they reach the shell literally.
            val cmd = "for d in /dev/input/event*; do echo \"== \$d\"; cat /sys/class/input/\$(basename \$d)/name 2>/dev/null; done"
            val output = shizuku.runCommand(cmd)
            val devices = parsePenDevices(output ?: "")
            mainHandler.post {
                detectedDevices = devices.toMutableList()
                deviceSpinner.isEnabled = devices.isNotEmpty()
                startButton.isEnabled = devices.isNotEmpty()
                if (devices.isEmpty()) {
                    deviceLabel.text = "No pen devices found."
                    statusText.text = "No pen input devices detected."
                } else {
                    val names = devices.joinToString(", ")
                    deviceLabel.text = "Pen devices: $names"
                    statusText.text = "Found ${devices.size} pen device(s)."
                    val adapter = ArrayAdapter(
                        this@RawMonitorActivity,
                        android.R.layout.simple_spinner_item,
                        devices
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    deviceSpinner.adapter = adapter
                }
            }
        }.start()
    }

    /**
     * Parses output like:
     *   == /dev/input/event8
     *   NVTCapacitivePenP81c
     *   == /dev/input/event9
     *   NVTCapacitivePenM80p
     */
    private fun parsePenDevices(output: String): List<String> {
        val devices = mutableListOf<String>()
        var currentDevice: String? = null
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("== /dev/input/")) {
                // "== /dev/input/event8" -> "/dev/input/event8"
                currentDevice = trimmed.substring(4, trimmed.length - 1)
            } else if (currentDevice != null && trimmed.contains("Pen", ignoreCase = true)) {
                devices.add(currentDevice!!)
                currentDevice = null
            }
        }
        return devices
    }

    private fun onStartClicked() {
        val selected = deviceSpinner.selectedItem as? String ?: return
        reader?.stop()
        reader = null
        axisContainer.removeAllViews()
        axisViews.clear()

        statusText.text = "Reading from $selected..."

        reader = EvdevReader(
            shizuku = shizuku,
            device = selected,
            onAxisUpdate = { info ->
                mainHandler.post {
                    updateAxisView(info)
                    if (info.code == selectedRollAxisCode) {
                        val degrees = if (info.max > info.min) {
                            ((info.latestValue - info.min).toFloat() /
                                (info.max - info.min)) * 360f
                        } else {
                            info.latestValue.toFloat()
                        }
                        rollDial.setRoll(degrees)
                    }
                }
            },
            onError = { err ->
                mainHandler.post {
                    statusText.text = "Error: $err"
                }
            }
        )
        reader?.start()

        startButton.isEnabled = false
        stopButton.isEnabled = true
        deviceSpinner.isEnabled = false
    }

    private fun onStopClicked() {
        reader?.stop()
        reader = null
        startButton.isEnabled = true
        stopButton.isEnabled = false
        deviceSpinner.isEnabled = true
        rollDial.setRoll(null)
        statusText.text = "Stopped."
    }

    private fun onChangeAxisClicked() {
        val items = AbsAxisValues.all.map { it.name }
        val currentIndex = AbsAxisValues.all.indexOfFirst { it.code == selectedRollAxisCode }.coerceAtLeast(0)
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select roll axis")
        builder.setSingleChoiceItems(items, currentIndex, null) { _, which ->
            selectedRollAxisCode = AbsAxisValues.all[which].code
            rollAxisLabel.text = "Roll axis: ${AbsAxisValues.all[which].name}"
        }
        builder.create().show()
    }

    private fun updateAxisView(info: AxisInfo) {
        val view = axisViews.getOrPut(info.code) {
            val tv = TextView(this@RawMonitorActivity)
            tv.textSize = 14f
            tv.setPadding(4, 4, 4, 4)
            axisContainer.addView(tv)
            tv
        }
        val name = AbsAxis.name(info.code)
        val changedMarker = if (System.currentTimeMillis() - info.changedAt < 500) " ▶" else ""
        view.text = "$name${changedMarker}: ${info.latestValue}  [min=${info.min} max=${info.max}]"

        // Brief highlight when value changes
        if (System.currentTimeMillis() - info.changedAt < 300) {
            view.setBackgroundColor(0x6600FF00)
            changedHighlightHandler?.postDelayed({ view.setBackgroundColor(0) }, 300)
        } else {
            view.setBackgroundColor(0)
        }
    }
}

/** Known ABS axis options for roll selection. */
private object AbsAxisValues {
    val all = listOf(
        AbsAxisEntry("ABS_BRAKE", AbsAxis.ABS_BRAKE),
        AbsAxisEntry("ABS_MISC", AbsAxis.ABS_MISC),
        AbsAxisEntry("ABS_RX", AbsAxis.ABS_RX),
        AbsAxisEntry("ABS_RY", AbsAxis.ABS_RY),
        AbsAxisEntry("ABS_RZ", AbsAxis.ABS_RZ)
    )
}

private data class AbsAxisEntry(val name: String, val code: Int)
