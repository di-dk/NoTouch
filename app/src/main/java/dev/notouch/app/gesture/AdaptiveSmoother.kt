package dev.notouch.app.gesture

/**
 * Low-pass filter whose strength depends on speed: slow movement is smoothed heavily (no jitter
 * while aiming), fast movement passes through almost unfiltered (no lag).
 */
class AdaptiveSmoother(private val minAlpha: Float = 0.2f, private val speedGain: Float = 6f) {
    private var prev: Pt? = null

    fun update(raw: Pt): Pt {
        val p = prev
        val out = if (p == null) raw else {
            val a = (minAlpha + speedGain * raw.dist(p)).coerceIn(minAlpha, 0.85f)
            Pt(p.x + a * (raw.x - p.x), p.y + a * (raw.y - p.y))
        }
        prev = out
        return out
    }

    fun reset() {
        prev = null
    }
}
