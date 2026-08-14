package com.rohittp.debuginput

import android.content.pm.ApplicationInfo
import android.content.SharedPreferences

private const val PREFERENCES_FILE = "debug_input_overrides"

internal actual fun platformOverrideStore(): OverrideStore? {
    val context = AndroidContextHolder.applicationContext ?: return null
    return SharedPreferencesOverrideStore(
        context.getSharedPreferences(PREFERENCES_FILE, android.content.Context.MODE_PRIVATE),
    )
}

/**
 * Read from the process's own manifest flag rather than from a generated per-variant
 * constant. `com.android.kotlin.multiplatform.library` has a single Android variant, so
 * a KMP module has no release compilation to skip — Android is in the same position as
 * iOS and inertness has to be a runtime property. See the amendment in
 * docs/adr/0002-android-release-skips-the-transform.md.
 *
 * No `Context` means the answer is unknowable, and an unprovable build is treated as
 * release: better a debug build where overrides briefly do not apply than a release
 * build where they do.
 */
internal actual val platformIsDebugBuild: Boolean
    get() {
        val context = AndroidContextHolder.applicationContext ?: return false
        return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

private class SharedPreferencesOverrideStore(
    private val preferences: SharedPreferences,
) : OverrideStore {

    override fun load(): Map<String, String> = buildMap {
        for ((key, value) in preferences.all) {
            if (value is String) put(key, value)
        }
    }

    override fun put(id: String, encoded: String) {
        preferences.edit().putString(id, encoded).apply()
    }

    override fun remove(id: String) {
        preferences.edit().remove(id).apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }
}
