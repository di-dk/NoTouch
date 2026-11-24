package dev.notouch.app.gesture

import kotlin.math.abs

enum class HandPose { PALM, FIST, POINT, PINCH, OTHER }

/**
 * Turns 21 hand landmarks per frame (MediaPipe layout, upright + mirrored) into commands.
 *
 *  - Point (index only)            -> moves the cursor
 *  - Pinch (thumb + index)         -> tap at the cursor
 *  - Open palm, swipe up/down/left -> scroll / swipe on screen
 *  - Open palm, swipe right        -> Back
 *  - Open palm held still          -> Home
 *  - Fist held                     -> Recents
 */
class HandGestureRecognizer {

    var pose: HandPose = HandPose.OTHER
        private set

    private var pinching = false
    private var smoothed: Pt? = null
    private val smoother = AdaptiveSmoother()
    private val cursorHistory = ArrayDeque<Pair<Long, Pt>>()
    private val palmTrail = ArrayDeque<Pair<Long, Pt>>()
    private var lastSwipeAt = Long.MIN_VALUE / 2

    private val tapTrigger = HoldTrigger(0.5f, 0.5f, holdMs = TAP_CONFIRM_MS, cooldownMs = 600)
    private val homeTrigger = HoldTrigger(0.5f, 0.5f, holdMs = HOLD_MS, cooldownMs = 1500)
    private val recentsTrigger = HoldTrigger(0.5f, 0.5f, holdMs = HOLD_MS, cooldownMs = 1500)

    fun reset() {
        pose = HandPose.OTHER
        pinching = false
        smoothed = null
        smoother.reset()
        cursorHistory.clear()
        palmTrail.clear()
        tapTrigger.reset()
        homeTrigger.reset()
        recentsTrigger.reset()
    }

    fun process(t: Long, lm: List<Pt>?): Output {
        if (lm == null || lm.size < 21) {
            reset()
            return Output.NONE
        }
        val wrist = lm[WRIST]
        val scale = wrist.dist(lm[MIDDLE_MCP]).coerceAtLeast(1e-3f)

        fun extended(tip: Int, pip: Int) = lm[tip].dist(wrist) > EXTENDED_RATIO * lm[pip].dist(wrist)
        val index = extended(INDEX_TIP, INDEX_PIP)
        val middle = extended(MIDDLE_TIP, MIDDLE_PIP)
        val ring = extended(RING_TIP, RING_PIP)
        val pinky = extended(PINKY_TIP, PINKY_PIP)
        val othersCurled = !middle && !ring && !pinky

        val pinchRatio = lm[THUMB_TIP].dist(lm[INDEX_TIP]) / scale
        val indexReach = lm[INDEX_TIP].dist(wrist) / scale
        if (!pinching && pinchRatio < PINCH_ENTER) pinching = true
        else if (pinching && pinchRatio > PINCH_EXIT) pinching = false

        pose = when {
            // indexReach separates a pinch from a fist whose thumb happens to touch the index.
            pinching && othersCurled && indexReach > MIN_PINCH_REACH -> HandPose.PINCH
            index && othersCurled -> HandPose.POINT
            index && middle && ring && pinky -> HandPose.PALM
            !index && !middle && !ring && !pinky -> HandPose.FIST
            else -> HandPose.OTHER
        }

        val commands = ArrayList<Command>(2)

        // --- cursor + tap ---------------------------------------------------------------
        var cursor: Pt? = null
        if (pose == HandPose.POINT || pose == HandPose.PINCH) {
            val raw = toScreen(lm[INDEX_TIP])
            if (pose == HandPose.POINT || smoothed == null) {
                smoothed = smoother.update(raw)
                cursorHistory.addLast(t to smoothed!!)
                while (cursorHistory.isNotEmpty() && t - cursorHistory.first().first > 1000) cursorHistory.removeFirst()
            } // while pinching the cursor is frozen: the fingertip drifts as the fingers close
            cursor = smoothed
        } else {
            smoothed = null
            smoother.reset()
            cursorHistory.clear()
        }
        if (tapTrigger.update(if (pose == HandPose.PINCH) 1f else 0f, t)) {
            commands += Command.Tap(positionAt(t - TAP_LOOKBACK_MS) ?: cursor)
        }

        // --- palm: swipe + hold -----------------------------------------------------------
        var still = false
        if (pose == HandPose.PALM) {
            val center = lm[MIDDLE_MCP]
            palmTrail.addLast(t to center)
            while (palmTrail.isNotEmpty() && t - palmTrail.first().first > TRAIL_MS) palmTrail.removeFirst()

            if (palmTrail.size >= 3 && t - lastSwipeAt > SWIPE_COOLDOWN_MS) {
                val (t0, p0) = palmTrail.first()
                val span = t - t0
                if (span >= MIN_SWIPE_SPAN_MS) {
                    val dx = center.x - p0.x
                    val dy = center.y - p0.y
                    val dist = center.dist(p0)
                    val speed = dist / (span / 1000f)
                    if (dist >= MIN_SWIPE_DIST && speed >= MIN_SWIPE_SPEED) {
                        val ax = abs(dx)
                        val ay = abs(dy)
                        val cmd: Command? = when {
                            ax >= AXIS_RATIO * ay ->
                                if (dx > 0) Command.Global(GlobalKind.BACK) else Command.Swipe(SwipeDir.LEFT)
                            ay >= AXIS_RATIO * ax ->
                                if (dy < 0) Command.Swipe(SwipeDir.UP) else Command.Swipe(SwipeDir.DOWN)
                            else -> null // diagonal: ambiguous, ignore
                        }
                        if (cmd != null) {
                            commands += cmd
                            lastSwipeAt = t
                            palmTrail.clear()
                            homeTrigger.reset()
                        }
                    }
                }
            }
            if (palmTrail.size >= 3 && t - palmTrail.first().first >= STILL_SPAN_MS) {
                still = palmTrail.all { it.second.dist(center) < STILL_RADIUS }
            }
        } else {
            palmTrail.clear()
        }

        if (homeTrigger.update(if (still) 1f else 0f, t)) {
            commands += Command.Global(GlobalKind.HOME)
        }
        if (recentsTrigger.update(if (pose == HandPose.FIST) 1f else 0f, t)) {
            commands += Command.Global(GlobalKind.RECENTS)
        }

        return Output(cursor, commands)
    }

