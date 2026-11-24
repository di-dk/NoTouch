package dev.notouch.app

enum class A11yState { OFF, STALLED, ON }

enum class RunState { BLOCKED, READY, RUNNING }

object SetupState {
    /** STALLED: switched on in Settings but Android is not running it (killed or crashed); toggling it fixes that. */
    fun a11y(enabledInSettings: Boolean, connected: Boolean) = when {
        !enabledInSettings -> A11yState.OFF
        !connected -> A11yState.STALLED
        else -> A11yState.ON
    }

    fun run(cameraGranted: Boolean, a11y: A11yState, running: Boolean) = when {
        running -> RunState.RUNNING
        cameraGranted && a11y == A11yState.ON -> RunState.READY
        else -> RunState.BLOCKED
    }
}
