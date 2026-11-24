package dev.notouch.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStateTest {
    @Test fun a11yOffStalledOrOn() {
        assertEquals(A11yState.OFF, SetupState.a11y(enabledInSettings = false, connected = false))
        assertEquals(A11yState.OFF, SetupState.a11y(enabledInSettings = false, connected = true))
        assertEquals(A11yState.STALLED, SetupState.a11y(enabledInSettings = true, connected = false))
        assertEquals(A11yState.ON, SetupState.a11y(enabledInSettings = true, connected = true))
    }

    @Test fun runButtonState() {
        assertEquals(RunState.RUNNING, SetupState.run(true, A11yState.ON, running = true))
        assertEquals(RunState.READY, SetupState.run(true, A11yState.ON, running = false))
        assertEquals(RunState.BLOCKED, SetupState.run(false, A11yState.ON, running = false))
        assertEquals(RunState.BLOCKED, SetupState.run(true, A11yState.STALLED, running = false))
        assertEquals(RunState.BLOCKED, SetupState.run(true, A11yState.OFF, running = false))
    }
}
