package dev.notouch.app.gesture

import dev.notouch.app.gesture.TestHands.Shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAndEngineTest {
    private val frameMs = 33L

    private fun face(nose: Pt = Pt(0.5f, 0.5f), vararg blend: Pair<String, Float>) =
        FaceFrame(nose, mapOf(*blend))

    private fun run(rec: FaceGestureRecognizer, frame: FaceFrame?, n: Int, t0: Long = 0): Pair<List<Command>, Long> {
        val out = ArrayList<Command>()
        var t = t0
        repeat(n) {
            out += rec.process(t, frame).commands
            t += frameMs
        }
        return out to t
    }

    @Test
    fun firstFrameIsTheNeutralCentre() {
        val rec = FaceGestureRecognizer()
        val c = rec.process(0, face(Pt(0.3f, 0.7f))).cursor!!
        assertEquals(0.5f, c.x, 1e-4f)
        assertEquals(0.5f, c.y, 1e-4f)
    }

    @Test
    fun turningTheHeadRightMovesTheCursorRight() {
        val rec = FaceGestureRecognizer()
        rec.process(0, face(Pt(0.5f, 0.5f)))
        var c = Pt(0.5f, 0.5f)
        var t = 33L
        repeat(30) { c = rec.process(t, face(Pt(0.58f, 0.46f))).cursor!!; t += frameMs }
        assertTrue(c.x > 0.8f)
        assertTrue("moving the nose up moves the cursor up", c.y < 0.4f)
    }

    @Test
    fun recenterMakesTheCurrentPoseNeutral() {
        val rec = FaceGestureRecognizer()
        rec.process(0, face(Pt(0.5f, 0.5f)))
        rec.recenter()
        val c = rec.process(33, face(Pt(0.7f, 0.5f))).cursor!!
        assertEquals(0.5f, c.x, 1e-4f)
    }

    @Test
    fun openMouthTapsOnceAndRearmsAfterClosing() {
        val rec = FaceGestureRecognizer()
        val open = face(blend = arrayOf("jawOpen" to 0.8f))
        val closed = face(blend = arrayOf("jawOpen" to 0.0f))
        val (a, t1) = run(rec, open, 20)
        assertEquals(1, a.filterIsInstance<Command.Tap>().size)
        val (_, t2) = run(rec, closed, 30, t1)
        val (b, _) = run(rec, open, 5, t2)
        assertEquals(1, b.filterIsInstance<Command.Tap>().size)
    }

    @Test
    fun browsRaisedGoesBack() {
        val rec = FaceGestureRecognizer()
        val brows = face(blend = arrayOf("browInnerUp" to 0.9f, "browOuterUpLeft" to 0.8f, "browOuterUpRight" to 0.8f))
        val (cmds, _) = run(rec, brows, 20)
        assertEquals(listOf<Command>(Command.Global(GlobalKind.BACK)), cmds)
    }

    @Test
    fun quickSmileDoesNotScrollButHeldSmileDoes() {
        val smile = face(blend = arrayOf("mouthSmileLeft" to 0.9f, "mouthSmileRight" to 0.9f))
        val rec = FaceGestureRecognizer()
        assertTrue(run(rec, smile, 8).first.isEmpty()) // ~260 ms
        val (cmds, _) = run(FaceGestureRecognizer(), smile, 60) // ~2 s
        assertTrue(cmds.size >= 2 && cmds.all { it == Command.Swipe(SwipeDir.UP) })
    }

    @Test
    fun smileWithOpenMouthIsIgnored() {
        val f = face(blend = arrayOf("mouthSmileLeft" to 0.9f, "mouthSmileRight" to 0.9f, "jawOpen" to 0.6f))
        val cmds = run(FaceGestureRecognizer(), f, 60).first
        assertTrue(cmds.none { it is Command.Swipe })
    }

    @Test
    fun normalBlinkIgnoredLongCloseGoesHome() {
        val closed = face(blend = arrayOf("eyeBlinkLeft" to 0.95f, "eyeBlinkRight" to 0.95f))
        assertTrue(run(FaceGestureRecognizer(), closed, 9).first.isEmpty()) // 300 ms blink
        val cmds = run(FaceGestureRecognizer(), closed, 50).first // ~1.6 s
        assertEquals(listOf<Command>(Command.Global(GlobalKind.HOME)), cmds)
    }

    @Test
    fun losingTheFaceHidesCursorAndRecalibrates() {
        val rec = FaceGestureRecognizer()
        rec.process(0, face(Pt(0.5f, 0.5f)))
        assertNull(rec.process(33, null).cursor)
        val c = rec.process(66, face(Pt(0.2f, 0.2f))).cursor!!
        assertEquals(0.5f, c.x, 1e-4f)
    }

    // ---- engine -----------------------------------------------------------------------

    @Test
    fun handOwnsTheCursorWhileVisible() {
        val e = GestureEngine(Mode.BOTH)
        val pointing = TestHands.hand(Shape.POINT, cx = 0.8f)
        val f = face(Pt(0.5f, 0.5f))
        e.process(0, null, f) // calibrate the face
        val faceOnly = e.process(33, null, face(Pt(0.52f, 0.5f))).cursor!!
        val withHand = e.process(66, pointing, face(Pt(0.52f, 0.5f))).cursor!!
        assertTrue("hand cursor (right side) differs from face cursor", withHand.x > faceOnly.x + 0.2f)
        // hand visible but not pointing: cursor hidden, the face does not take over
        assertNull(e.process(99, TestHands.hand(Shape.PALM), face(Pt(0.52f, 0.5f))).cursor)
        // hand gone: face cursor is back
        assertNotNull(e.process(132, null, face(Pt(0.52f, 0.5f))).cursor)
    }

    @Test
    fun faceTapLandsOnTheShownCursor() {
        val e = GestureEngine(Mode.FACE)
        val open = face(Pt(0.5f, 0.5f), "jawOpen" to 0.9f)
        val out = e.process(0, null, open)
        val tap = out.commands.single() as Command.Tap
        assertEquals(out.cursor, tap.at)
    }

    @Test
    fun faceTapWithNoCursorIsDropped() {
        val e = GestureEngine(Mode.BOTH)
        // hand visible (palm -> no cursor) while the mouth opens: nothing to tap at
        val out = e.process(0, TestHands.hand(Shape.PALM), face(blend = arrayOf("jawOpen" to 0.9f)))
        assertTrue(out.commands.none { it is Command.Tap })
    }

    @Test
    fun modesDisableTheOtherSource() {
        val handOnly = GestureEngine(Mode.HAND)
        val out = handOnly.process(0, null, face(blend = arrayOf("jawOpen" to 0.9f)))
        assertNull(out.cursor)
        assertTrue(out.commands.isEmpty())

        val faceOnly = GestureEngine(Mode.FACE)
        assertNull(faceOnly.process(0, TestHands.hand(Shape.POINT), null).cursor)
    }
}
