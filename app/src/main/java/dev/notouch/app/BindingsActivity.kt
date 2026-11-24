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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Gesture actions"
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
                setSelection(ActionCatalog.indexOf(Settings.bindings(this@BindingsActivity).commandFor(event)), false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val cmd = ActionCatalog.actions[pos].second
                        val current = Settings.bindings(this@BindingsActivity)
                        if (current.commandFor(event) != cmd) {
                            Settings.setBindings(this@BindingsActivity, current.with(event, cmd))
                        }
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }

        col.addView(TextView(this).apply {
            text = "Choose what each gesture does. Changes apply immediately."
            textSize = 13f
        })
        heading("Hand gestures")
        ActionCatalog.handGestures.forEach { (e, l) -> row(e, l) }
        heading("Face gestures")
        ActionCatalog.faceGestures.forEach { (e, l) -> row(e, l) }
        col.addView(Button(this).apply {
            text = "Reset all to defaults"
            setOnClickListener {
                Settings.setBindings(this@BindingsActivity, Bindings.DEFAULT)
                recreate()
            }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