    /** Latest recorded cursor sample at or before [time], else the oldest sample. */
    private fun positionAt(time: Long): Pt? {
        var best: Pt? = null
        for ((st, p) in cursorHistory) {
            if (st <= time) best = p else break
        }
        return best ?: cursorHistory.firstOrNull()?.second
    }

    /** The hand can't comfortably reach the camera edges, so stretch an inner window to the screen. */
    private fun toScreen(p: Pt) = Pt(
        ((p.x - MARGIN_X) / (1f - 2 * MARGIN_X)).coerceIn(0f, 1f),
        ((p.y - MARGIN_Y) / (1f - 2 * MARGIN_Y)).coerceIn(0f, 1f),
    )

    companion object {
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_PIP = 6
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val MIDDLE_PIP = 10
        const val MIDDLE_TIP = 12
        const val RING_PIP = 14
        const val RING_TIP = 16
        const val PINKY_PIP = 18
        const val PINKY_TIP = 20

        // Tunables - these need adjusting on a real device.
        const val EXTENDED_RATIO = 1.1f
        const val PINCH_ENTER = 0.35f
        const val PINCH_EXIT = 0.50f
        const val MIN_PINCH_REACH = 1.1f
        const val MARGIN_X = 0.15f
        const val MARGIN_Y = 0.20f
        const val TAP_CONFIRM_MS = 60L
        const val TAP_LOOKBACK_MS = 250L
        const val TRAIL_MS = 450L
        const val MIN_SWIPE_SPAN_MS = 120L
        const val MIN_SWIPE_DIST = 0.18f
        const val MIN_SWIPE_SPEED = 0.7f
        const val AXIS_RATIO = 1.6f
        const val SWIPE_COOLDOWN_MS = 900L
        const val STILL_SPAN_MS = 300L
        const val STILL_RADIUS = 0.05f
        const val HOLD_MS = 1000L
    }
}
