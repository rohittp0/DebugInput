package com.rohittp.debuginput

/**
 * The generated record of one debug input. Instances are built by IR-generated
 * descriptor functions, one per instrumented module, and read only by the page.
 */
public class DebugInputDescriptor(
    /** Stable identity, also the override store key. `com.app.physics.speed`. */
    public val id: String,
    /** What the page shows as the row label. `speed`, or `FREE.limit`. */
    public val displayName: String,
    /** Gradle project path the input was declared in. `:domain`. */
    public val module: String,
    /** Declaring class, object or enum; the file's base name for top-level properties. */
    public val section: String,
    /** Fully qualified name of the input's type. `kotlin.Int`. */
    public val typeKey: String,
    /** The declaration's KDoc, or its explicit annotation override, empty when absent. */
    public val docs: String,
    /** The value the property's initializer produced. */
    public val default: Any?,
    /** Constant names, for enum-typed inputs. Null for everything else. */
    public val enumConstants: List<String>? = null,
    /**
     * The codec spec literal for this input's type — `int`, `lst<str>`, `iarr`,
     * `pair<int,str>`. The page needs it to read and write overrides through the codec,
     * which works in specs rather than in type names.
     *
     * Empty until the compiler emits it, in which case the page treats the input as
     * `int` — the only type M1 supported.
     */
    public val spec: String = "",
    /** Documentation shown at the top of this section's page, empty when absent. */
    public val sectionDescription: String = "",
    /** Stable identity when this section has its own page; null for legacy/manual descriptors. */
    public val sectionPageId: String? = null,
) {
    override fun toString(): String = "DebugInputDescriptor($id: $typeKey = $default)"
}
