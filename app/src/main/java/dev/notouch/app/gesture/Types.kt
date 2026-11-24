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

enum class MediaKind { PLAY_PAUSE, NEXT, PREVIOUS }

/** What a recogniser saw. Each event is mapped to a [Command] (or nothing) through [Bindings]. */
enum class GestureEvent {
    PINCH, PALM_SWIPE_UP, PALM_SWIPE_DOWN, PALM_SWIPE_LEFT, PALM_SWIPE_RIGHT, PALM_HOLD, FIST_HOLD,
    TWO_FINGER_SWIPE_UP, TWO_FINGER_SWIPE_DOWN, TWO_FINGER_SWIPE_LEFT, TWO_FINGER_SWIPE_RIGHT,
    MOUTH_OPEN, BROWS_RAISED, SMILE_HOLD, PUCKER_HOLD, BLINK_HOLD,
}

sealed interface Command {
    /** Tap at a normalized screen position; null means "at the current cursor". */
    data class Tap(val at: Pt?) : Command
    data class Global(val kind: GlobalKind) : Command
    data class Swipe(val dir: SwipeDir) : Command
    data class Media(val kind: MediaKind) : Command
    data class Volume(val up: Boolean) : Command
}

/** [cursor] is a normalized screen position, or null when no cursor should be shown. */
data class Output(val cursor: Pt?, val commands: List<Command>) {
    companion object {
        val NONE = Output(null, emptyList())
    }
}

/** One face observation: nose tip plus blendshape scores by name (e.g. "jawOpen"). */
data class FaceFrame(val nose: Pt, val blend: Map<String, Float>)
