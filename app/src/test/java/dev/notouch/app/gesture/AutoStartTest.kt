package dev.notouch.app.gesture

import dev.notouch.app.service.AutoStart
import dev.notouch.app.service.AutoStartAction
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoStartTest {
    private val apps = setOf("video.app", "music.app")
    private fun d(fg: String, running: Boolean, byAuto: Boolean) = AutoStart.decide(fg, "dev.notouch.app", apps, running, byAuto)

    @Test fun startsWhenAChosenAppOpens() = assertEquals(AutoStartAction.START, d("video.app", false, false))
    @Test fun doesNothingIfAlreadyRunning() = assertEquals(AutoStartAction.NONE, d("video.app", true, true))
    @Test fun stopsOnlyWhatAutoStartStarted() {
        assertEquals(AutoStartAction.STOP, d("mail.app", true, true))
        assertEquals(AutoStartAction.NONE, d("mail.app", true, false))
    }
    @Test fun switchingBetweenChosenAppsKeepsRunning() = assertEquals(AutoStartAction.NONE, d("music.app", true, true))
    @Test fun ownScreenNeverStartsOrStops() {
        assertEquals(AutoStartAction.NONE, d("dev.notouch.app", true, true))
        assertEquals(AutoStartAction.NONE, d("dev.notouch.app", false, false))
    }
}
