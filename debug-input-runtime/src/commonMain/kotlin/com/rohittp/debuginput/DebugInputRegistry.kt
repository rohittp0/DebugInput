package com.rohittp.debuginput

import kotlin.concurrent.Volatile

/**
 * Resolves debug input reads. Every getter the IR plugin rewrites calls into here.
 *
 * Overrides are held in an immutable map behind a `@Volatile` reference, hydrated on
 * the first read and replaced wholesale on every write. That makes the read path —
 * the only hot one — a volatile read and a map lookup with no locking. Writes come
 * from the page, on one thread.
 *
 * This artifact has no dependencies, so there is no atomics library to reach for. A
 * race between two first reads can hydrate twice, which is idempotent and harmless.
 */
public object DebugInputRegistry {

    @Volatile
    private var overrides: Map<String, String>? = null

    @Volatile
    private var listeners: Map<String, List<Listener>> = emptyMap()

    /** Parsed spec literals. The same handful recur on every read of every input. */
    @Volatile
    private var specs: Map<String, TypeSpec> = emptyMap()

    /**
     * Decoded array instances, keyed by id, alongside the override map they were decoded
     * from. Any write replaces that map wholesale, so an identity check on it is the
     * whole of the invalidation ADR-0009 asks for. See [cachedArray].
     */
    @Volatile
    private var decodedArrays: DecodedArrays? = null

    private class Listener(val token: Any, val onChange: (Any?) -> Unit)

    private class DecodedArrays(val source: Map<String, String>, val byId: Map<String, Any>)

    // ---- Read path. Called by generated code. ----

    public fun resolveInt(id: String, default: Int): Int {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeInt(encoded) ?: default
    }

    public fun resolveLong(id: String, default: Long): Long {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeLong(encoded) ?: default
    }

    public fun resolveShort(id: String, default: Short): Short {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeShort(encoded) ?: default
    }

    public fun resolveByte(id: String, default: Byte): Byte {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeByte(encoded) ?: default
    }

    public fun resolveFloat(id: String, default: Float): Float {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeFloat(encoded) ?: default
    }

    public fun resolveDouble(id: String, default: Double): Double {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeDouble(encoded) ?: default
    }

    public fun resolveBoolean(id: String, default: Boolean): Boolean {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeBoolean(encoded) ?: default
    }

    public fun resolveChar(id: String, default: Char): Char {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeChar(encoded) ?: default
    }

    public fun resolveString(id: String, default: String): String {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        return decodeString(encoded) ?: default
    }

    /**
     * The wire form holds a constant's name, and turning that back into a constant needs
     * the entry table — which generated code passes in, because Kotlin/Native has no
     * reflection to recover it. A name no constant answers to resolves to [default]:
     * that is a constant that was renamed or removed since the override was stored.
     */
    public fun <T : Enum<T>> resolveEnum(id: String, default: T, entries: Array<out T>): T {
        if (!isDebugBuild()) return default
        val encoded = current()[id] ?: return default
        val name = decodeEnumName(encoded) ?: return default
        for (entry in entries) if (entry.name == name) return entry
        return default
    }

    /**
     * Resolves a composite. [spec] is the codec spec literal baked in at the call site,
     * and the caller casts the result to the input's own type.
     *
     * With no override stored this returns [default] — the property's own backing-field
     * instance, so identity and in-place mutation of an array input are exactly what they
     * are without the plugin. With one stored, array-tagged specs return a cached
     * instance per id. See docs/adr/0009-array-inputs-return-a-cached-instance.md.
     */
    public fun resolveComposite(id: String, default: Any?, spec: String): Any? {
        if (!isDebugBuild()) return default
        val overrides = current()
        val encoded = overrides[id] ?: return default
        val parsed = specFor(spec) ?: return default
        if (!isArrayTag(parsed.tag)) return decodeValue(encoded, parsed) ?: default
        return cachedArray(id, encoded, parsed, overrides) ?: default
    }

    // ---- Everything below is for the page. ----

    @DebugInputInternalApi
    public val isDebugBuild: Boolean get() = isDebugBuild()

    /**
     * The decoded override for [id], or null when the input is at its default or the
     * stored bytes cannot be read at all.
     *
     * Type-blind: it is given an id and nothing else, and the wire form is
     * self-describing, so a dormant override left by another type reads back as *that*
     * type — a `Boolean` on what is now an `Int` input. Reads of the input resolve their
     * default in that case, so a caller holding the descriptor has to reconcile the two.
     */
    @DebugInputInternalApi
    public fun overrideOf(id: String): Any? = current()[id]?.let(::decodeAny)

