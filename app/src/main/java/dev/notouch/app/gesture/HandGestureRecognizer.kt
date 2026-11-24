package dev.notouch.app.gesture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

enum class HandPose { PALM, PEACE, FIST, POINT, PINCH, OTHER }

/**
 * Turns 21 hand landmarks per frame (MediaPipe layout, upright + mirrored) into commands.
 *
 *  - Point (index only)            -> moves the cursor
 *  - Push the pointing finger toward the camera (hand grows quickly) -> click where the cursor was
 *  - Thumb + index touch then spread / spread then touch -> zoom in / zoom out
 *  - Thumb + index held apart and twisted                -> rotate clockwise / counterclockwise
 *  - Open palm, swipe up/down/left -> scroll / swipe on screen
 *  - Open palm, swipe right        -> Back
 *  - Open palm held still          -> Home
 *  - Fist held                     -> Recents
 *  - Two fingers (index + middle), swipe -> volume / previous / next by default
 *
 * Every action goes through [bindings], so the user can remap any gesture.
 */
class HandGestureRecognizer {

    var bindings = Bindings.DEFAULT

    var pose: HandPose = HandPose.OTHER
        private set

    private var pinching = false
    private var smoothed: Pt? = null
    private val smoother = AdaptiveSmoother()
    private val cursorHistory = ArrayDeque<Pair<Long, Pt>>()
    private class Sample(val t: Long, val p: Pt, val two: Boolean)
    private val palmTrail = ArrayDeque<Sample>()
    private var lastSwipeAt = Long.MIN_VALUE / 2
    private var lastSwipePoseAt = Long.MIN_VALUE / 2
    private var lastSeenAt = Long.MIN_VALUE / 2

    /** For the on-phone diagnostics log only. */
    var debug = ""
        private set

    private val scaleHistory = ArrayDeque<Pair<Long, Float>>()
    private val pinchHistory = ArrayDeque<Pair<Long, Float>>()
    private var lastPushAt = Long.MIN_VALUE / 2
    private var growth = 1f
    private val angleHistory = ArrayDeque<Triple<Long, Float, Float>>() // time, angle (rad), pinch ratio
    private var lastZoomAt = Long.MIN_VALUE / 2
    private var lastRotateAt = Long.MIN_VALUE / 2
    private val homeTrigger = HoldTrigger(0.5f, 0.5f, holdMs = HOLD_MS, cooldownMs = 1500)
    private val recentsTrigger = HoldTrigger(0.5f, 0.5f, holdMs = HOLD_MS, cooldownMs = 1500)

    fun reset() {
        pose = HandPose.OTHER
        lastSwipePoseAt = Long.MIN_VALUE / 2
        pinching = false
        smoothed = null
        smoother.reset()
        cursorHistory.clear()
        palmTrail.clear()
        scaleHistory.clear()
        pinchHistory.clear()
        homeTrigger.reset()
        recentsTrigger.reset()
    }

