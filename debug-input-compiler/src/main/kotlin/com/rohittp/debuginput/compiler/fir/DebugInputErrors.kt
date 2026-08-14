package com.rohittp.debuginput.compiler.fir

import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Every way `@DebugInput` can be misapplied. All of these are errors in both variants of
 * an Android build, so a misuse cannot survive to a release-only failure — see
 * docs/adr/0002-android-release-skips-the-transform.md.
 *
 * Each diagnostic is positioned on the property name and names the property in its
 * message, because the annotation itself carries no clue about which declaration it sits
 * on once the message reaches a build log.
 */
internal object DebugInputErrors : KtDiagnosticsContainer() {

    val CONST_VAL: KtDiagnosticFactory1<String> by error1<KtProperty, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val VAR_PROPERTY: KtDiagnosticFactory1<String> by error1<KtProperty, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val CUSTOM_GETTER: KtDiagnosticFactory1<String> by error1<KtProperty, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val DELEGATED_PROPERTY: KtDiagnosticFactory1<String> by error1<KtProperty, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val LOCAL_PROPERTY: KtDiagnosticFactory1<String> by error1<KtProperty, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val EXPECT_ACTUAL_PROPERTY: KtDiagnosticFactory1<String> by error1<KtProperty, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val UNSUPPORTED_TYPE: KtDiagnosticFactory2<String, String> by error2<KtProperty, String, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val NESTED_TYPE: KtDiagnosticFactory2<String, String> by error2<KtProperty, String, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val MAP_TYPE: KtDiagnosticFactory2<String, String> by error2<KtProperty, String, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val ENUM_IN_CONTAINER: KtDiagnosticFactory2<String, String> by error2<KtProperty, String, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val NOT_AN_ENUM_CLASS: KtDiagnosticFactory1<String> by error1<KtClass, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val ENUM_WITHOUT_INPUTS: KtDiagnosticFactory1<String> by error1<KtClass, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val ENUM_CONSTRUCTOR_VAR: KtDiagnosticFactory2<String, String> by error2<KtClass, String, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    val ENUM_CONSTRUCTOR_TYPE: KtDiagnosticFactory2<String, String> by error2<KtClass, String, String>(
        SourceElementPositioningStrategies.DECLARATION_NAME,
    )

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = DebugInputErrorMessages
}

/**
 * Message text for [DebugInputErrors]. Built lazily on first render: the map references
 * the factories and the factories reference this object, so eager construction would
 * deadlock the two initialisers.
 *
 * Messages go through `MessageFormat`, so they contain no apostrophes.
 */
internal object DebugInputErrorMessages : BaseDiagnosticRendererFactory() {

    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("DebugInput") { map ->
        map.put(
            DebugInputErrors.CONST_VAL,
            "@DebugInput cannot be applied to const val {0}: a const val is inlined at every " +
                "use site, so there is no getter to intercept.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.VAR_PROPERTY,
            "@DebugInput cannot be applied to var {0}: the setter would write a backing field " +
                "that the rewritten getter ignores.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.CUSTOM_GETTER,
            "@DebugInput cannot be applied to {0}, which has a custom getter: there is no " +
                "initializer to take the default from.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.DELEGATED_PROPERTY,
            "@DebugInput cannot be applied to the delegated property {0}: there is no " +
                "initializer to take the default from.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.LOCAL_PROPERTY,
            "@DebugInput cannot be applied to the local val {0}: a local val has no fully " +
                "qualified name to derive an id from.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.EXPECT_ACTUAL_PROPERTY,
            "@DebugInput cannot be applied to {0}, which is an expect or actual property: the " +
                "default lives in the actual and the id would be derived twice.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.UNSUPPORTED_TYPE,
            "@DebugInput cannot be applied to {0} of type {1}: nothing knows how to store that " +
                "type. Supported are Int, Long, Short, Byte, Float, Double, Boolean, Char, " +
                "String, any enum class, and List, Set, Array, IntArray and its seven siblings, " +
                "Pair and Triple holding one of those.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.NESTED_TYPE,
            "@DebugInput cannot be applied to {0} of type {1}: a container may hold scalars and " +
                "nothing else, so it cannot hold another container. One nesting level is a " +
                "deliberate limit on the codec, not an oversight; a custom renderer is the " +
                "escape hatch for a value shaped like this.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.MAP_TYPE,
            "@DebugInput cannot be applied to {0} of type {1}: Map is not one of the supported " +
                "container types, whatever its key and value types are. A custom renderer is " +
                "the escape hatch for it.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.ENUM_IN_CONTAINER,
            "@DebugInput cannot be applied to {0} of type {1}: an enum is supported as an input " +
                "on its own but not inside a container. An override stores a constant by name, " +
                "and turning a name back into a constant needs the table of constants for that " +
                "enum. Generated code passes that table in for a whole-input enum, but there is " +
                "nowhere to put it for an element type: it cannot travel inside a codec spec " +
                "string, and Kotlin/Native has no reflection to recover it.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.NOT_AN_ENUM_CLASS,
            "@DebugInput on the class {0} does nothing: on a class the annotation only means " +
                "something for an enum class, where it makes every constructor val of every " +
                "constant an input. Annotate the properties you want to tweak instead.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.ENUM_WITHOUT_INPUTS,
            "@DebugInput on the enum class {0} has nothing to instrument: it declares no " +
                "constructor val of a supported type. A val in the enum body is one value shared " +
                "by every constant, not one per constant, so it is not an input.",
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.ENUM_CONSTRUCTOR_VAR,
            "@DebugInput cannot instrument {0}: it is a var, and the setter would write a backing " +
                "field that the rewritten getter ignores. Every other constructor val of {1} is " +
                "still instrumented.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            DebugInputErrors.ENUM_CONSTRUCTOR_TYPE,
            "@DebugInput cannot instrument {0} of type {1}: nothing knows how to store that type. " +
                "Every other constructor val of the enum is still instrumented.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
    }
}