    /**
     * The decoded override for [id], or null when the input is at its default **or** the
     * stored value is not of [spec]'s shape.
     *
     * This is the overload the page should use. [overrideOf] is type-blind, so a dormant
     * override left behind by an input of another type reads back as that other type,
     * letting a row show as changed while every read of the input resolves its default.
     * Checking against the spec makes the page agree with the read path.
     */
    @DebugInputInternalApi
    public fun overrideOf(id: String, spec: String): Any? {
        val encoded = current()[id] ?: return null
        val parsed = specFor(spec) ?: return null
        return decodeValue(encoded, parsed)
    }

    /**
     * Stores an override for [id].
     *
     * Silently does nothing when the override store is unavailable, which on Android
     * means no `Context` has been captured yet. The page is given no signal, because
     * there is nothing useful it could do: the state is unreachable on iOS and only
     * reachable on Android before any UI exists.
     */
    @DebugInputInternalApi
    public fun setInt(id: String, value: Int) {
        write(id, encodeInt(value), value)
    }

    /**
     * Stores an override of any supported type. [spec] is the same codec spec literal the
     * read path uses; a [value] that is not that shape is refused rather than stored, so a
     * page bug cannot write an override no read will ever accept.
     */
    @DebugInputInternalApi
    public fun setValue(id: String, value: Any?, spec: String) {
        val parsed = specFor(spec) ?: return
        val encoded = encodeValue(value, parsed) ?: return
        write(id, encoded, value)
    }

    @DebugInputInternalApi
    public fun clearOverride(id: String) {
        val store = overrideStore() ?: return
        store.remove(id)
        overrides = current() - id
        notifyListeners(id, null)
    }

    /**
     * Drops every override, including ones no input currently claims. Abandoned keys
     * are otherwise kept forever and simply ignored, so this is the only way to be
     * rid of them.
     */
    @DebugInputInternalApi
    public fun clearAll() {
        val store = overrideStore() ?: return
        store.clear()
        // Explicit, unlike everywhere else: an emptied map is `emptyMap()`, which is a
        // singleton, so the identity check the array cache relies on cannot see the
        // change. Nothing could read a stale array through it — with no overrides left
        // every read returns its default before reaching the cache — but leaving the
        // reasoning to the reader is worse than one assignment.
        decodedArrays = null
        overrides = emptyMap()
        listeners.keys.toList().forEach { notifyListeners(it, null) }
    }

    /** Registers [listener] for changes to [id]. Returns the removal function. */
    @DebugInputInternalApi
    public fun addListener(id: String, listener: (Any?) -> Unit): () -> Unit {
        val entry = Listener(token = Any(), onChange = listener)
        listeners = listeners + (id to ((listeners[id] ?: emptyList()) + entry))
        return {
            val remaining = (listeners[id] ?: emptyList()).filter { it.token !== entry.token }
            listeners = if (remaining.isEmpty()) listeners - id else listeners + (id to remaining)
        }
    }

    /** Test seam. Forces the next read to hydrate from the store again. */
    @DebugInputInternalApi
    public fun resetForTesting() {
        overrides = null
        listeners = emptyMap()
        decodedArrays = null
    }

    private fun specFor(spec: String): TypeSpec? {
        specs[spec]?.let { return it }
        val parsed = parseTypeSpec(spec) ?: return null
        specs = specs + (spec to parsed)
        return parsed
    }

    /**
     * The decoded array for [id], reused for as long as [source] is the override map it
     * was decoded from. Every write replaces that map, so a page edit swaps the instance
     * — including for array inputs the edit did not touch, which is what "invalidated
     * whenever the override map changes" buys: no per-id bookkeeping, and no chance of
     * handing back an instance decoded from an override that is no longer stored.
     */
    private fun cachedArray(
        id: String,
        encoded: String,
        spec: TypeSpec,
        source: Map<String, String>,
    ): Any? {
        val cache = decodedArrays?.takeIf { it.source === source }
        cache?.byId?.get(id)?.let { return it }

        val decoded = decodeValue(encoded, spec) ?: return null
        decodedArrays = DecodedArrays(source, (cache?.byId ?: emptyMap()) + (id to decoded))
        return decoded
    }

    private fun write(id: String, encoded: String, decoded: Any?) {
        val store = overrideStore() ?: return
        store.put(id, encoded)
        overrides = current() + (id to encoded)
        notifyListeners(id, decoded)
    }

    private fun current(): Map<String, String> {
        overrides?.let { return it }
        // No store yet means no Context yet. Return empty without caching, so a later
        // read hydrates properly rather than being stuck with an empty map forever.
        val store = overrideStore() ?: return emptyMap()
        return store.load().also { overrides = it }
    }

    private fun notifyListeners(id: String, value: Any?) {
        listeners[id]?.forEach { it.onChange(value) }
    }
}