    fun process(t: Long, lm: List<Pt>?): Output {
        if (lm == null || lm.size < 21) {
            if (t - lastSeenAt > DROPOUT_GRACE_MS) {
                reset()
            } else {
                // a brief detection dropout must not wipe a swipe or zoom in progress, but never bridges a hold
                pose = HandPose.OTHER
                pinching = false
                homeTrigger.reset()
                recentsTrigger.reset()
            }
            return Output.NONE
        }
        lastSeenAt = t
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
            index && middle && !ring && !pinky -> HandPose.PEACE
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
        // --- click: the pointing finger is pushed toward the camera, so the hand quickly looks bigger ---
        if (pose == HandPose.POINT) {
            scaleHistory.addLast(t to scale)
            while (scaleHistory.isNotEmpty() && t - scaleHistory.first().first > PUSH_WINDOW_MS) scaleHistory.removeFirst()
            val smallest = scaleHistory.minOf { it.second }
            growth = scale / smallest
            val before = positionAt(t - PUSH_WINDOW_MS)
            val travel = if (before != null && cursor != null) before.dist(cursor) else 0f
            if (scale >= PUSH_RATIO * smallest && travel <= PUSH_MAX_TRAVEL && t - lastPushAt > PUSH_COOLDOWN_MS) {
                bindings.resolve(GestureEvent.PUSH_CLICK, positionAt(t - PUSH_LOOKBACK_MS) ?: cursor)?.let { commands += it }
                lastPushAt = t
                scaleHistory.clear()
            }
        } else {
            scaleHistory.clear()
            growth = 1f
        }

        // --- zoom: thumb and index touch then open (zoom in) or open then touch (zoom out) ---------------
        if (index && othersCurled) {
            pinchHistory.addLast(t to pinchRatio)
            while (pinchHistory.isNotEmpty() && t - pinchHistory.first().first > ZOOM_WINDOW_MS) pinchHistory.removeFirst()
            if (t - lastZoomAt > ZOOM_COOLDOWN_MS) {
                val lowest = pinchHistory.minOf { it.second }
                val highest = pinchHistory.maxOf { it.second }
                val event = when {
                    pinchRatio >= ZOOM_OPEN && lowest <= PINCH_ENTER -> GestureEvent.ZOOM_IN
                    pinchRatio <= PINCH_ENTER && highest >= ZOOM_OPEN -> GestureEvent.ZOOM_OUT
                    else -> null
                }
                if (event != null) {
                    bindings.resolve(event, cursor ?: positionAt(t))?.let { commands += it }
                    lastZoomAt = t
                    pinchHistory.clear()
                }
            }

            // --- rotate: the thumb-to-index line turns while the two stay roughly the same distance apart ---
            val angle = atan2(lm[INDEX_TIP].y - lm[THUMB_TIP].y, lm[INDEX_TIP].x - lm[THUMB_TIP].x)
            angleHistory.addLast(Triple(t, angle, pinchRatio))
            while (angleHistory.isNotEmpty() && t - angleHistory.first().first > ROTATE_WINDOW_MS) angleHistory.removeFirst()
            if (pinchRatio >= ROTATE_MIN_OPEN && t - lastRotateAt > ROTATE_COOLDOWN_MS && angleHistory.size >= 3) {
                val ratios = angleHistory.map { it.third }
                if (ratios.min() >= ROTATE_MIN_OPEN && ratios.max() - ratios.min() <= ROTATE_MAX_RATIO_CHANGE) {
                    var turn = angle - angleHistory.first().second
                    while (turn > PI) turn -= (2 * PI).toFloat()
                    while (turn < -PI) turn += (2 * PI).toFloat()
                    if (abs(turn) >= ROTATE_MIN_RAD) {
                        val event = if (turn > 0) GestureEvent.ROTATE_CW else GestureEvent.ROTATE_CCW
                        bindings.resolve(event, cursor ?: positionAt(t))?.let { commands += it }
                        lastRotateAt = t
                        angleHistory.clear()
                    }
                }
            } else if (pinchRatio < ROTATE_MIN_OPEN) {
                angleHistory.clear()
            }
        } else {
            pinchHistory.clear()
            angleHistory.clear()
        }

        // --- palm / two fingers: swipe, and palm held still -------------------------------------
        var still = false
        val swipePose = pose == HandPose.PALM || pose == HandPose.PEACE
        if (swipePose) {
            lastSwipePoseAt = t
        } else if (t - lastSwipePoseAt > POSE_FLICKER_GRACE_MS) {
            palmTrail.clear()
        }
        if (swipePose) {
            val center = lm[MIDDLE_MCP]
            palmTrail.addLast(Sample(t, center, pose == HandPose.PEACE))
            while (palmTrail.isNotEmpty() && t - palmTrail.first().t > TRAIL_MS) palmTrail.removeFirst()

            if (palmTrail.size >= 3 && t - lastSwipeAt > SWIPE_COOLDOWN_MS) {
                val first = palmTrail.first()
                val span = t - first.t
                if (span >= MIN_SWIPE_SPAN_MS) {
                    val two = palmTrail.count { it.two } * 2 > palmTrail.size // finger counts flicker mid-swipe: go by majority
                    val dx = center.x - first.p.x
                    val dy = center.y - first.p.y
                    val dist = center.dist(first.p)
                    val speed = dist / (span / 1000f)
                    if (dist >= MIN_SWIPE_DIST && speed >= MIN_SWIPE_SPEED) {
                        val ax = abs(dx)
                        val ay = abs(dy)
                        val event: GestureEvent? = when {
                            ax >= AXIS_RATIO * ay -> when {
                                dx > 0 -> if (two) GestureEvent.TWO_FINGER_SWIPE_RIGHT else GestureEvent.PALM_SWIPE_RIGHT
                                else -> if (two) GestureEvent.TWO_FINGER_SWIPE_LEFT else GestureEvent.PALM_SWIPE_LEFT
                            }
                            ay >= AXIS_RATIO * ax -> when {
                                dy < 0 -> if (two) GestureEvent.TWO_FINGER_SWIPE_UP else GestureEvent.PALM_SWIPE_UP
                                else -> if (two) GestureEvent.TWO_FINGER_SWIPE_DOWN else GestureEvent.PALM_SWIPE_DOWN
                            }
                            else -> null // diagonal: ambiguous, ignore
                        }
                        if (event != null) {
                            bindings.resolve(event)?.let { commands += it }
                            lastSwipeAt = t
                            palmTrail.clear()
                            homeTrigger.reset()
                        }
                    }
                }
            }
            if (pose == HandPose.PALM && palmTrail.size >= 3 && t - palmTrail.first().t >= STILL_SPAN_MS) {
                still = palmTrail.all { it.p.dist(center) < STILL_RADIUS }
            }
        }

        if (homeTrigger.update(if (still) 1f else 0f, t)) {
            bindings.resolve(GestureEvent.PALM_HOLD)?.let { commands += it }
        }
        if (recentsTrigger.update(if (pose == HandPose.FIST) 1f else 0f, t)) {
            bindings.resolve(GestureEvent.FIST_HOLD)?.let { commands += it }
        }

        debug = "pose=$pose ratio=${"%.2f".format(pinchRatio)} grow=${"%.2f".format(growth)} fingers=${if (index) "I" else "-"}${if (middle) "M" else "-"}${if (ring) "R" else "-"}${if (pinky) "P" else "-"}"
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
        const val PUSH_WINDOW_MS = 300L
        const val PUSH_LOOKBACK_MS = 250L
        const val PUSH_RATIO = 1.18f
        const val PUSH_MAX_TRAVEL = 0.15f
        const val PUSH_COOLDOWN_MS = 700L
        const val ZOOM_WINDOW_MS = 900L
        const val DROPOUT_GRACE_MS = 200L
        const val POSE_FLICKER_GRACE_MS = 250L
        const val ZOOM_OPEN = 0.9f
        const val ZOOM_COOLDOWN_MS = 800L
        const val ROTATE_WINDOW_MS = 700L
        const val ROTATE_MIN_OPEN = 0.6f
        const val ROTATE_MAX_RATIO_CHANGE = 0.3f
        const val ROTATE_MIN_RAD = 0.7f // about 40 degrees
        const val ROTATE_COOLDOWN_MS = 900L
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
