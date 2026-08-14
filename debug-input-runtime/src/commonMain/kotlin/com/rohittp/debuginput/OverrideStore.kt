package com.rohittp.debuginput

import kotlin.concurrent.Volatile

/**
 * Persistence for overrides, in a namespace of its own on every platform so that
 * clearing everything can never touch the consuming app's own data.
 *
 * Values are length-prefixed self-describing strings — see Codec.kt. Reads must be
 * synchronous, because the getters the IR plugin rewrites are.
 */
internal interface OverrideStore {
    fun load(): Map<String, String>
    fun put(id: String, encoded: String)
    fun remove(id: String)
    fun clear()
}

/**
 * The store the registry talks to: the platform one, unless a test has installed
 * something in its place.
 */
internal fun overrideStore(): OverrideStore? {
    val installed = installedStore
    return if (installed == null) platformOverrideStore() else installed.store
}

/**
 * Test seam. A handle holding null is how a test says "no store yet", which is a
 * state no platform can be talked into on demand.
 */
private class InstalledStore(val store: OverrideStore?)

@Volatile
private var installedStore: InstalledStore? = null

/** Test seam. Replaces the platform store with [store], which may be null. */
internal fun installOverrideStoreForTesting(store: OverrideStore?) {
    installedStore = InstalledStore(store)
}

/** Test seam. Hands the registry back to the platform store. */
internal fun uninstallOverrideStoreForTesting() {
    installedStore = null
}

/**
 * The platform store, or null when it is not usable yet. On Android that means the
 * `ContentProvider` has not captured a `Context`, which can happen if an input is
 * read from a static initialiser that runs before providers are created.
 */
internal expect fun platformOverrideStore(): OverrideStore?

/**
 * Whether this process is a debug build, unless a test has said otherwise.
 *
 * This is what makes a release build inert, so it is consulted on every resolve.
 */
internal fun isDebugBuild(): Boolean = installedIsDebugBuild ?: platformIsDebugBuild

@Volatile
private var installedIsDebugBuild: Boolean? = null

/**
 * Test seam. Release inertness is the whole promise of this library, so it needs to be
 * assertable — and neither platform can be talked into reporting a release build on
 * demand. Pass null to hand the answer back to the platform.
 */
internal fun installIsDebugBuildForTesting(value: Boolean?) {
    installedIsDebugBuild = value
}

/**
 * Whether this binary is a debug build.
 *
 * Both platforms answer at runtime. On iOS one klib serves debug and release, so the
 * answer comes from the linker. On Android the same is true of any Kotlin
 * Multiplatform module: `com.android.kotlin.multiplatform.library` has a single Android
 * variant, so there is no per-variant compilation to skip — see the amendment in
 * docs/adr/0002-android-release-skips-the-transform.md.
 */
internal expect val platformIsDebugBuild: Boolean

internal const val STORE_NAMESPACE: String = "com.rohittp.debuginput.overrides"
