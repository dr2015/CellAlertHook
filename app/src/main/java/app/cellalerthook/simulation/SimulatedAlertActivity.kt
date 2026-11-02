package app.cellalerthook.simulation

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import app.cellalerthook.R

/**
 * Stand-in for the system alert dialog. It deliberately uses the same view ids as the AOSP
 * dialog and classic views, so the accessibility path runs exactly as it would for real.
 */
class SimulatedAlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(R.layout.activity_simulated_alert)
        findViewById<TextView>(R.id.message).text = intent.getStringExtra(EXTRA_MESSAGE)
        findViewById<Button>(R.id.dismissButton).setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_MESSAGE = "message"
    }
}
