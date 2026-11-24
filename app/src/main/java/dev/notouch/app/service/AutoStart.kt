package dev.notouch.app.service

enum class AutoStartAction { START, STOP, NONE }

/** Decides whether the camera service should start or stop when the foreground app changes. */
object AutoStart {
    /**
     * [startedByAuto] is true only when this logic (not the user) started the running service, so a
     * service the user started by hand is never stopped when they switch apps.
     */
    fun decide(
        foreground: String,
        ownPackage: String,
        apps: Set<String>,
        running: Boolean,
        startedByAuto: Boolean,
    ): AutoStartAction = when {
        foreground == ownPackage -> AutoStartAction.NONE
        foreground in apps && !running -> AutoStartAction.START
        foreground !in apps && running && startedByAuto -> AutoStartAction.STOP
        else -> AutoStartAction.NONE
    }
}
