package dev.notouch.app.gesture

/**
 * Combines the hand and face recognisers.
 *
 * Precedence: while a hand is visible it owns the cursor (the face cursor is suppressed so the two
 * never fight); face *expressions* still work. Taps without an explicit position land on the
 * cursor that is currently shown.
 */
class GestureEngine(var mode: Mode = Mode.BOTH) {
    private val hand = HandGestureRecognizer()
    private val face = FaceGestureRecognizer()
    private var lastCursor: Pt? = null

    var faceGainScale: Float
        get() = face.gainScale
        set(v) { face.gainScale = v }

    val handPose: HandPose get() = hand.pose

    fun recenterFace() = face.recenter()

    fun reset() {
        hand.reset()
        face.reset()
        lastCursor = null
    }

    fun process(t: Long, handLandmarks: List<Pt>?, faceFrame: FaceFrame?): Output {
        val handOut = if (mode != Mode.FACE) hand.process(t, handLandmarks) else Output.NONE.also { hand.reset() }
        val faceOut = if (mode != Mode.HAND) face.process(t, faceFrame) else Output.NONE.also { face.reset() }

        val handPresent = mode != Mode.FACE && handLandmarks != null
        val cursor = if (handPresent) handOut.cursor else faceOut.cursor
        lastCursor = cursor

        val commands = ArrayList<Command>(handOut.commands.size + faceOut.commands.size)
        for (c in handOut.commands + faceOut.commands) {
            if (c is Command.Tap) {
                val at = c.at ?: cursor ?: continue
                commands += Command.Tap(at)
            } else {
                commands += c
            }
        }
        return Output(cursor, commands)
    }
}
