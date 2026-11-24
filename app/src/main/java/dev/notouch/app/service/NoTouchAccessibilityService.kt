package dev.notouch.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioManager
import android.view.KeyEvent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.notouch.app.Settings
import dev.notouch.app.gesture.Command
import dev.notouch.app.gesture.MediaKind
import dev.notouch.app.gesture.GlobalKind
import dev.notouch.app.gesture.Pt
import dev.notouch.app.gesture.SwipeDir

/**
 * Executes gesture commands on the whole device: taps and swipes via dispatchGesture, Back / Home /
 * Recents via global actions, and draws the pointer ring as an accessibility overlay (no extra
 * "draw over other apps" permission needed). It never reads screen content.
 */
class NoTouchAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var windowManager: WindowManager? = null
    private var cursorView: CursorView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorShown = false
    private var screenW = 0
    private var screenH = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        refreshScreenSize()
        val size = (RING_DP * resources.displayMetrics.density).toInt()
        cursorView = CursorView(this).also { it.visibility = View.GONE }
        cursorParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Anchor (0,0) to the physical display corner, the same origin dispatchGesture uses,
            // so the ring is drawn exactly where a tap will land.
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        runCatching { windowManager?.addView(cursorView, cursorParams) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Keyboards, system UI and other non-launchable packages are not "the app you are using".
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return
        foregroundPackage = pkg
        if (!CameraGestureService.running) autoStarted = false
        when (AutoStart.decide(pkg, packageName, Settings.autoStartApps(this), CameraGestureService.running, autoStarted)) {
            AutoStartAction.START -> {
                autoStarted = true
                runCatching { CameraGestureService.start(this) }
                    .onFailure { autoStarted = false; Log.w(TAG, "auto-start blocked", it) }
            }
            AutoStartAction.STOP -> { autoStarted = false; CameraGestureService.stop(this) }
            AutoStartAction.NONE -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        if (instance === this) instance = null
        runCatching { cursorView?.let { windowManager?.removeView(it) } }
        cursorView = null
    }

    /** Called from the camera thread; hops to the main thread. */
    fun apply(cursor: Pt?, commands: List<Command>, dwell: Float = 0f) {
        main.post {
            cursorView?.dwell = dwell
            updateCursor(cursor)
            commands.forEach(::execute)
        }
    }

    private fun updateCursor(cursor: Pt?) {
        val view = cursorView ?: return
        val params = cursorParams ?: return
        if (cursor == null || !Settings.showCursor(this)) {
            if (cursorShown) {
                view.visibility = View.GONE
                cursorShown = false
            }
            return
        }
        refreshScreenSize()
        val half = params.width / 2
        params.x = (cursor.x * screenW).toInt() - half
        params.y = (cursor.y * screenH).toInt() - half
        if (!cursorShown) {
            view.visibility = View.VISIBLE
            cursorShown = true
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun execute(c: Command) {
        Log.i(TAG, "execute $c")
        when (c) {
            is Command.Tap -> c.at?.let { tap(it) }
            is Command.Global -> performGlobalAction(
                when (c.kind) {
                    GlobalKind.BACK -> GLOBAL_ACTION_BACK
                    GlobalKind.HOME -> GLOBAL_ACTION_HOME
                    GlobalKind.RECENTS -> GLOBAL_ACTION_RECENTS
                    GlobalKind.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                },
            )
            is Command.Swipe -> swipe(c.dir)
            is Command.Media -> media(c.kind)
            is Command.Zoom -> zoom(c)
            is Command.Rotate -> rotate(c)
            is Command.Volume -> audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (c.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    private fun media(kind: MediaKind) {
        val code = when (kind) {
            MediaKind.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaKind.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKind.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun tap(p: Pt) {
        refreshScreenSize()
        val path = Path().apply { moveTo(p.x * screenW, p.y * screenH) }
        dispatch(path, durationMs = 60)
    }

    private fun swipe(dir: SwipeDir) {
        refreshScreenSize()
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val path = Path()
        when (dir) {
            SwipeDir.UP -> { path.moveTo(w * 0.5f, h * 0.72f); path.lineTo(w * 0.5f, h * 0.28f) }
            SwipeDir.DOWN -> { path.moveTo(w * 0.5f, h * 0.28f); path.lineTo(w * 0.5f, h * 0.72f) }
            SwipeDir.LEFT -> { path.moveTo(w * 0.8f, h * 0.5f); path.lineTo(w * 0.2f, h * 0.5f) }
            SwipeDir.RIGHT -> { path.moveTo(w * 0.2f, h * 0.5f); path.lineTo(w * 0.8f, h * 0.5f) }
            else -> { // diagonals: through the centre, along the direction's signs
                val sx = if (dir == SwipeDir.UP_RIGHT || dir == SwipeDir.DOWN_RIGHT) 1f else -1f
                val sy = if (dir == SwipeDir.DOWN_LEFT || dir == SwipeDir.DOWN_RIGHT) 1f else -1f
                path.moveTo(w * 0.5f - sx * w * 0.3f, h * 0.5f - sy * h * 0.22f)
                path.lineTo(w * 0.5f + sx * w * 0.3f, h * 0.5f + sy * h * 0.22f)
            }
        }
        dispatch(path, durationMs = 280)
    }

    /** Two fingers moving apart (zoom in) or together (zoom out) around the given point. */
    private fun zoom(c: Command.Zoom) {
        refreshScreenSize()
        val cx = (c.at?.x ?: 0.5f) * screenW
        val cy = (c.at?.y ?: 0.5f) * screenH
        val far = minOf(0.3f * screenW, cx - 8f, screenW - cx - 8f).coerceAtLeast(40f)
        val near = far * 0.2f
        val (from, to) = if (c.zoomIn) near to far else far to near
        fun finger(sign: Float) = Path().apply { moveTo(cx + sign * from, cy); lineTo(cx + sign * to, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(finger(-1f), 0, 350))
            .addStroke(GestureDescription.StrokeDescription(finger(1f), 0, 350))
            .build()
        send(gesture)
    }

    /** Two fingers on opposite sides of a point, both turning the same way through about 70 degrees. */
    private fun rotate(c: Command.Rotate) {
        refreshScreenSize()
        val cx = (c.at?.x ?: 0.5f) * screenW
        val cy = (c.at?.y ?: 0.5f) * screenH
        val radius = minOf(0.2f * screenW, cx - 8f, screenW - cx - 8f, cy - 8f, screenH - cy - 8f).coerceAtLeast(40f)
        val sweep = Math.toRadians(if (c.clockwise) 70.0 else -70.0)
        fun finger(startAngle: Double) = Path().apply {
            for (i in 0..12) {
                val a = startAngle + sweep * i / 12
                val x = cx + (radius * Math.cos(a)).toFloat()
                val y = cy + (radius * Math.sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(finger(0.0), 0, 400))
            .addStroke(GestureDescription.StrokeDescription(finger(Math.PI), 0, 400))
            .build()
        send(gesture)
    }

    private fun send(gesture: GestureDescription) {
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { Log.i(TAG, "gesture completed") }
                override fun onCancelled(g: GestureDescription?) { Log.w(TAG, "gesture cancelled by the system") }
            },
            null,
        )
        if (!accepted) Log.w(TAG, "dispatchGesture refused the gesture")
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        send(gesture)
    }

    @Suppress("DEPRECATION")
    private fun refreshScreenSize() {
        val dm = DisplayMetrics()
        windowManager?.defaultDisplay?.getRealMetrics(dm)
        if (dm.widthPixels > 0) {
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    private class CursorView(context: Context) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * context.resources.displayMetrics.density
            color = 0xFF3D5AFE.toInt()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f * context.resources.displayMetrics.density
            color = 0x66FFFFFF
        }

        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 5f * context.resources.displayMetrics.density
            color = 0xFF00E676.toInt()
        }

        /** 0..1 progress of a hold-still click; drawn as a green arc around the ring. */
        var dwell = 0f
            set(v) {
                if (v != field) {
                    field = v
                    invalidate()
                }
            }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - ring.strokeWidth
            canvas.drawCircle(cx, cy, r, halo)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, r / 4f, dot)
            if (dwell > 0f) {
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * dwell, false, progressPaint)
            }
        }
    }

    companion object {
        private const val TAG = "NoTouch"
        private const val RING_DP = 44

        /** Non-null while the user has enabled the accessibility service. */
        @Volatile
        var instance: NoTouchAccessibilityService? = null

        /** Package of the app in front, from window-change events (null until the first one). */
        @Volatile var foregroundPackage: String? = null

        @Volatile private var autoStarted = false
            private set
    }
}
