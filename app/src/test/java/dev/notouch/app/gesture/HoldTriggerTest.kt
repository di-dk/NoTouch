package dev.notouch.app.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldTriggerTest {
    @Test
    fun firesOnceAfterHold() {
        val t = HoldTrigger(enter = 0.5f, exit = 0.3f, holdMs = 500)
        var fires = 0
        for (ms in 0..2000 step 50) if (t.update(1f, ms.toLong())) fires++
        assertEquals(1, fires)
    }

    @Test
    fun doesNotFireIfReleasedEarly() {
        val t = HoldTrigger(0.5f, 0.3f, holdMs = 500)
        assertFalse(t.update(1f, 0))
        assertFalse(t.update(1f, 300))
        assertFalse(t.update(0f, 400))
        assertFalse(t.update(1f, 450))
        assertFalse(t.update(1f, 800)) // only 350 ms into the new hold
    }

    @Test
    fun hysteresisKeepsItActiveBetweenThresholds() {
        val t = HoldTrigger(0.5f, 0.3f, holdMs = 0)
        assertTrue(t.update(0.6f, 0))
        assertFalse(t.update(0.4f, 100)) // still active, already fired
        assertFalse(t.update(0.6f, 200)) // never dropped below exit -> no re-fire
        assertFalse(t.update(0.2f, 300)) // released
        assertTrue(t.update(0.6f, 400)) // new activation fires again
    }

    @Test
    fun repeatsWhileHeld() {
        val t = HoldTrigger(0.5f, 0.3f, holdMs = 100, repeatMs = 500)
        val times = (0..2000 step 50).filter { t.update(1f, it.toLong()) }
        assertEquals(listOf(100, 600, 1100, 1600), times)
    }

    @Test
    fun cooldownBlocksQuickRefire() {
        val t = HoldTrigger(0.5f, 0.3f, cooldownMs = 1000)
        assertTrue(t.update(1f, 0))
        t.update(0f, 100)
        assertFalse(t.update(1f, 200)) // re-activated inside the cooldown, fires once it ends
        assertTrue(t.update(1f, 1000))
    }
}
