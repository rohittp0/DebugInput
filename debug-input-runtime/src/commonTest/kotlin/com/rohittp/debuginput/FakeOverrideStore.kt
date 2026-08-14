package com.rohittp.debuginput

/**
 * In-memory [OverrideStore] standing in for SharedPreferences and `NSUserDefaults`.
 *
 * [persisted] outlives `resetForTesting()`, which is how these tests simulate a
 * relaunch: the process forgets its hydrated map, the override store does not.
 */
internal class FakeOverrideStore(initial: Map<String, String> = emptyMap()) : OverrideStore {

    val persisted: MutableMap<String, String> = initial.toMutableMap()

    /** How many times the registry has hydrated from this store. */
    var loads: Int = 0
        private set

    override fun load(): Map<String, String> {
        loads++
        return persisted.toMap()
    }

    override fun put(id: String, encoded: String) {
        persisted[id] = encoded
    }

    override fun remove(id: String) {
        persisted.remove(id)
    }

    override fun clear() {
        persisted.clear()
    }
}

/**
 * Installs a fake override store holding [initial] and leaves the registry cold, so
 * the next read hydrates from it.
 */
@OptIn(DebugInputInternalApi::class)
internal fun installFakeOverrideStore(
    initial: Map<String, String> = emptyMap(),
): FakeOverrideStore {
    val fake = FakeOverrideStore(initial)
    installOverrideStoreForTesting(fake)
    // A JVM host test has no debuggable flag to read, so the platform reports release
    // and every override would be ignored. Overriding it is what lets these tests be
    // about resolution rather than about build types.
    installIsDebugBuildForTesting(true)
    DebugInputRegistry.resetForTesting()
    return fake
}

/**
 * Marks the override store unavailable — Android before the `ContentProvider` has
 * captured a `Context` — and leaves the registry cold.
 */
@OptIn(DebugInputInternalApi::class)
internal fun makeOverrideStoreUnavailable() {
    installOverrideStoreForTesting(null)
    installIsDebugBuildForTesting(true)
    DebugInputRegistry.resetForTesting()
}

/** Undoes both seams and leaves the registry cold for whatever runs next. */
@OptIn(DebugInputInternalApi::class)
internal fun restorePlatformOverrideStore() {
    uninstallOverrideStoreForTesting()
    installIsDebugBuildForTesting(null)
    DebugInputRegistry.resetForTesting()
}

/** Ids shaped the way ADR-0005 derives them. */
internal const val SPEED: String = "com.app.physics.speed"
internal const val TIMEOUT: String = "com.app.Config.timeout"
internal const val FREE_LIMIT: String = "com.app.Tier.FREE.limit"

/** A spec literal, parsed. Fails the test rather than the assertion if the literal is bad. */
internal fun spec(literal: String): TypeSpec =
    requireNotNull(parseTypeSpec(literal)) { "unparseable spec literal '$literal'" }

/**
 * [value] on the wire, for seeding an override store the way an earlier launch would
 * have left it.
 */
internal fun encoded(value: Any?, literal: String): String =
    requireNotNull(encodeValue(value, spec(literal))) { "$value is not a $literal" }

/** An enum to be an enum input, named the way CONTEXT.md's examples are. */
internal enum class Tier { FREE, PRO, TEAM }

internal val tierEntries: Array<Tier> = Tier.entries.toTypedArray()
