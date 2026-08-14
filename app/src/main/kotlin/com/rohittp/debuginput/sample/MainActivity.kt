package com.rohittp.debuginput.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.rohittp.debuginput.sample.shared.debugInputView

/**
 * The whole Android app: it shows the debug inputs page and nothing else.
 *
 * `ComponentActivity` rather than `Activity` because `ComposeView` needs a lifecycle
 * owner in the view tree.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(debugInputView(this))
    }
}
