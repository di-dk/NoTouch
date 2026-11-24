package dev.notouch.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Per-app settings: start gestures automatically when an app opens, and give an app its own gesture actions. */
class AppsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Apps"
    }

    override fun onResume() {
        super.onResume()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "Auto-start turns the camera gestures on when you open the app and off when you leave it. " +
                "NoTouch must be enabled as an accessibility service. Android may block starting the camera " +
                "from the background; if gestures do not start, open NoTouch once and start them by hand.\n" +
                "NoTouch only sees which app is in front (its package name), never its content."
            textSize = 13f
        })
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launch, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val auto = Settings.autoStartApps(this)
        for ((pkg, label) in apps) {
            col.addView(CheckBox(this).apply {
                text = label
                isChecked = pkg in auto
                setPadding(0, dp(12), 0, 0)
                setOnCheckedChangeListener { _, v -> Settings.setAutoStart(this@AppsActivity, pkg, v) }
            })
            col.addView(Button(this).apply {
                text = if (Settings.hasProfile(this@AppsActivity, pkg)) "Gesture actions (custom)" else "Gesture actions (global)"
                setOnClickListener {
                    startActivity(Intent(this@AppsActivity, BindingsActivity::class.java).putExtra(BindingsActivity.EXTRA_PACKAGE, pkg))
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
