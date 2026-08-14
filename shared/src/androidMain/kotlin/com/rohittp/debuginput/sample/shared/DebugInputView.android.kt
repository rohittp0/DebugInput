package com.rohittp.debuginput.sample.shared

import android.content.Context
import android.view.View
import androidx.compose.ui.platform.ComposeView

/**
 * Hands `:app` a plain `View`, so the Android application module needs neither the
 * Kotlin Multiplatform plugin nor the Compose compiler plugin. AGP 9 refuses to apply
 * `com.android.application` alongside Kotlin Multiplatform, which is why the split
 * exists at all.
 */
fun debugInputView(context: Context): View = ComposeView(context).apply {
    setContent { SampleDebugScreen() }
}
