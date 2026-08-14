package com.rohittp.debuginput.gradle

/** The single Kotlin minor this release of debug-input is built against. See ADR-0001. */
internal const val SUPPORTED_KOTLIN_MINOR = "2.3"

/**
 * The message to fail the build with when [kotlinVersion] is not the Kotlin minor this
 * release supports, or null when it is.
 *
 * `debug-input-compiler` links `kotlin-compiler-embeddable` as `compileOnly`, so a
 * mismatch does not fail dependency resolution — it surfaces as a `NoSuchMethodError`
 * from inside FIR, at a point where nothing in the stack trace names debug-input. This
 * message is the whole reason the guard exists, so it names both versions and says what
 * to do about it.
 */
internal fun kotlinVersionMismatchMessage(pluginVersion: String, kotlinVersion: String): String? {
    if (minorOf(kotlinVersion) == SUPPORTED_KOTLIN_MINOR) return null
    return "debug-input $pluginVersion supports Kotlin $SUPPORTED_KOTLIN_MINOR only, " +
        "but this build uses Kotlin $kotlinVersion. debug-input-compiler is built " +
        "against Kotlin $SUPPORTED_KOTLIN_MINOR's compiler internals, which are not a " +
        "stable API, so running it on another Kotlin version fails inside the compiler " +
        "instead of here. Either move this build to Kotlin $SUPPORTED_KOTLIN_MINOR.x, " +
        "or use a debug-input release built for Kotlin $kotlinVersion."
}

/**
 * `2.3.21` and `2.3.20-Beta2` both yield `2.3`. Anything this cannot read yields null and
 * is reported as a mismatch: an unrecognisable version is exactly the case where
 * guessing costs a `NoSuchMethodError`.
 */
private fun minorOf(kotlinVersion: String): String? {
    val parts = kotlinVersion.split('.')
    if (parts.size < 2) return null
    val major = parts[0].takeWhile(Char::isDigit)
    val minor = parts[1].takeWhile(Char::isDigit)
    if (major.isEmpty() || minor.isEmpty()) return null
    return "$major.$minor"
}
