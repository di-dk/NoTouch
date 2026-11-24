package dev.notouch.app.gesture

/**
 * Which [Command] each [GestureEvent] triggers. An event missing from [map] does nothing.
 * The defaults reproduce the original fixed behaviour, plus volume/media on the two-finger swipes.
 */
class Bindings(private val map: Map<GestureEvent, Command>) {

    /** [at] is where a tap should land; null lets the engine use the current cursor. */
    fun resolve(event: GestureEvent, at: Pt? = null): Command? {
        val c = map[event] ?: return null
        return if (c is Command.Tap) Command.Tap(at) else c
    }

    fun with(event: GestureEvent, command: Command?): Bindings =
        Bindings(if (command == null) map - event else map + (event to command))

    fun commandFor(event: GestureEvent): Command? = map[event]

    /** One line per binding, `EVENT=command`, for SharedPreferences. */
    fun serialize(): String = map.entries.joinToString(";") { (e, c) -> "${e.name}=${encode(c)}" }

    companion object {
        val DEFAULT = Bindings(
            mapOf(
                GestureEvent.PINCH to Command.Tap(null),
                GestureEvent.PALM_SWIPE_UP to Command.Swipe(SwipeDir.UP),
                GestureEvent.PALM_SWIPE_DOWN to Command.Swipe(SwipeDir.DOWN),
                GestureEvent.PALM_SWIPE_LEFT to Command.Swipe(SwipeDir.LEFT),
                GestureEvent.PALM_SWIPE_RIGHT to Command.Global(GlobalKind.BACK),
                GestureEvent.PALM_HOLD to Command.Global(GlobalKind.HOME),
                GestureEvent.FIST_HOLD to Command.Global(GlobalKind.RECENTS),
                GestureEvent.TWO_FINGER_SWIPE_UP to Command.Volume(up = true),
                GestureEvent.TWO_FINGER_SWIPE_DOWN to Command.Volume(up = false),
                GestureEvent.TWO_FINGER_SWIPE_LEFT to Command.Media(MediaKind.PREVIOUS),
                GestureEvent.TWO_FINGER_SWIPE_RIGHT to Command.Media(MediaKind.NEXT),
                GestureEvent.MOUTH_OPEN to Command.Tap(null),
                GestureEvent.BROWS_RAISED to Command.Global(GlobalKind.BACK),
                GestureEvent.SMILE_HOLD to Command.Swipe(SwipeDir.UP),
                GestureEvent.PUCKER_HOLD to Command.Swipe(SwipeDir.DOWN),
                GestureEvent.BLINK_HOLD to Command.Global(GlobalKind.HOME),
            ),
        )

        /** Inverse of [serialize]. Unknown or malformed entries are skipped; missing events stay unbound. */
        fun parse(text: String): Bindings {
            val map = HashMap<GestureEvent, Command>()
            for (part in text.split(';')) {
                val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                val event = runCatching { GestureEvent.valueOf(name) }.getOrNull() ?: continue
                decode(value)?.let { map[event] = it }
            }
            return Bindings(map)
        }

        private fun encode(c: Command): String = when (c) {
            is Command.Tap -> "tap"
            is Command.Global -> "global:${c.kind.name}"
            is Command.Swipe -> "swipe:${c.dir.name}"
            is Command.Media -> "media:${c.kind.name}"
            is Command.Volume -> if (c.up) "volume:up" else "volume:down"
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
                    else -> null
                }
            }.getOrNull()
        }
    }
}
