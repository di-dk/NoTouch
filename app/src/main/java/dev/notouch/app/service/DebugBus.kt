package dev.notouch.app.service

import android.graphics.Bitmap
import dev.notouch.app.gesture.Pt

/** In-process hand-off of the latest analysed frame so the app can show what the model sees. */
object DebugBus {
    class Snapshot(
        val frame: Bitmap,
        val hand: List<Pt>?,
        val nose: Pt?,
        val blend: Map<String, Float>,
        val pose: String,
        val fps: Float,
        val delegate: String,
        val cursor: Pt?,
    )

    /** Only build snapshots while the live view is on screen. */
    @Volatile var enabled = false
    @Volatile var snapshot: Snapshot? = null
    @Volatile var lastCommand: String = "-"
    @Volatile var lastCommandAt: Long = 0L
}
