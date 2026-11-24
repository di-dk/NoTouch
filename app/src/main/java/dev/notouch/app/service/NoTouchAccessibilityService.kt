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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
    fun apply(cursor: Pt?, commands: List<Command>) {
        main.post {
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
        }
        dispatch(path, durationMs = 280)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
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

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - ring.strokeWidth
            canvas.drawCircle(cx, cy, r, halo)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, r / 4f, dot)
        }
    }

    companion object {
        private const val RING_DP = 44

        /** Non-null while the user has enabled the accessibility service. */
        @Volatile
        var instance: NoTouchAccessibilityService? = null
            private set
    }
}
