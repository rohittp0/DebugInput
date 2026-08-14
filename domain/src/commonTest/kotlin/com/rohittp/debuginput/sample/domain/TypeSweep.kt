package com.rohittp.debuginput.sample.domain

import com.rohittp.debuginput.DebugInputInternalApi
import com.rohittp.debuginput.DebugInputRegistry
import kotlin.test.assertEquals

/**
 * The whole supported type table, asserted through the real override store of whichever
 * platform runs it. Lives in `commonTest` so the Android and iOS suites share one list —
 * a type covered on one platform and forgotten on the other is exactly the gap this
 * project keeps finding.
 *
 * Values are compared as strings. Arrays do not have structural `equals`, and a mismatch
 * message that reads `[100, 400, 1600]` beats one that reads `[I@3f2a`.
 */
internal class TypeCase(
    val id: String,
    val spec: String,
    /** The value the page would hand to `setValue`. */
    val override: Any?,
    val expectedDefault: String,
    val expectedOverridden: String,
    val read: () -> String,
)

private const val PACKAGE = "com.rohittp.debuginput.sample.domain"

private fun show(value: Any?): String = when (value) {
    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "[", postfix = "]")
    is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
    is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
    is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
    is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
    is CharArray -> value.joinToString(prefix = "[", postfix = "]")
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]")
    else -> value.toString()
}

internal fun everySupportedType(): List<TypeCase> = listOf(
    TypeCase("$PACKAGE.requestsPerMinute", "int", 120, "60", "120") { show(requestsPerMinute) },
    TypeCase("$PACKAGE.cacheBudgetBytes", "lng", 9_000_000_000L, "50000000", "9000000000") {
        show(cacheBudgetBytes)
    },
    TypeCase("$PACKAGE.retryLimit", "sht", 9.toShort(), "3", "9") { show(retryLimit) },
    TypeCase("$PACKAGE.compressionLevel", "byt", 1.toByte(), "6", "1") { show(compressionLevel) },
    TypeCase("$PACKAGE.animationScale", "flt", 0.5f, "1.0", "0.5") { show(animationScale) },
    TypeCase("$PACKAGE.fallbackLatitude", "dbl", -0.5, "12.9716", "-0.5") {
        show(fallbackLatitude)
    },
    TypeCase("$PACKAGE.useExperimentalRenderer", "bln", true, "false", "true") {
        show(useExperimentalRenderer)
    },
    TypeCase("$PACKAGE.csvSeparator", "chr", ';', ",", ";") { show(csvSeparator) },
    TypeCase(
        "$PACKAGE.apiBaseUrl", "str", "https://staging.example.com:8443/v2",
        "https://api.example.com", "https://staging.example.com:8443/v2",
        // Colons in the payload: the case length prefixes exist for.
    ) { show(apiBaseUrl) },
    TypeCase("$PACKAGE.defaultTier", "enm", Tier.TEAM, "FREE", "TEAM") { show(defaultTier) },

    TypeCase(
        "$PACKAGE.apiHosts", "lst<str>", listOf("only.example.com"),
        "[api.example.com, cdn.example.com]", "[only.example.com]",
    ) { show(apiHosts) },
    TypeCase(
        "$PACKAGE.retryableStatuses", "set<int>", setOf(500, 502, 504),
        "[429, 503]", "[500, 502, 504]",
    ) { show(retryableStatuses) },
    TypeCase(
        "$PACKAGE.startupFlags", "arr<str>", arrayOf("slow-boot"),
        "[fast-boot, prefetch]", "[slow-boot]",
    ) { show(startupFlags) },

    TypeCase(
        "$PACKAGE.backoffMillis", "iarr", intArrayOf(50), "[100, 400, 1600]", "[50]",
    ) { show(backoffMillis) },
    TypeCase(
        "$PACKAGE.rolloverThresholds", "larr", longArrayOf(7L), "[1000, 1000000]", "[7]",
    ) { show(rolloverThresholds) },
    TypeCase(
        "$PACKAGE.probePorts", "sarr", shortArrayOf(8080.toShort()), "[80, 443]", "[8080]",
    ) { show(probePorts) },
    TypeCase(
        "$PACKAGE.cacheMagic", "barr", byteArrayOf(1, 2, 3), "[127, 69]", "[1, 2, 3]",
    ) { show(cacheMagic) },
    TypeCase(
        "$PACKAGE.samplingWeights", "farr", floatArrayOf(1.0f), "[0.25, 0.75]", "[1.0]",
    ) { show(samplingWeights) },
    TypeCase(
        "$PACKAGE.rankingWeights", "darr", doubleArrayOf(2.0, 1.0),
        "[1.0, 0.5, 0.25]", "[2.0, 1.0]",
    ) { show(rankingWeights) },
    TypeCase(
        "$PACKAGE.stagesEnabled", "zarr", booleanArrayOf(false),
        "[true, false, true]", "[false]",
    ) { show(stagesEnabled) },
    TypeCase(
        "$PACKAGE.strippedChars", "carr", charArrayOf('x', 'y'),
        "[\n, \t]", "[x, y]",
    ) { show(strippedChars) },

    TypeCase(
        "$PACKAGE.retryStrategy", "pair<int,str>", 9 to "linear",
        "(3, exponential)", "(9, linear)",
    ) { show(retryStrategy) },
    TypeCase(
        "$PACKAGE.viewport", "trip<int,int,bln>", Triple(720, 1280, false),
        "(1080, 1920, true)", "(720, 1280, false)",
    ) { show(viewport) },
)

/**
 * Every supported type reads its default, takes an override that survives a relaunch, and
 * returns to its default when the override is cleared — through the platform's real store.
 */
@OptIn(DebugInputInternalApi::class)
internal fun assertEverySupportedTypeOverrides() {
    val cases = everySupportedType()
    assertEquals(23, cases.size, "the type table has 23 entries")

    for (case in cases) {
        assertEquals(case.expectedDefault, case.read(), "default of ${case.id}")

        DebugInputRegistry.setValue(case.id, case.override, case.spec)
        assertEquals(case.expectedOverridden, case.read(), "override of ${case.id}")

        // The process restarts: the registry forgets its hydrated map, the store does not.
        DebugInputRegistry.resetForTesting()
        assertEquals(
            case.expectedOverridden,
            case.read(),
            "override of ${case.id} after a relaunch",
        )

        DebugInputRegistry.clearOverride(case.id)
        assertEquals(case.expectedDefault, case.read(), "default of ${case.id} once cleared")
    }
}

/**
 * A composite whose stored value has the wrong shape resolves its default rather than
 * failing the cast the compiler inserts around `resolveComposite`. Worth asserting on the
 * real store: the guard is the spec-shape check, and if it ever regressed the failure would
 * be a `ClassCastException` from inside generated code.
 */
@OptIn(DebugInputInternalApi::class)
internal fun assertDormantCompositeResolvesItsDefault() {
    val hosts = "$PACKAGE.apiHosts"

    // A well-formed override of a different shape, as a rename could leave behind.
    DebugInputRegistry.setValue(hosts, setOf(1, 2), "set<int>")
    DebugInputRegistry.resetForTesting()

    assertEquals("[api.example.com, cdn.example.com]", show(apiHosts))

    // Dormant, not deleted — ADR-0005.
    assertEquals(setOf(1, 2), DebugInputRegistry.overrideOf(hosts, "set<int>"))
}
