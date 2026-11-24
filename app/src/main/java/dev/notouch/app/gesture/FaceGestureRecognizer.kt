package dev.notouch.app.gesture

/**
 * Head movement drives the cursor; facial expressions trigger actions.
 *
 *  - Move head           -> cursor (relative to the pose when the face was first seen / last recentered)
 *  - Open mouth          -> tap at the cursor
 *  - Raise eyebrows      -> Back
 *  - Smile (held)        -> scroll down (repeats while held)
 *  - Pucker lips (held)  -> scroll up (repeats while held)
 *  - Close both eyes 1s  -> Home
 */
class FaceGestureRecognizer {

    private var center: Pt? = null
    private var recenterRequested = true
    private var smoothed: Pt? = null
    private val smoother = AdaptiveSmoother()

    var bindings = Bindings.DEFAULT

    /** Head-to-cursor gain multiplier (the sensitivity setting). */
    var gainScale = 1f

    private val tap = HoldTrigger(enter = 0.4f, exit = 0.2f, cooldownMs = 600)
    private val back = HoldTrigger(enter = 0.4f, exit = 0.2f, holdMs = 100, cooldownMs = 1200)
    private val scrollDown = HoldTrigger(enter = 0.5f, exit = 0.3f, holdMs = 400, repeatMs = 900)
    private val scrollUp = HoldTrigger(enter = 0.45f, exit = 0.25f, holdMs = 400, repeatMs = 900)
    private val home = HoldTrigger(enter = 0.6f, exit = 0.35f, holdMs = 1000, cooldownMs = 2000)

    /** Treat the next observed head position as the neutral "screen centre". */
    fun recenter() {
        recenterRequested = true
    }

    fun reset() {
        smoothed = null
        smoother.reset()
        recenterRequested = true // re-calibrate when the face comes back
        tap.reset(); back.reset(); scrollDown.reset(); scrollUp.reset(); home.reset()
    }

    fun process(t: Long, f: FaceFrame?): Output {
        if (f == null) {
            reset()
            return Output.NONE
        }
        if (recenterRequested || center == null) {
            center = f.nose
            recenterRequested = false
            smoothed = null
            smoother.reset()
        }
        val c = center!!
        val raw = Pt(
            (0.5f + (f.nose.x - c.x) * GAIN_X * gainScale).coerceIn(0f, 1f),
            (0.5f + (f.nose.y - c.y) * GAIN_Y * gainScale).coerceIn(0f, 1f),
        )
        val cursor = smoother.update(raw)
        smoothed = cursor

        fun b(name: String) = f.blend[name] ?: 0f
        val jaw = b("jawOpen")
        val brow = (b("browInnerUp") + (b("browOuterUpLeft") + b("browOuterUpRight")) / 2f) / 2f
        var smile = (b("mouthSmileLeft") + b("mouthSmileRight")) / 2f
        var pucker = b("mouthPucker")
        val blink = minOf(b("eyeBlinkLeft"), b("eyeBlinkRight"))
        if (jaw > 0.3f) { // an open mouth often drags the other mouth shapes along
            smile = 0f
            pucker = 0f
        }

        val commands = ArrayList<Command>(2)
        fun fire(event: GestureEvent) { bindings.resolve(event)?.let { commands += it } }
        if (tap.update(jaw, t)) fire(GestureEvent.MOUTH_OPEN)
        if (back.update(brow, t)) fire(GestureEvent.BROWS_RAISED)
        if (scrollDown.update(smile, t)) fire(GestureEvent.SMILE_HOLD)
        if (scrollUp.update(pucker, t)) fire(GestureEvent.PUCKER_HOLD)
        if (home.update(blink, t)) fire(GestureEvent.BLINK_HOLD)
        return Output(cursor, commands)
    }

    companion object {
        const val GAIN_X = 5f
        const val GAIN_Y = 6f
    }
}
