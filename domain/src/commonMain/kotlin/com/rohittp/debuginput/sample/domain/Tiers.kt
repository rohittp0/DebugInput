package com.rohittp.debuginput.sample.domain

import com.rohittp.debuginput.DebugInput

/**
 * Object member. Its id is `com.rohittp.debuginput.sample.domain.Tiers.freeLimit` and
 * its page section is `Tiers`.
 */
object Tiers {

    /**
     * A deliberately non-constant default: it is computed from another property, so
     * the compiler cannot fold it. The transform must take the default from the
     * property's own backing field rather than from the initializer expression, and
     * this is what proves it does.
     */
    @DebugInput(docs = "Items a free account may create")
    val freeLimit: Int = baseLimit * 5

    @DebugInput(docs = "Items a paid account may create")
    val paidLimit: Int = 1_000
}

private val baseLimit: Int = 5
