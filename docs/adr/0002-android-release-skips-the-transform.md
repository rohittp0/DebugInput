# Android release skips the transform; iOS cannot

> **Amended during M1 implementation.** The premise below — that `androidTarget()`
> compiles per variant — is false for the module shape this project actually uses.
> `com.android.kotlin.multiplatform.library` (AGP 9) produces a **single** Android
> variant: the task names are `compileAndroidMain` and `bundleAndroidMainAar`, with no
> Debug/Release. Only non-KMP `com.android.application` and `com.android.library`
> modules have per-variant compilations.
>
> So the compile-time skip is an **optimisation available where variants exist**, not
> the primary inertness mechanism. Inertness on Android is a runtime property, exactly
> as on iOS: `platformIsDebugBuild` reads `ApplicationInfo.FLAG_DEBUGGABLE` from the
> `Context` the `ContentProvider` captured, and returns false when no `Context` is
> available yet, so an unprovable build is treated as release.
>
> Two consequences follow. Ids and `docs` strings **do** reach a release APK when the
> inputs live in a KMP module — the same disclosure iOS already carried, now on both
> platforms. And the release-inertness gate must assert *behaviour* (the registry
> returns defaults when the process is not debuggable) for KMP modules; the
> absence-of-strings check only holds for non-KMP Android modules. The `enabled`
> compiler option is retained so those modules still get compile-time inertness.
>
> The rest of this ADR stands: the transform is unconditional wherever a single
> compilation serves both build types, and FIR diagnostics run regardless.


Kotlin/Native compiles one klib that feeds both `linkDebugFramework…` and
`linkReleaseFramework…`, so on iOS the IR transform must be unconditional and
inertness is a runtime property of `DebugInputRegistry`. Android's
`androidTarget()` compiles per variant, so release compilations receive a compiler
option that skips the transform outright: no getter rewrite, no descriptor
function, no runtime call. We take that option rather than unifying on the runtime
short-circuit.

## Consequences

Inertness on Android is provable by absence — `print(speed)` compiles to the
constant and can be asserted with `javap` — and no input id or `docs` string
reaches the APK. On iOS both do reach the release framework unless link-time dead
code elimination can prove the page unreachable through `Platform.isDebugBinary`,
which is unverified.

The price is two transform shapes. Two things must hold or release-only breakage
follows: FIR diagnostics run in **both** variants, so a `const val` or `var`
misuse fails the release build too; and `rememberDebugInput` needs an explicit
release lowering, since its call sites must still compile when no transform ran.
