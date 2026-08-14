package com.rohittp.debuginput

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSUserDefaults

internal actual fun platformOverrideStore(): OverrideStore? = NSUserDefaultsOverrideStore

// Resolved at link time, which is the only place a debug and a release iOS binary
// differ — one klib feeds both.
// See docs/adr/0002-android-release-skips-the-transform.md.
@OptIn(ExperimentalNativeApi::class)
internal actual val platformIsDebugBuild: Boolean get() = Platform.isDebugBinary

private object NSUserDefaultsOverrideStore : OverrideStore {

    private val defaults: NSUserDefaults = NSUserDefaults(suiteName = STORE_NAMESPACE)

    /**
     * Reads and clears go through the suite's persistent domain, never through
     * `dictionaryRepresentation()`. That returns the whole search list merged —
     * `NSGlobalDomain` and the app's own domain included — so it would report
     * `AppleLocale` and friends as overrides, and hand [clear] keys that are not
     * ours to delete.
     */
    override fun load(): Map<String, String> {
        val suite = defaults.persistentDomainForName(STORE_NAMESPACE) ?: return emptyMap()
        return buildMap {
            for ((key, value) in suite) {
                val id = key as? String ?: continue
                val encoded = value as? String ?: continue
                put(id, encoded)
            }
        }
    }

    override fun put(id: String, encoded: String) {
        defaults.setObject(encoded, forKey = id)
    }

    override fun remove(id: String) {
        defaults.removeObjectForKey(id)
    }

    override fun clear() {
        defaults.removePersistentDomainForName(STORE_NAMESPACE)
    }
}
