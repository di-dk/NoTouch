package dev.notouch.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BindingsTest {

    @Test
    fun everyEventHasADefault() {
        GestureEvent.values().forEach { assertEquals("$it", true, Bindings.DEFAULT.commandFor(it) != null) }
    }

    @Test
    fun serializeRoundTrips() {
        val b = Bindings.DEFAULT
            .with(GestureEvent.FIST_HOLD, Command.Media(MediaKind.PLAY_PAUSE))
            .with(GestureEvent.BLINK_HOLD, Command.Volume(up = false))
            .with(GestureEvent.PINCH, null)
        val back = Bindings.parse(b.serialize())
        GestureEvent.values().forEach { assertEquals("$it", b.commandFor(it), back.commandFor(it)) }
        assertNull(back.commandFor(GestureEvent.PINCH))
    }

    @Test
    fun parseSkipsGarbage() {
        val b = Bindings.parse("NOPE=tap;PINCH=wat;MOUTH_OPEN=global:HOME;broken;PALM_HOLD=swipe:SIDEWAYS")
        assertEquals(Command.Global(GlobalKind.HOME), b.commandFor(GestureEvent.MOUTH_OPEN))
        assertNull(b.commandFor(GestureEvent.PINCH))
        assertNull(b.commandFor(GestureEvent.PALM_HOLD))
    }

    @Test
    fun tapTakesTheSuppliedPosition() {
        assertEquals(Command.Tap(Pt(0.2f, 0.3f)), Bindings.DEFAULT.resolve(GestureEvent.PINCH, Pt(0.2f, 0.3f)))
    }
}

class ActionCatalogTest {
    @Test
    fun everyEventIsEditableAndEveryDefaultIsOffered() {
        val listed = (ActionCatalog.handGestures + ActionCatalog.faceGestures).map { it.first }.toSet()
        assertEquals(GestureEvent.values().toSet(), listed)
        GestureEvent.values().forEach {
            val cmd = Bindings.DEFAULT.commandFor(it)
            assertEquals("$it", cmd, ActionCatalog.actions[ActionCatalog.indexOf(cmd)].second)
        }
    }
}
