package dev.notouch.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.os.SystemClock
import dev.notouch.app.gesture.Pt
import dev.notouch.app.service.DebugBus
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import dev.notouch.app.gesture.Mode
import dev.notouch.app.service.CameraGestureService
import dev.notouch.app.service.NoTouchAccessibilityService

/** Setup + control screen: permissions, mode, start/stop and a gesture cheat sheet. */
class MainActivity : Activity() {

    private lateinit var cameraStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var cameraButton: Button
    private lateinit var a11yButton: Button
    private lateinit var toggleButton: Button
    private lateinit var liveView: LiveView
    private lateinit var liveCheck: CheckBox
    private val ui = Handler(Looper.getMainLooper())
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            if (liveView.visibility == View.VISIBLE) liveView.invalidate()
            if (++ticks % 12 == 0) refresh() // about once a second: picks up the services starting or dying
            ui.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
        DebugBus.enabled = liveCheck.isChecked
        ui.post(tick)
    }

    override fun onPause() {
        DebugBus.enabled = false // stop building preview frames when not on screen
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        refresh()
    }

    // ---- state ------------------------------------------------------------------------

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun a11yEnabled(): Boolean {
        val me = ComponentName(this, NoTouchAccessibilityService::class.java).flattenToString()
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
    }

    private fun tint(button: Button, color: Int) {
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.setTextColor(Color.WHITE)
    }

    private fun refresh() {
        val cam = hasCamera()
        cameraStatus.text = if (cam) "Camera permission: granted" else "Camera permission: needed"
        cameraStatus.setTextColor(if (cam) GREEN else RED)
        cameraButton.visibility = if (cam) View.GONE else View.VISIBLE
        tint(cameraButton, AMBER)

        val a11y = SetupState.a11y(a11yEnabled(), NoTouchAccessibilityService.instance != null)
        when (a11y) {
            A11yState.ON -> {
                a11yStatus.text = "Accessibility service: on and connected"
                a11yButton.text = "Accessibility settings"
                tint(a11yButton, GREEN)
            }
            A11yState.STALLED -> {
                a11yStatus.text = "Accessibility service: switched on but not running (Android stopped it). " +
                    "Turn it off and on again. If this keeps happening, set NoTouch to \"No restrictions\" under battery settings."
                a11yButton.text = "Turn off and on again"
                tint(a11yButton, AMBER)
            }
            A11yState.OFF -> {
                a11yStatus.text = "Accessibility service: off (needed to tap and swipe for you)"
                a11yButton.text = "Turn on in accessibility settings"
                tint(a11yButton, RED)
            }
        }
        a11yStatus.setTextColor(when (a11y) { A11yState.ON -> GREEN; A11yState.STALLED -> AMBER; A11yState.OFF -> RED })

        val run = SetupState.run(cam, a11y, CameraGestureService.running)
        toggleButton.text = when (run) {
            RunState.RUNNING -> "Stop gestures"
            RunState.READY -> "Start gestures"
            RunState.BLOCKED -> "Start gestures (finish the steps above first)"
        }
        toggleButton.isEnabled = run != RunState.BLOCKED
        tint(toggleButton, when (run) { RunState.RUNNING -> RED; RunState.READY -> GREEN; RunState.BLOCKED -> GREY })
    }

    // ---- ui ---------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        col.addView(text("NoTouch", 30f, bold = true))
        col.addView(text("Control your phone with hand and face gestures from the front camera. Everything runs on the device; no video leaves it."))

        // 1. permissions
        col.addView(text("1. Permissions", 18f, bold = true))
        cameraStatus = text("")
        col.addView(cameraStatus)
        cameraButton = Button(this).apply {
            text = "Allow camera"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
        }
        col.addView(cameraButton)
        a11yStatus = text("")
        col.addView(a11yStatus)
        a11yButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        col.addView(a11yButton)
        col.addView(text("If the service is greyed out after sideloading: Settings > Apps > NoTouch > menu (three dots) > Allow restricted settings, then try again.", 13f))

        // 2. mode
        col.addView(text("2. Gestures to use", 18f, bold = true))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modes = listOf(Mode.HAND to "Hand", Mode.FACE to "Face and head", Mode.BOTH to "Both")
        val current = Settings.mode(this)
        modes.forEachIndexed { i, (m, label) ->
            group.addView(RadioButton(this).apply {
                id = 100 + i
                text = label
                isChecked = m == current
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            Settings.setMode(this, modes[id - 100].first)
            if (CameraGestureService.running) CameraGestureService.start(this) // apply immediately
        }
        col.addView(group)
        col.addView(CheckBox(this).apply {
            text = "Show on-screen pointer"
            isChecked = Settings.showCursor(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Settings.setShowCursor(this@MainActivity, v) }
        })

        col.addView(text("Sensitivity (higher = expressions and head movement trigger more easily)", 13f))
        val sensLabel = text("", 14f)
        col.addView(sensLabel)
        fun showSens(v: Float) { sensLabel.text = "Sensitivity: ${(v * 100).toInt()}%" }
        showSens(Settings.sensitivity(this))
        col.addView(SeekBar(this).apply {
            max = 200 // 50% .. 250%
            progress = (Settings.sensitivity(this@MainActivity) * 100).toInt() - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = (p + 50) / 100f
                    Settings.setSensitivity(this@MainActivity, v)
                    showSens(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) = Unit
                override fun onStopTrackingTouch(sb: SeekBar) = Unit
            })
        })
        col.addView(CheckBox(this).apply {
            text = "Use GPU (experimental, restart gestures to apply)"
            isChecked = Settings.useGpu(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Settings.setUseGpu(this@MainActivity, v) }
        })

        // 3. start / stop
        col.addView(text("3. Run", 18f, bold = true))
        toggleButton = Button(this).apply {
            setOnClickListener {
                if (CameraGestureService.running) {
                    CameraGestureService.stop(this@MainActivity)
                } else {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        // The notification holds the Pause / Stop buttons, so ask for it.
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                    }
                    CameraGestureService.start(this@MainActivity)
                }
                it.postDelayed({ refresh() }, 400)
            }
        }
        col.addView(toggleButton)
        col.addView(text("While running, a notification has Pause and Stop buttons. Pause turns the camera off.", 13f))

        liveView = LiveView(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(420))
        }
        liveCheck = CheckBox(this).apply {
            text = "Show live camera view (see what the app sees)"
            setOnCheckedChangeListener { _, v ->
                liveView.visibility = if (v) View.VISIBLE else View.GONE
                DebugBus.enabled = v
            }
        }
        col.addView(liveCheck)
        col.addView(liveView)

        col.addView(Button(this).apply {
            text = "Apps: auto-start and per-app gestures"
            setOnClickListener { startActivity(Intent(this@MainActivity, AppsActivity::class.java)) }
        })
        col.addView(Button(this).apply {
            text = "Customize gesture actions"
            setOnClickListener { startActivity(Intent(this@MainActivity, BindingsActivity::class.java)) }
        })

        // cheat sheet (shows the default actions; use "Customize gesture actions" to change them)
        col.addView(text("Hand gestures (defaults)", 18f, bold = true))
        col.addView(text(
            "- Point with one finger: moves the pointer\n" +
                "- Push the pointing finger toward the camera: click\n" +
                "- Thumb and index touching, then spread: zoom in (reverse: zoom out)\n" +
                "- Thumb and index apart, twist: rotate\n" +
                "- Open palm, swipe up / down: scroll\n" +
                "- Open palm, swipe left: swipe left\n" +
                "- Open palm, swipe right: Back\n" +
                "- Open palm held still 1 s: Home\n" +
                "- Fist held 1 s: Recents\n" +
                "- Two fingers (index + middle), swipe any direction: swipe on screen",
        ))
        col.addView(text("Face gestures", 18f, bold = true))
        col.addView(text(
            "- Move your head: moves the pointer\n" +
                "- Open mouth: tap\n" +
                "- Raise eyebrows: Back\n" +
                "- Hold a smile: scroll down\n" +
                "- Hold pursed lips: scroll up\n" +
                "- Close both eyes for 1 s: Home\n" +
                "(Use Recenter in the notification to reset the neutral head position.)",
        ))
        col.addView(text("If a hand and a face are both visible, the hand controls the pointer.", 13f))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        }
    }

    private companion object {
        const val GREEN = 0xFF2E7D32.toInt()
        const val RED = 0xFFC62828.toInt()
        const val AMBER = 0xFFEF6C00.toInt()
        const val GREY = 0xFF9E9E9E.toInt()
    }
}


