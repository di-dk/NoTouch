package dev.notouch.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.notouch.app.gesture.ActionCatalog
import dev.notouch.app.gesture.Bindings
import dev.notouch.app.gesture.GestureEvent

/** Lets the user choose which action each gesture triggers. Changes apply immediately. */
class BindingsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Set when editing one app's profile; null edits the global table. */
    private val pkg: String? by lazy { intent.getStringExtra(EXTRA_PACKAGE) }

    private fun current(): Bindings = Settings.bindingsFor(this, pkg)

    private fun save(b: Bindings) = pkg?.let { Settings.setProfile(this, it, b) } ?: Settings.setBindings(this, b)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = pkg?.let { p -> "Gestures for " + runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)) }.getOrDefault(p) }
            ?: "Gesture actions"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        fun heading(s: String) = col.addView(TextView(this).apply {
            text = s
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(4))
        })
        fun row(event: GestureEvent, label: String) {
            col.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, dp(8), 0, 0) })
            col.addView(Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@BindingsActivity, android.R.layout.simple_spinner_dropdown_item,
                    ActionCatalog.actions.map { it.first },
                )
                setSelection(ActionCatalog.indexOf(current().commandFor(event)), false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val cmd = ActionCatalog.actions[pos].second
                        val now = current()
                        if (now.commandFor(event) != cmd) save(now.with(event, cmd))
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }

        col.addView(TextView(this).apply {
            text = if (pkg == null) "Choose what each gesture does. Changes apply immediately."
            else "These actions apply only while this app is in front. Changing any gesture creates a profile for the app."
            textSize = 13f
        })
        heading("Hand gestures")
        ActionCatalog.handGestures.forEach { (e, l) -> row(e, l) }
        heading("Face gestures")
        ActionCatalog.faceGestures.forEach { (e, l) -> row(e, l) }
        col.addView(Button(this).apply {
            text = if (pkg == null) "Reset all to defaults" else "Use the global gesture actions for this app"
            setOnClickListener {
                if (pkg == null) Settings.setBindings(this@BindingsActivity, Bindings.DEFAULT)
                else Settings.setProfile(this@BindingsActivity, pkg!!, null)
                recreate()
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
