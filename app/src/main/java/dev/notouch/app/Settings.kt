package dev.notouch.app

import android.content.Context
import dev.notouch.app.gesture.Bindings
import dev.notouch.app.gesture.Mode

/** Tiny SharedPreferences wrapper for the two user settings. */
object Settings {
    private const val FILE = "notouch"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(c: Context): Mode =
        runCatching { Mode.valueOf(prefs(c).getString("mode", Mode.BOTH.name)!!) }.getOrDefault(Mode.BOTH)

    fun setMode(c: Context, m: Mode) = prefs(c).edit().putString("mode", m.name).apply()

    /** Multiplies expression scores and head-to-cursor gain. Higher = easier to trigger. */
    fun sensitivity(c: Context): Float = prefs(c).getFloat("sensitivity", 1.3f)

    fun setSensitivity(c: Context, v: Float) = prefs(c).edit().putFloat("sensitivity", v).apply()

    fun useGpu(c: Context): Boolean = prefs(c).getBoolean("useGpu", false)

    fun setUseGpu(c: Context, v: Boolean) = prefs(c).edit().putBoolean("useGpu", v).apply()

    fun showCursor(c: Context): Boolean = prefs(c).getBoolean("showCursor", true)

    fun setShowCursor(c: Context, v: Boolean) = prefs(c).edit().putBoolean("showCursor", v).apply()

    private var cachedText: String? = null
    private var cachedBindings = Bindings.DEFAULT

    /** Gesture-to-action table; falls back to the defaults until the user changes something. Cached per stored string. */
    @Synchronized
    fun bindings(c: Context): Bindings {
        val text = prefs(c).getString("bindings", null) ?: return Bindings.DEFAULT
        if (text != cachedText) {
            cachedText = text
            cachedBindings = Bindings.parse(text)
        }
        return cachedBindings
    }

    fun setBindings(c: Context, b: Bindings) = prefs(c).edit().putString("bindings", b.serialize()).apply()
}
