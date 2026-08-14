package com.rohittp.debuginput.smoke

import com.rohittp.debuginput.DebugInput

/** Proves that consumer-source KDoc reaches a plugin-generated descriptor. */
@DebugInput(section = "Smoke")
internal val frameRate: Int = 60

internal fun currentFrameRate(): Int = frameRate
