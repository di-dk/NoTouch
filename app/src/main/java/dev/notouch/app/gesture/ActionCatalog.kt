package dev.notouch.app.gesture

/** The user-facing names for gestures and the actions they can be bound to. */
object ActionCatalog {

    val actions: List<Pair<String, Command?>> = listOf(
        "Do nothing" to null,
        "Tap at pointer" to Command.Tap(null),
        "Back" to Command.Global(GlobalKind.BACK),
        "Home" to Command.Global(GlobalKind.HOME),
        "Recents" to Command.Global(GlobalKind.RECENTS),
        "Open notifications" to Command.Global(GlobalKind.NOTIFICATIONS),
        "Scroll down (swipe up)" to Command.Swipe(SwipeDir.UP),
        "Scroll up (swipe down)" to Command.Swipe(SwipeDir.DOWN),
        "Swipe left" to Command.Swipe(SwipeDir.LEFT),
        "Swipe right" to Command.Swipe(SwipeDir.RIGHT),
        "Play / pause" to Command.Media(MediaKind.PLAY_PAUSE),
        "Next track" to Command.Media(MediaKind.NEXT),
        "Previous track" to Command.Media(MediaKind.PREVIOUS),
        "Volume up" to Command.Volume(up = true),
        "Volume down" to Command.Volume(up = false),
        "Zoom in" to Command.Zoom(zoomIn = true, at = null),
        "Zoom out" to Command.Zoom(zoomIn = false, at = null),
        "Rotate clockwise" to Command.Rotate(clockwise = true, at = null),
        "Rotate counterclockwise" to Command.Rotate(clockwise = false, at = null),
    )

    fun indexOf(command: Command?): Int = actions.indexOfFirst { it.second == command }.coerceAtLeast(0)

    val handGestures: List<Pair<GestureEvent, String>> = listOf(
        GestureEvent.PUSH_CLICK to "Push pointing finger toward the camera (click)",
        GestureEvent.ZOOM_IN to "Thumb and index touching, then spread apart",
        GestureEvent.ZOOM_OUT to "Thumb and index apart, then touch together",
        GestureEvent.ROTATE_CW to "Thumb and index apart, twist clockwise",
        GestureEvent.ROTATE_CCW to "Thumb and index apart, twist counterclockwise",
        GestureEvent.PALM_SWIPE_UP to "Open palm, swipe up",
        GestureEvent.PALM_SWIPE_DOWN to "Open palm, swipe down",
        GestureEvent.PALM_SWIPE_LEFT to "Open palm, swipe left",
        GestureEvent.PALM_SWIPE_RIGHT to "Open palm, swipe right",
        GestureEvent.PALM_HOLD to "Open palm held still",
        GestureEvent.FIST_HOLD to "Fist held",
        GestureEvent.TWO_FINGER_SWIPE_UP to "Two fingers, swipe up",
        GestureEvent.TWO_FINGER_SWIPE_DOWN to "Two fingers, swipe down",
        GestureEvent.TWO_FINGER_SWIPE_LEFT to "Two fingers, swipe left",
        GestureEvent.TWO_FINGER_SWIPE_RIGHT to "Two fingers, swipe right",
    )

    val faceGestures: List<Pair<GestureEvent, String>> = listOf(
        GestureEvent.MOUTH_OPEN to "Open mouth",
        GestureEvent.BROWS_RAISED to "Raise eyebrows",
        GestureEvent.SMILE_HOLD to "Hold a smile",
        GestureEvent.PUCKER_HOLD to "Hold pursed lips",
        GestureEvent.BLINK_HOLD to "Close both eyes for 1 s",
    )
}
