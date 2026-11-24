package dev.notouch.app.gesture

/**
 * Which [Command] each [GestureEvent] triggers. An event missing from [map] does nothing.
 * The defaults reproduce the original fixed behaviour, plus volume/media on the two-finger swipes.
 */
class Bindings(private val map: Map<GestureEvent, Command>) {

    /** [at] is where a tap should land; null lets the engine use the current cursor. */
    fun resolve(event: GestureEvent, at: Pt? = null): Command? {
        return when (val c = map[event] ?: return null) {
            is Command.Tap -> Command.Tap(at)
            is Command.Zoom -> Command.Zoom(c.zoomIn, at)
            is Command.Rotate -> Command.Rotate(c.clockwise, at)
            else -> c
        }
    }

    fun with(event: GestureEvent, command: Command?): Bindings =
        Bindings(if (command == null) map - event else map + (event to command))

    fun commandFor(event: GestureEvent): Command? = map[event]

    /** `EVENT=command` for every event (`none` when unbound), for SharedPreferences. */
    fun serialize(): String = GestureEvent.values().joinToString(";") { e -> "${e.name}=${map[e]?.let(::encode) ?: "none"}" }

    companion object {
        val DEFAULT = Bindings(
            mapOf(
                GestureEvent.DWELL_CLICK to Command.Tap(null),
                GestureEvent.ZOOM_IN to Command.Zoom(zoomIn = true, at = null),
                GestureEvent.ZOOM_OUT to Command.Zoom(zoomIn = false, at = null),
                GestureEvent.ROTATE_CW to Command.Rotate(clockwise = true, at = null),
                GestureEvent.ROTATE_CCW to Command.Rotate(clockwise = false, at = null),
                GestureEvent.PALM_SWIPE_UP to Command.Swipe(SwipeDir.UP),
                GestureEvent.PALM_SWIPE_DOWN to Command.Swipe(SwipeDir.DOWN),
                GestureEvent.PALM_SWIPE_LEFT to Command.Swipe(SwipeDir.LEFT),
                GestureEvent.PALM_SWIPE_RIGHT to Command.Global(GlobalKind.BACK),
                GestureEvent.PALM_HOLD to Command.Global(GlobalKind.HOME),
                GestureEvent.FIST_HOLD to Command.Global(GlobalKind.RECENTS),
                GestureEvent.TWO_FINGER_SWIPE_UP to Command.Swipe(SwipeDir.UP),
                GestureEvent.TWO_FINGER_SWIPE_DOWN to Command.Swipe(SwipeDir.DOWN),
                GestureEvent.TWO_FINGER_SWIPE_LEFT to Command.Swipe(SwipeDir.LEFT),
                GestureEvent.TWO_FINGER_SWIPE_RIGHT to Command.Swipe(SwipeDir.RIGHT),
                GestureEvent.TWO_FINGER_SWIPE_UP_LEFT to Command.Swipe(SwipeDir.UP_LEFT),
                GestureEvent.TWO_FINGER_SWIPE_UP_RIGHT to Command.Swipe(SwipeDir.UP_RIGHT),
                GestureEvent.TWO_FINGER_SWIPE_DOWN_LEFT to Command.Swipe(SwipeDir.DOWN_LEFT),
                GestureEvent.TWO_FINGER_SWIPE_DOWN_RIGHT to Command.Swipe(SwipeDir.DOWN_RIGHT),
                GestureEvent.MOUTH_OPEN to Command.Tap(null),
                GestureEvent.BROWS_RAISED to Command.Global(GlobalKind.BACK),
                GestureEvent.SMILE_HOLD to Command.Swipe(SwipeDir.UP),
                GestureEvent.PUCKER_HOLD to Command.Swipe(SwipeDir.DOWN),
                GestureEvent.BLINK_HOLD to Command.Global(GlobalKind.HOME),
            ),
        )

        /**
         * Inverse of [serialize], applied on top of [DEFAULT] so gestures added in later versions get
         * their default. `none` unbinds an event; unknown or malformed entries are skipped.
         */
        fun parse(text: String): Bindings {
            val map = HashMap(DEFAULT.map)
            for (part in text.split(';')) {
                val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                val event = runCatching { GestureEvent.valueOf(name) }.getOrNull() ?: continue
                if (value == "none") map.remove(event) else decode(value)?.let { map[event] = it }
            }
            return Bindings(map)
        }

        private fun encode(c: Command): String = when (c) {
            is Command.Tap -> "tap"
            is Command.Global -> "global:${c.kind.name}"
            is Command.Swipe -> "swipe:${c.dir.name}"
            is Command.Media -> "media:${c.kind.name}"
            is Command.Volume -> if (c.up) "volume:up" else "volume:down"
            is Command.Zoom -> if (c.zoomIn) "zoom:in" else "zoom:out"
            is Command.Rotate -> if (c.clockwise) "rotate:cw" else "rotate:ccw"
        }

        private fun decode(s: String): Command? {
            val (kind, arg) = s.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
            return runCatching {
                when (kind) {
                    "tap" -> Command.Tap(null)
                    "global" -> Command.Global(GlobalKind.valueOf(arg!!))
                    "swipe" -> Command.Swipe(SwipeDir.valueOf(arg!!))
                    "media" -> Command.Media(MediaKind.valueOf(arg!!))
                    "volume" -> Command.Volume(arg == "up")
                    "zoom" -> Command.Zoom(arg == "in", null)
                    "rotate" -> Command.Rotate(arg == "cw", null)
                    else -> null
                }
            }.getOrNull()
        }
    }
}
