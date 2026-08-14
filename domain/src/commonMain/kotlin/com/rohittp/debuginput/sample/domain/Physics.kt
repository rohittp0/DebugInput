package com.rohittp.debuginput.sample.domain

import com.rohittp.debuginput.DebugInput

/**
 * Internal top-level property. Its id is the plain fully qualified name,
 * `com.rohittp.debuginput.sample.domain.speed`, because the compiler already
 * guarantees that name is unique.
 */
@DebugInput(docs = "Player speed in m/s")
val speed: Int = 10

/**
 * Private top-level property. Its id carries the file name —
 * `com.rohittp.debuginput.sample.domain.Physics.kt.droppedFrameBudget` — because two
 * files in one package may each declare a private property of the same name.
 * See docs/adr/0005-id-derivation-and-dormant-overrides.md.
 */
@DebugInput(docs = "Frames dropped before the animation degrades")
private val droppedFrameBudget: Int = 3

/** Reads the private input, since nothing outside this file can. */
fun animationBudget(): Int = droppedFrameBudget
