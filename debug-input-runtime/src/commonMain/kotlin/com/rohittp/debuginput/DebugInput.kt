package com.rohittp.debuginput

/**
 * Marks a `val` as a debug input: readable normally, but resolved through
 * [DebugInputRegistry] in debug builds so it can be changed at runtime.
 *
 * Applied to an enum class, every constructor `val` of every constant becomes an
 * input of its own.
 *
 * Retention is [AnnotationRetention.BINARY] rather than SOURCE on purpose. A
 * `rememberDebugInput { … }` in one module referring to a property in another needs
 * the consuming module's frontend to see this annotation, and a source-retained
 * annotation is absent from both the klib and the class metadata.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class DebugInput(
    /** Shown behind the info icon on the page. */
    public val docs: String = "",
)

/**
 * Marks API that exists only so `debug-input-compose` and generated code can reach
 * it. Not covered by any compatibility guarantee.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to debug-input. It may change or disappear in any release.",
)
@Retention(AnnotationRetention.BINARY)
public annotation class DebugInputInternalApi
