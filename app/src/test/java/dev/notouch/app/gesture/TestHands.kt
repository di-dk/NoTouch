package dev.notouch.app.gesture

/** Synthetic hand landmarks (upright, mirrored space). Hand size: wrist -> middle MCP = 0.2. */
object TestHands {
    enum class Shape { PALM, FIST, POINT, PINCH, PEACE }

    fun hand(shape: Shape, cx: Float = 0.5f, cy: Float = 0.5f): List<Pt> {
        val lm = MutableList(21) { Pt(cx, cy) }
        lm[0] = Pt(cx, cy + 0.2f) // wrist
        // thumb chain (1..4): by default held away from the index finger
        lm[1] = Pt(cx - 0.05f, cy + 0.15f)
        lm[2] = Pt(cx - 0.09f, cy + 0.11f)
        lm[3] = Pt(cx - 0.12f, cy + 0.08f)
        lm[4] = Pt(cx - 0.15f, cy + 0.05f)

        val xs = mapOf(5 to -0.06f, 9 to -0.02f, 13 to 0.02f, 17 to 0.06f)
        fun finger(mcp: Int, extended: Boolean) {
            val x = cx + xs.getValue(mcp)
            lm[mcp] = Pt(x, cy)
            lm[mcp + 1] = Pt(x, cy - 0.08f)                     // PIP
            lm[mcp + 2] = if (extended) Pt(x, cy - 0.14f) else Pt(x, cy - 0.02f)
            lm[mcp + 3] = if (extended) Pt(x, cy - 0.20f) else Pt(x, cy + 0.05f) // tip
        }
        val (i, m, r, p) = when (shape) {
            Shape.PALM -> listOf(true, true, true, true)
            Shape.FIST -> listOf(false, false, false, false)
            Shape.POINT, Shape.PINCH -> listOf(true, false, false, false)
            Shape.PEACE -> listOf(true, true, false, false)
        }
        finger(5, i); finger(9, m); finger(13, r); finger(17, p)
        if (shape == Shape.PINCH) {
            // thumb tip touches the index tip
            lm[4] = Pt(lm[8].x + 0.01f, lm[8].y + 0.01f)
        }
        return lm
    }

    /** Scale a hand about its index fingertip, like pushing the pointing finger toward the camera. */
    fun pushed(lm: List<Pt>, factor: Float): List<Pt> {
        val tip = lm[8]
        return lm.map { Pt(tip.x + (it.x - tip.x) * factor, tip.y + (it.y - tip.y) * factor) }
    }

    /** Put the thumb tip [r] away from the index tip, at [angle] radians (image space, y down). */
    fun thumbAt(lm: List<Pt>, r: Float, angle: Float): List<Pt> =
        lm.toMutableList().also { it[4] = Pt(it[8].x - r * kotlin.math.cos(angle), it[8].y - r * kotlin.math.sin(angle)) }

    /** Shift a whole hand. */
    fun moved(lm: List<Pt>, dx: Float, dy: Float) = lm.map { Pt(it.x + dx, it.y + dy) }
}
