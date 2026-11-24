package dev.notouch.app.gesture

/**
 * Turns a continuous signal into discrete firings.
 *
 * - Hysteresis: becomes active at `value >= enter`, inactive at `value <= exit`.
 * - [holdMs]: the signal must stay active this long before the first firing.
 * - [repeatMs]: if > 0, keeps firing at this interval while held; otherwise fires once per activation.
 * - [cooldownMs]: minimum gap between any two firings.
 */
class HoldTrigger(
    private val enter: Float,
    private val exit: Float,
    private val holdMs: Long = 0,
    private val repeatMs: Long = 0,
    private val cooldownMs: Long = 0,
) {
    private var active = false
    private var since = 0L
    private var fired = false
    private var lastFire = Long.MIN_VALUE / 2

    fun update(value: Float, t: Long): Boolean {
        if (!active) {
            if (value >= enter) {
                active = true
                since = t
                fired = false
            } else {
                return false
            }
        } else if (value <= exit) {
            active = false
            fired = false
            return false
        }
        if (t - since < holdMs) return false
        if (t - lastFire < cooldownMs) return false
        if (!fired) {
            fired = true
            lastFire = t
            return true
        }
        if (repeatMs > 0 && t - lastFire >= repeatMs) {
            lastFire = t
            return true
        }
        return false
    }

    fun reset() {
        active = false
        fired = false
    }
}
