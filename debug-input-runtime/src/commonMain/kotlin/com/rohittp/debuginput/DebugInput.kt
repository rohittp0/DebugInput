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
    /**
     * Explicit documentation override. When omitted, the annotated declaration's KDoc is shown.
     * On an enum class, that documentation describes the enum's section page.
     */
    public val docs: String = "",
    /**
     * Page section title. When empty, the declaring class, object or file name is used for a
     * property, and the enum's name is used for an annotated enum class.
     */
    public val section: String = "",
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