/** Draws the analysed camera frame with the detected hand / face points and live expression meters. */
private class LiveView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; strokeWidth = 2f * d }
    private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val nosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 3f * d
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * d }
    private val bg = Paint().apply { color = 0xCC000000.toInt() }
    private val barBg = Paint().apply { color = 0x55FFFFFF }
    private val barFg = Paint().apply { color = 0xFF3D5AFE.toInt() }

    private val bones = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11,
        11 to 12, 9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
    )

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLACK })
        val s = DebugBus.snapshot
        if (s == null) {
            c.drawText("Waiting for frames... (start gestures first)", 12f * d, 28f * d, text)
            return
        }
        // fit the frame, keep aspect
        val scale = minOf(width.toFloat() / s.frame.width, height.toFloat() / s.frame.height)
        val w = s.frame.width * scale
        val h = s.frame.height * scale
        val r = RectF((width - w) / 2f, 0f, (width + w) / 2f, h)
        c.drawBitmap(s.frame, null, r, null)
        fun px(p: Pt) = r.left + p.x * r.width() to r.top + p.y * r.height()

        s.hand?.let { lm ->
            if (lm.size >= 21) {
                for ((a, b) in bones) {
                    val (x1, y1) = px(lm[a]); val (x2, y2) = px(lm[b])
                    c.drawLine(x1, y1, x2, y2, bone)
                }
                for (p in lm) { val (x, y) = px(p); c.drawCircle(x, y, 3f * d, joint) }
            }
        }
        s.nose?.let { val (x, y) = px(it); c.drawCircle(x, y, 6f * d, nosePaint) }
        s.cursor?.let { c.drawCircle(r.left + it.x * r.width(), r.top + it.y * r.height(), 12f * d, cursorPaint) }

        // header
        c.drawRect(0f, 0f, width.toFloat(), 22f * d, bg)
        val ago = (SystemClock.uptimeMillis() - DebugBus.lastCommandAt) / 1000f
        val last = if (DebugBus.lastCommandAt == 0L) "-" else "%.1fs ago".format(ago)
        c.drawText("%.0f fps  %s  hand: %s  face: %s".format(s.fps, s.delegate, s.pose, if (s.nose != null) "yes" else "no"), 6f * d, 16f * d, text)

        // expression meters (values already include the sensitivity scaling)
        val b = s.blend
        fun v(n: String) = b[n] ?: 0f
        val rows = listOf(
            "jaw open -> tap" to v("jawOpen"),
            "brows up -> back" to (v("browInnerUp") + (v("browOuterUpLeft") + v("browOuterUpRight")) / 2f) / 2f,
            "smile -> scroll dn" to (v("mouthSmileLeft") + v("mouthSmileRight")) / 2f,
            "pucker -> scroll up" to v("mouthPucker"),
            "eyes shut -> home" to minOf(v("eyeBlinkLeft"), v("eyeBlinkRight")),
        )
        val rowH = 18f * d
        val top = height - rowH * (rows.size + 1) - 4f * d
        c.drawRect(0f, top - 2f * d, width.toFloat(), height.toFloat(), bg)
        rows.forEachIndexed { i, (label, value) ->
            val y = top + rowH * (i + 1)
            c.drawText(label, 6f * d, y - 4f * d, text)
            val x0 = width * 0.45f; val x1 = width - 8f * d
            c.drawRect(x0, y - 14f * d, x1, y - 4f * d, barBg)
            c.drawRect(x0, y - 14f * d, x0 + (x1 - x0) * value.coerceIn(0f, 1f), y - 4f * d, barFg)
        }
        c.drawText("last command: ${DebugBus.lastCommand}  ($last)", 6f * d, top + 0f, text)
    }
}
