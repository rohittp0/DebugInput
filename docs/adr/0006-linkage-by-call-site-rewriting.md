# Everything is linked by call-site rewriting, not registration

Three separate seams in this design need a symbol that only exists downstream of the
code that wants it. All three are solved the same way: the IR plugin rewrites the
consumer's call site, because the call site is always in a module that can see what
the callee cannot.

| Call site as written | After IR |
|---|---|
| `DebugInputsPage()` | `DebugInputsPage(descriptors = descriptors_app())` |
| `rememberDebugInput { speed }` | `debugInputState("com.app.physics.speed", 10)` |
| `speed` (getter body) | `DebugInputRegistry.resolveInt("com.app.physics.speed", speed$default)` |

The alternatives are closed off by constraints recorded in the design note:
Kotlin/Native has no `ServiceLoader` and no classpath scanning, and dead-code
elimination strips registrations nothing references — so self-registering inputs
would leave the page listing only what had already been read. Making the consumer
name the generated symbol (`DebugInputsPage(descriptors_app())`) would put a
module-name-mangled generated symbol into hand-written source, where a module rename
breaks compilation.

Collapsing the per-module descriptor functions into a single aggregate emitted in the
page's module is also impossible, not merely undesirable: a descriptor's default is read
from a *private* backing field, so it can only be constructed inside its own module.
That constraint is what forces hierarchical aggregation.

> **Amended during M1 implementation: the constraint is tighter than "module".** Kotlin
> fields are always private to their own file, and Kotlin/Native's IR validator enforces
> it — `Access to a field declared in another file`, and `Kotlin fields are expected to
> always be private` when the transform tried widening one. The JVM backend accepted both,
> so this only surfaced on iOS.
>
> Descriptors are therefore built by a **per-file** helper emitted into the file that
> declares the inputs, where the field reads are local. The module's descriptor function
> only concatenates those helpers and its dependencies' module functions:
>
> ```kotlin
> // in Physics.kt
> fun `$debugInputDescriptors$Physics_kt`(): List<DebugInputDescriptor> = …
>
> // in the generated file
> fun descriptors_domain(): List<DebugInputDescriptor> {
>     val all = mutableListOf<DebugInputDescriptor>()
>     all.addAll(`$debugInputDescriptors$Physics_kt`())
>     all.addAll(`$debugInputDescriptors$Tiers_kt`())
>     all.addAll(descriptors_shared())
>     return all
> }
> ```
>
> No field widening happens anywhere, which retracts the widening note added earlier in
> this ADR.
>
> One further trap, also iOS-only: **an HMPP compilation runs the frontend once per source
> set**, and asks every session's generation extensions for their top-level declarations.
> `:domain` compiling for `iosSimulatorArm64` has five sessions (`<commonMain>`,
> `<nativeMain>`, `<appleMain>`, `<iosMain>`, `<com.rohittp:domain>`), so a naive extension
> declares the descriptor function five times. Native reports it as
> `Different declarations with the same signatures`; the JVM backend materialises one and
> the IR pass then fails to match it, producing `Function has no body`. The session at the
> root of the `dependsOn` chain owns the declaration — exactly one per compilation, and its
> source set compiles into every target.

## Consequences

Whichever module calls `DebugInputsPage()` determines what the page can see; calling
it from a module that does not depend on `:domain` silently yields a page without
`:domain`'s inputs. This is documented, not solved.

The generated descriptor functions must be `public` to be callable across module
boundaries, which would put them in every consumer's autocomplete and in the ABI of
any module they publish. They are emitted into a fixed
`com.rohittp.debuginput.generated` package and annotated
`@Deprecated(level = DeprecationLevel.HIDDEN)`, which removes them from resolution
and completion while leaving them in the binary.

> **Amended during M1 implementation.** Hidden-across-modules is confirmed — a dependent
> module cannot name the function in source (`Unresolved reference`), while the plugin
> resolves it and the generated `invokestatic` links. Both halves are pinned by tests.
>
> But the reason given above was wrong, and it mattered. The claim was that IR alone
> suffices because "IR-generated calls are constructed directly rather than resolved."
> That is true of the **caller** and false of the **callee**: a function that exists only
> in IR gets no Kotlin metadata, so nothing downstream can resolve it — verified against
> 2.3.21, where the facade came out `@Metadata(k = 3)` with no payload, and
> `registerFunctionAsMetadataVisible` had no effect because a synthetic `IrFileImpl` has
> no `FirFile` to serialize into. Resolution failed with the HIDDEN annotation removed
> too, so HIDDEN was never the obstacle.
>
> The working mechanism is **declared in the frontend, bodied in IR**:
> `FirDeclarationGenerationExtension` declares the function (carrying the `@Deprecated`
> annotation), and the IR extension only fills in its body. The facade then carries real
> metadata and the method is `ACC_SYNTHETIC` with `Deprecated: true`.
>
> Two costs come with that route. It needs the
> `ExperimentalTopLevelDeclarationsGenerationApi` opt-in. And per **KT-66735, top-level
> generated declarations are not covered by Kotlin's incremental compilation** — so editing
> a module's inputs may require a non-incremental recompile of anything that aggregates it.
> That is a Gradle-plugin concern, not a compiler one.
>
> Separately, reading a default from the property's own backing field crashes the JVM
> backend (`SyntheticAccessorLowering should not attempt to modify other files!`), because
> a Kotlin backing field is private to its own class regardless of the property's
> visibility — public top-level `val`s included. The transform therefore widens the
> backing field's JVM visibility on inputs whose default a descriptor reads. Metadata is
> serialized from FIR, so consumers still see the property exactly as written, and the
> widening only happens where the transform runs. On iOS, where the transform is
> unconditional, the widened field does land in the klib's serialized IR — a possible
> klib-ABI-check interaction, still unverified.

Three rewrites also means three chances to collide with the Compose compiler plugin
running over the same files. Plugin ordering is an M1 verification item too.
