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

    @Test
    fun pinchTapsAtTheCursorFromBeforeThePinch() {
        val rec = HandGestureRecognizer()
        val pointAt = TestHands.hand(Shape.POINT, cx = 0.4f)
        val (_, t) = run(rec, repeat(pointAt, 15))
        val cursorBefore = rec.process(t, pointAt).cursor!!
        // pinching drags the fingertip; the tap must still land where the cursor was
        val pinch = TestHands.moved(TestHands.hand(Shape.PINCH, cx = 0.4f), 0.05f, 0.05f)
        val (cmds, _) = run(rec, repeat(pinch, 10), t + frameMs)
        val taps = cmds.filterIsInstance<Command.Tap>()
        assertEquals(1, taps.size)
        val at = taps[0].at!!
        assertEquals(cursorBefore.x, at.x, 0.02f)
        assertEquals(cursorBefore.y, at.y, 0.02f)
    }

    @Test
    fun holdingAPinchTapsOnlyOnce() {
        val rec = HandGestureRecognizer()
        val (cmds, _) = run(rec, repeat(TestHands.hand(Shape.POINT), 5) + repeat(TestHands.hand(Shape.PINCH), 90))
        assertEquals(1, cmds.filterIsInstance<Command.Tap>().size)
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
    fun twoFingerSwipesAdjustVolumeAndSkipTracks() {
        fun two(dx: Float, dy: Float) = swipe(dx, dy, shape = Shape.PEACE)
        assertEquals(listOf<Command>(Command.Volume(up = true)), two(0f, -0.3f))
        assertEquals(listOf<Command>(Command.Volume(up = false)), two(0f, 0.3f))
        assertEquals(listOf<Command>(Command.Media(MediaKind.PREVIOUS)), two(-0.3f, 0f))
        assertEquals(listOf<Command>(Command.Media(MediaKind.NEXT)), two(0.3f, 0f))
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
