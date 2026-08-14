package com.rohittp.debuginput

/**
 * Stand-ins for the `debug-input-runtime` declarations the plugin resolves against.
 *
 * `:debug-input-runtime` has no JVM target — it publishes `androidTarget()` and three iOS
 * targets only — so these tests cannot depend on it. They live in the real package and
 * carry the real signatures instead, which is all the plugin looks up: the annotation's
 * `ClassId`, the registry's resolvers and the `DebugInputDescriptor` constructor.
 *
 * Keep these in sync with
 * `debug-input-runtime/src/commonMain/kotlin/com/rohittp/debuginput/`.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class DebugInput(val docs: String = "", val section: String = "")

/**
 * Records which resolver each rewritten getter called and with what, and hands back whatever
 * [overrides] says. That is enough to prove type dispatch: the resolver name is the dispatch
 * decision, and the recorded `spec` is what the page would pick a renderer by.
 */
object DebugInputRegistry {

    /** One resolver call. [spec] is empty for the scalar fast paths, which take no spec. */
    data class Call(
        val id: String,
        val resolver: String,
        val default: Any?,
        val spec: String = "",
        val entries: List<String> = emptyList(),
    )

    val calls: MutableList<Call> = mutableListOf()

    val overrides: MutableMap<String, Any?> = mutableMapOf()

    fun reset() {
        calls.clear()
        overrides.clear()
    }

    fun resolveInt(id: String, default: Int): Int = scalar(id, "resolveInt", default)

    fun resolveLong(id: String, default: Long): Long = scalar(id, "resolveLong", default)

    fun resolveShort(id: String, default: Short): Short = scalar(id, "resolveShort", default)

    fun resolveByte(id: String, default: Byte): Byte = scalar(id, "resolveByte", default)

    fun resolveFloat(id: String, default: Float): Float = scalar(id, "resolveFloat", default)

    fun resolveDouble(id: String, default: Double): Double = scalar(id, "resolveDouble", default)

    fun resolveBoolean(id: String, default: Boolean): Boolean = scalar(id, "resolveBoolean", default)

    fun resolveChar(id: String, default: Char): Char = scalar(id, "resolveChar", default)

    fun resolveString(id: String, default: String): String = scalar(id, "resolveString", default)

    fun <T : Enum<T>> resolveEnum(id: String, default: T, entries: Array<out T>): T {
        calls += Call(id, "resolveEnum", default, entries = entries.map { it.name })
        @Suppress("UNCHECKED_CAST")
        return overrides[id] as? T ?: default
    }

    fun resolveComposite(id: String, default: Any?, spec: String): Any? {
        calls += Call(id, "resolveComposite", default, spec = spec)
        return if (overrides.containsKey(id)) overrides[id] else default
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(id: String, resolver: String, default: T): T {
        calls += Call(id, resolver, default)
        return overrides[id] as? T ?: default
    }
}

/**
 * Constructor signature must match the runtime's, including the parameters the plugin leaves at
 * their defaults: filling those is the default-argument lowering's job, and a stub without them
 * would not exercise it.
 */
class DebugInputDescriptor(
    val id: String,
    val displayName: String,
    val module: String,
    val section: String,
    val typeKey: String,
    val docs: String,
    val default: Any?,
    val enumConstants: List<String>? = null,
    val spec: String = "",
    val sectionDescription: String = "",
    val sectionPageId: String? = null,
) {
    override fun toString(): String =
        "$id|$displayName|$module|$section|$typeKey|$docs|$default|$enumConstants|$spec|" +
            "$sectionDescription|$sectionPageId"
}
