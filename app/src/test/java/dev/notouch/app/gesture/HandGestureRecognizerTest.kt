package dev.notouch.app.gesture

import dev.notouch.app.gesture.TestHands.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGestureRecognizerTest {

    private val frameMs = 33L

    /** Feed [hands] (one per frame) starting at [t0]; returns all commands and the final time. */
    private fun run(
        rec: HandGestureRecognizer,
        hands: List<List<Pt>?>,
        t0: Long = 0,
    ): Pair<List<Command>, Long> {
        val cmds = ArrayList<Command>()
        var t = t0
        for (h in hands) {
            cmds += rec.process(t, h).commands
            t += frameMs
        }
        return cmds to t
    }

    private fun repeat(lm: List<Pt>, n: Int) = List(n) { lm }

    @Test
    fun classifiesPoses() {
        val rec = HandGestureRecognizer()
        fun pose(s: Shape): HandPose { rec.reset(); rec.process(0, TestHands.hand(s)); return rec.pose }
        assertEquals(HandPose.PALM, pose(Shape.PALM))
        assertEquals(HandPose.FIST, pose(Shape.FIST))
        assertEquals(HandPose.POINT, pose(Shape.POINT))
        assertEquals(HandPose.PINCH, pose(Shape.PINCH))
        assertEquals(HandPose.PEACE, pose(Shape.PEACE))
    }

    @Test
    fun fistWithThumbTouchingIndexIsNotAPinch() {
        val rec = HandGestureRecognizer()
        val fist = TestHands.hand(Shape.FIST).toMutableList()
        fist[4] = Pt(fist[8].x + 0.01f, fist[8].y) // thumb tip touching the (curled) index tip
        rec.process(0, fist)
        assertEquals(HandPose.FIST, rec.pose)
    }

    @Test
    fun noCursorUnlessPointing() {
        val rec = HandGestureRecognizer()
        assertNull(rec.process(0, TestHands.hand(Shape.PALM)).cursor)
        assertNull(rec.process(33, TestHands.hand(Shape.FIST)).cursor)
        assertNotNull(rec.process(66, TestHands.hand(Shape.POINT)).cursor)
        assertNull(rec.process(99, null).cursor)
    }

    @Test
    fun cursorFollowsIndexTipAndIsClamped() {
        val rec = HandGestureRecognizer()
        val left = rec.process(0, TestHands.hand(Shape.POINT, cx = 0.2f)).cursor!!
        rec.reset()
        val right = rec.process(0, TestHands.hand(Shape.POINT, cx = 0.8f)).cursor!!
        assertTrue("moving the hand right moves the cursor right", right.x > left.x)
        rec.reset()
        val edge = rec.process(0, TestHands.hand(Shape.POINT, cx = 0.99f, cy = 0.5f)).cursor!!
        assertEquals(1f, edge.x, 0f)
    }

    private val still = TestHands.hand(Shape.POINT, cx = 0.4f)

    private fun pushFrames(from: Float, to: Float, n: Int) =
        List(n) { TestHands.pushed(still, from + (to - from) * (it + 1) / n) }

    @Test
    fun pinchNoLongerClicks() {
        val rec = HandGestureRecognizer()
        val (cmds, _) = run(rec, repeat(TestHands.hand(Shape.POINT), 5) + repeat(TestHands.hand(Shape.PINCH), 60))
        assertTrue(cmds.none { it is Command.Tap })
    }

    @Test
    fun pushingTheFingerForwardClicksWhereThePointerWas() {
        val rec = HandGestureRecognizer()
        val (_, t) = run(rec, repeat(still, 10))
        val cursorBefore = rec.process(t, still).cursor!!
        val (cmds, _) = run(rec, pushFrames(1f, 1.3f, 6), t + frameMs)
        val taps = cmds.filterIsInstance<Command.Tap>()
        assertEquals(1, taps.size)
        assertEquals(cursorBefore.x, taps[0].at!!.x, 0.03f)
        assertEquals(cursorBefore.y, taps[0].at!!.y, 0.03f)
    }

    @Test
    fun aSlowApproachDoesNotClick() {
        val rec = HandGestureRecognizer()
        val (cmds, _) = run(rec, repeat(still, 5) + pushFrames(1f, 1.3f, 60))
        assertTrue(cmds.none { it is Command.Tap })
    }

    @Test
    fun oneClickPerPush() {
        val rec = HandGestureRecognizer()
        val (cmds, _) = run(rec, repeat(still, 10) + pushFrames(1f, 1.3f, 6) + List(30) { TestHands.pushed(still, 1.3f) })
        assertEquals(1, cmds.filterIsInstance<Command.Tap>().size)
    }

    @Test
    fun thumbAndIndexSpreadingZoomsInAndClosingZoomsOut() {
        val apart = TestHands.hand(Shape.POINT)
        val touching = TestHands.hand(Shape.PINCH)
        val zoomIn = run(HandGestureRecognizer(), repeat(touching, 8) + repeat(apart, 8)).first
        assertEquals(listOf(true), zoomIn.filterIsInstance<Command.Zoom>().map { it.zoomIn })
        val zoomOut = run(HandGestureRecognizer(), repeat(apart, 8) + repeat(touching, 8)).first
        assertEquals(listOf(false), zoomOut.filterIsInstance<Command.Zoom>().map { it.zoomIn })
    }

    @Test
    fun plainPointingNeverZooms() {
        val (cmds, _) = run(HandGestureRecognizer(), repeat(TestHands.hand(Shape.POINT), 90))
        assertTrue(cmds.none { it is Command.Zoom || it is Command.Rotate })
    }

    @Test
    fun twistingThumbAndIndexRotates() {
        fun twist(from: Float, to: Float): List<Command> {
            val base = TestHands.hand(Shape.POINT)
            val hands = List(15) { TestHands.thumbAt(base, 0.2f, from + (to - from) * it / 14f) }
            return run(HandGestureRecognizer(), hands).first
        }
        val deg = (Math.PI / 180).toFloat()
        assertEquals(listOf(true), twist(-60 * deg, 60 * deg).filterIsInstance<Command.Rotate>().map { it.clockwise })
        assertEquals(listOf(false), twist(60 * deg, -60 * deg).filterIsInstance<Command.Rotate>().map { it.clockwise })
        assertTrue("small wobble", twist(0f, 10 * deg).none { it is Command.Rotate })
    }

    @Test
    fun pinchHysteresisIgnoresJitterAroundTheThreshold() {
        val rec = HandGestureRecognizer()
        val pinch = TestHands.hand(Shape.PINCH)
        val sloppy = pinch.toMutableList().also { // thumb drifts to ratio ~0.38, between enter (0.30) and exit (0.45)
            it[4] = Pt(it[8].x + 0.076f, it[8].y)
        }
        run(rec, repeat(pinch, 3))
        assertEquals(HandPose.PINCH, rec.pose)
        rec.process(200, sloppy)
        assertEquals("still pinching inside the hysteresis band", HandPose.PINCH, rec.pose)
    }

    private fun swipe(
        dx: Float, dy: Float, frames: Int = 8, shape: Shape = Shape.PALM,
        rec: HandGestureRecognizer = HandGestureRecognizer(),
    ): List<Command> {
        val start = TestHands.hand(shape, cx = 0.5f, cy = 0.5f)
        val hands = (0 until frames).map {
            val f = it / (frames - 1f)
            TestHands.moved(start, dx * f, dy * f)
        }
        return run(rec, hands).first
    }

    @Test
    fun twoFingerSwipesSwipeTheScreenInAnyDirection() {
        fun two(dx: Float, dy: Float) = swipe(dx, dy, shape = Shape.PEACE)
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.UP)), two(0f, -0.3f))
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.DOWN)), two(0f, 0.3f))
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.LEFT)), two(-0.3f, 0f))
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.RIGHT)), two(0.3f, 0f))
    }

    @Test
    fun twoFingerSwipesCanStillBeBoundToVolumeAndTracks() {
        val rec = HandGestureRecognizer()
        rec.bindings = Bindings.DEFAULT
            .with(GestureEvent.TWO_FINGER_SWIPE_UP, Command.Volume(up = true))
            .with(GestureEvent.TWO_FINGER_SWIPE_RIGHT, Command.Media(MediaKind.NEXT))
        assertEquals(listOf<Command>(Command.Volume(up = true)), swipe(0f, -0.3f, shape = Shape.PEACE, rec = rec))
        val rec2 = HandGestureRecognizer().also { it.bindings = rec.bindings }
        assertEquals(listOf<Command>(Command.Media(MediaKind.NEXT)), swipe(0.3f, 0f, shape = Shape.PEACE, rec = rec2))
    }

    @Test
    fun remappedAndUnboundGestures() {
        val rec = HandGestureRecognizer()
        rec.bindings = Bindings.DEFAULT
            .with(GestureEvent.PALM_SWIPE_UP, Command.Media(MediaKind.PLAY_PAUSE))
            .with(GestureEvent.PALM_SWIPE_DOWN, null)
        assertEquals(listOf<Command>(Command.Media(MediaKind.PLAY_PAUSE)), swipe(0f, -0.3f, rec = rec))
        assertTrue(swipe(0f, 0.3f, rec = HandGestureRecognizer().also { it.bindings = rec.bindings }).isEmpty())
    }

    private fun swipeFrames(shape: Shape, n: Int = 9, dy: Float = -0.3f) = (0 until n).map {
        TestHands.moved(TestHands.hand(shape), 0f, dy * it / (n - 1f))
    }

    @Test
    fun aSwipeSurvivesOnePoseFlickerFrame() {
        val frames = swipeFrames(Shape.PALM).toMutableList()
        frames[4] = TestHands.moved(TestHands.hand(Shape.FIST), 0f, -0.15f) // detector briefly sees a fist
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.UP)), run(HandGestureRecognizer(), frames).first)
    }

    @Test
    fun aSwipeSurvivesOneLostFrame() {
        val frames = swipeFrames(Shape.PALM).toMutableList<List<Pt>?>()
        frames[4] = null
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.UP)), run(HandGestureRecognizer(), frames).first)
    }

    @Test
    fun aLongDropoutStillCancelsTheSwipe() {
        val frames = swipeFrames(Shape.PALM, n = 5, dy = -0.1f) + List(10) { null } + swipeFrames(Shape.PALM, n = 5, dy = -0.1f)
        assertTrue(run(HandGestureRecognizer(), frames).first.none { it is Command.Swipe })
    }

    @Test
    fun twoFingersFlickeringToPalmStillCountAsTwoFingers() {
        val frames = swipeFrames(Shape.PEACE).toMutableList()
        frames[3] = TestHands.moved(TestHands.hand(Shape.PALM), 0f, -0.11f)
        frames[6] = TestHands.moved(TestHands.hand(Shape.PALM), 0f, -0.2f)
        val rec = HandGestureRecognizer()
        rec.bindings = Bindings.DEFAULT
            .with(GestureEvent.TWO_FINGER_SWIPE_UP, Command.Volume(up = true))
            .with(GestureEvent.PALM_SWIPE_UP, Command.Swipe(SwipeDir.DOWN))
        assertEquals(listOf<Command>(Command.Volume(up = true)), run(rec, frames).first)
    }

    @Test
    fun swipeDirections() {
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.UP)), swipe(0f, -0.3f))
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.DOWN)), swipe(0f, 0.3f))
        assertEquals(listOf<Command>(Command.Swipe(SwipeDir.LEFT)), swipe(-0.3f, 0f))
        assertEquals(listOf<Command>(Command.Global(GlobalKind.BACK)), swipe(0.3f, 0f))
    }

    @Test
    fun slowDriftAndDiagonalsAreIgnored() {
        assertTrue("slow drift", swipe(0f, -0.3f, frames = 80).none { it is Command.Swipe })
        assertTrue("diagonal", swipe(0.25f, -0.25f).isEmpty())
        assertTrue("too short", swipe(0f, -0.1f).isEmpty())
    }

    @Test
    fun swipeNeedsAnOpenPalm() {
        val rec = HandGestureRecognizer()
        val start = TestHands.hand(Shape.FIST)
        val hands = (0 until 8).map { TestHands.moved(start, 0f, -0.3f * it / 7f) }
        assertTrue(run(rec, hands).first.none { it is Command.Swipe })
    }

    @Test
    fun swipeCooldownPreventsDoubleFire() {
        val rec = HandGestureRecognizer()
        val start = TestHands.hand(Shape.PALM)
        // two quick strokes back to back: the second falls inside the cooldown
        val stroke = (0 until 8).map { TestHands.moved(start, 0f, -0.3f * it / 7f) }
        val (cmds, _) = run(rec, stroke + stroke)
        assertEquals(1, cmds.filterIsInstance<Command.Swipe>().size)
    }

    @Test
    fun stillPalmHeldTriggersHomeOnce() {
        val rec = HandGestureRecognizer()
        val palm = TestHands.hand(Shape.PALM)
        val (early, _) = run(rec, repeat(palm, 30)) // ~1 s: not yet
        assertTrue(early.none { it is Command.Global })
        rec.reset()
        val (cmds, _) = run(rec, repeat(palm, 120)) // ~4 s
        assertEquals(listOf<Command>(Command.Global(GlobalKind.HOME)), cmds)
    }

    @Test
    fun fistHeldTriggersRecents() {
        val rec = HandGestureRecognizer()
        val (cmds, _) = run(rec, repeat(TestHands.hand(Shape.FIST), 60))
        assertEquals(listOf<Command>(Command.Global(GlobalKind.RECENTS)), cmds)
    }

    @Test
    fun losingTheHandResetsHolds() {
        val rec = HandGestureRecognizer()
        val fist = TestHands.hand(Shape.FIST)
        val hands = repeat(fist, 25) + listOf(null) + repeat(fist, 25) // 0.8 s + gap + 0.8 s
        assertTrue(run(rec, hands).first.isEmpty())
    }
}
