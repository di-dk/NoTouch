package dev.notouch.app.gesture

import kotlin.math.hypot

/**
 * A point in normalized coordinates (0..1).
 *
 * Camera frames are converted to an *upright, mirrored* image before detection (see
 * CameraGestureService), so x grows towards the user's right and y grows downwards,
 * exactly like a selfie preview.
 */
data class Pt(val x: Float, val y: Float) {
    fun dist(o: Pt): Float = hypot(x - o.x, y - o.y)
}

enum class Mode { HAND, FACE, BOTH }

enum class GlobalKind { BACK, HOME, RECENTS, NOTIFICATIONS }

/** Direction of the *finger* swipe injected on screen (UP scrolls the content down). */
enum class SwipeDir { UP, DOWN, LEFT, RIGHT }

sealed interface Command {
    /** Tap at a normalized screen position; null means "at the current cursor". */
    data class Tap(val at: Pt?) : Command
    data class Global(val kind: GlobalKind) : Command
    data class Swipe(val dir: SwipeDir) : Command
}

/** [cursor] is a normalized screen position, or null when no cursor should be shown. */
data class Output(val cursor: Pt?, val commands: List<Command>) {
    companion object {
        val NONE = Output(null, emptyList())
    }
}

/** One face observation: nose tip plus blendshape scores by name (e.g. "jawOpen"). */
data class FaceFrame(val nose: Pt, val blend: Map<String, Float>)
