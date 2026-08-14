# debug-input M1 Implementation Plan

**Goal:** An `@DebugInput`-annotated `Int` `val` is editable at runtime from
`DebugInputsPage()`, persists across launches, works on Android and iOS, aggregates
across two modules, and is provably absent from Android release builds.

**Architecture:** A Kotlin FIR+IR compiler plugin rewrites annotated property getters
to resolve through `DebugInputRegistry`, emits one hidden descriptor function per
module, and rewrites the `DebugInputsPage()` call site to pass the enclosing module's
descriptors. Android release compilations skip the transform entirely; iOS is
unconditional and inert at runtime via `Platform.isDebugBinary`.

**Tech Stack:** Kotlin 2.3.21 (pinned, see ADR-0001), AGP 9.3.1, Gradle 9.5,
Compose Multiplatform 1.12.0-rc01, vanniktech maven-publish 0.36.0.

**Spec:** [`docs/design-note.md`](../../design-note.md) — read the amendment table
first; it supersedes parts of the original text. Language: [`CONTEXT.md`](../../../CONTEXT.md).
Decisions: [`docs/adr/0001`–`0007`](../../adr/).

## Global Constraints

- Kotlin **2.3.21** exactly. `debug-input-compiler` takes `kotlin-compiler-embeddable`
  as `compileOnly`.
- `debug-input-runtime` has **zero dependencies**. No coroutines, no atomicfu, no
  serialization. Thread safety uses `kotlin.concurrent.Volatile` + copy-on-write only.
- Group `com.rohittp`. Kotlin package `com.rohittp.debuginput`. Plugin id
  `com.rohittp.debug-input`.
- Targets: `androidTarget()`, `iosArm64`, `iosSimulatorArm64`, `iosX64`. No JVM
  desktop, JS or Wasm.
- `explicitApi()` on `debug-input-runtime` and `debug-input-compose`.
- M1 supports **`Int` only**. Any other type on `@DebugInput` is a FIR error naming
  the unsupported type.
- Dogfood modules `:app` and `:domain` are never published.

---

## Shared contract

Every module below is built against these exact signatures. Do not rename anything
here without updating this section first.

### `debug-input-runtime` — package `com.rohittp.debuginput`

```kotlin
@Retention(AnnotationRetention.BINARY)                    // NOT SOURCE — see design note
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class DebugInput(public val docs: String = "")

@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Internal to debug-input.")
@Retention(AnnotationRetention.BINARY)
public annotation class DebugInputInternalApi

public class DebugInputDescriptor(
    public val id: String,
    public val displayName: String,
    public val module: String,
    public val section: String,
    public val typeKey: String,
    public val docs: String,
    public val default: Any?,
    public val enumConstants: List<String>? = null,
)

public object DebugInputRegistry {
    // Called by IR-generated getter bodies. The only hot path.
    public fun resolveInt(id: String, default: Int): Int

    @DebugInputInternalApi public val isDebugBuild: Boolean
    @DebugInputInternalApi public fun overrideOf(id: String): Any?
    @DebugInputInternalApi public fun setInt(id: String, value: Int)
    @DebugInputInternalApi public fun clearOverride(id: String)
    @DebugInputInternalApi public fun clearAll()
    @DebugInputInternalApi public fun addListener(id: String, listener: (Any?) -> Unit): () -> Unit
}
```

Storage: dedicated namespace per platform — Android SharedPreferences file
`debug_input_overrides`, iOS `NSUserDefaults(suiteName = "com.rohittp.debuginput.overrides")`.
Encoding is a type-tagged string, `"i:25"` for `Int`. A value whose tag does not
match the requested type is ignored and the default returned.

`isDebugBuild`: `true` on Android (the code only exists in debug — ADR-0002),
`Platform.isDebugBinary` on iOS.

### `debug-input-compose` — package `com.rohittp.debuginput.compose`

```kotlin
@Composable
public fun DebugInputsPage(
    descriptors: List<DebugInputDescriptor> = emptyList(),
    modifier: Modifier = Modifier,
)
```

The default empty list is what makes Android release render nothing: no transform
runs, so the call site is never rewritten.

### `debug-input-compiler`

Compiler plugin id `com.rohittp.debug-input`. CLI options:

| Option | Type | Meaning |
|---|---|---|
| `enabled` | bool, default `true` | `false` on Android release compilations — skip everything except FIR diagnostics |
| `module` | string | Gradle project path, e.g. `:domain`. Defaults to the compiler's module name |
| `manifestOut` | path | Where to write this module's descriptor manifest |
| `dependencyDescriptors` | repeatable string | FQN of each direct dependency's descriptor function |

Generated per module: `com.rohittp.debuginput.generated.descriptors_<sanitized>`,
annotated `@Deprecated(level = DeprecationLevel.HIDDEN)`, returning
`List<DebugInputDescriptor>` — this module's descriptors plus a call to each
dependency's function. Sanitisation: `:sample:domain` → `sample_domain`, `:app` →
`app`, root → `root`.

Descriptor manifest JSON:

```json
{
  "module": ":domain",
  "function": "com.rohittp.debuginput.generated.descriptors_domain",
  "inputs": [{ "id": "com.app.physics.speed", "typeKey": "kotlin.Int" }]
}
```

Id derivation (ADR-0005): FQN; private top-level properties additionally carry the
file name — `com.app.physics.Physics.kt.speed`. `typeKey` is `kotlin.Int`.
`section` is the enclosing class/object/enum simple name, else the file base name
without `.kt`. `displayName` is the property name.

### `debug-input` — Gradle plugin

`com.rohittp.debuginput.gradle.DebugInputGradlePlugin`, a
`KotlinCompilerPluginSupportPlugin`. Fails the build when `getKotlinPluginVersion()` is
not `2.3.x` (ADR-0001).

### Facts discovered during M1 that amend the above

- **Kotlin version:** read it with `getKotlinPluginVersion()` from
  `org.jetbrains.kotlin:kotlin-gradle-plugin`. `KotlinCompilerVersion.VERSION` lives in
  `kotlin-compiler-embeddable` and is not reachable from a Gradle plugin.
- **`getPluginArtifactForNative()` is `DeprecationLevel.ERROR`** in 2.3.21 and goes away
  in 2.4. Only `getPluginArtifact()` is used; do not override the native one.
- **Compose Multiplatform has no `iosX64` variant** at 1.12.0-rc01, so no module with
  Compose on it can declare that target. `debug-input-runtime` keeps `iosX64`;
  `debug-input-compose` and `:shared` cannot.
- **`org.jetbrains.compose.material3:material3` is on its own version line** — no
  `1.12.0-rc01` exists. Pinned separately as `composeMaterial3 = "1.9.0"`.
- **`debug-input-compose` has no `withHostTest {}`.** `androidx.compose.ui.test` will not
  run on a plain JVM (it reaches for Robolectric and dies on a null `Build.FINGERPRINT`),
  so that module's suite runs on `iosSimulatorArm64Test`.
- **Android inertness is a runtime property**, not a compile-time one, for KMP modules —
  see the amendment in ADR-0002. The `enabled` option survives for non-KMP consumer
  modules only.

---

## Tasks

Task 1 is sequential and blocks everything. Tasks 2–5 are independent and run in
parallel against the contract above. Tasks 6–8 integrate.

### Task 1 — Repo skeleton
Clear the Android Studio scaffold. Root `build.gradle.kts` with `group`/`version`
from `VERSION_NAME` and the R2 publishing block lifted from rentile. Version catalog
pinned. Six modules in `settings.gradle.kts`, four with empty source sets that
compile. Gate: `./gradlew build` green on an empty project.

### Task 2 — `debug-input-runtime`
Annotation, `DebugInputDescriptor`, `DebugInputRegistry`, the `expect`/`actual`
override store, the `ContentProvider` that captures `Context`, and
`AndroidManifest.xml`. Tests in `commonTest`, `androidHostTest`,
`iosSimulatorArm64Test`: override wins over default, wrong type tag is ignored,
value survives a simulated relaunch, `clearAll` restores defaults, listeners fire.

### Task 3 — `debug-input-compiler`
`CompilerPluginRegistrar` + `CommandLineProcessor`, FIR checkers for every rejection
in design-note §6 plus local `val`s, `expect`/`actual` properties and unsupported
types, and the IR getter rewrite. The getter reads the property's **own existing
backing field** — no synthetic `$default` property. Test harness drives
`K2JVMCompiler` in-process from the embeddable jar. Behaviour-primary tests plus
golden IR dumps for `scalarInt`, `privateTopLevel`, `androidRelease`.

### Task 4 — `debug-input-compose`
`DebugInputsPage`, two-level grouping (module → section, module level collapsed away
when there is only one), the `Int` renderer as a validated text field, changed
indicator, per-row reset, **Reset all**, and the docs info icon. Tests via
`runComposeUiTest` in `commonTest`.

### Task 5 — `debug-input` Gradle plugin
Per-variant `enabled` computation, `module` from `project.path`, manifest collection
through a dedicated resolvable configuration, and the Kotlin version guard. TestKit
fixtures asserting the exact option strings.

### Task 6 — Descriptor function + manifest emission
The IR half that Task 3 leaves out: emit the hidden descriptor function, call
dependency functions, write the manifest. Verify `@Deprecated(HIDDEN)` is callable
across a module boundary from IR-generated code.

### Task 7 — `:domain` + `:app` dogfood
`:app` becomes KMP (`com.android.application` + `androidTarget()` + three iOS
targets), hand-wiring the compiler plugin per ADR-0007. `:domain` declares the
inputs. End-to-end tests: `androidHostTest` and `iosSimulatorArm64Test` assert an
override resolves through a real `:domain` property; `runComposeUiTest` renders the
page on the iOS simulator.

### Task 8 — Release inertness gate + CI
`checkReleaseInertness` scans `:app`'s release class bytes and fails if
`DebugInputRegistry` or any input id appears. `ci.yml` mirroring rentile's shape.

## M2–M6

Deferred deliberately: their detail depends on what M1's seams turn up, and inventing
step-by-step code for an IR harness that does not exist yet would be fiction.

| | Deliverable |
|---|---|
| M2 | Remaining scalars (`Long`, `Float`, `Double`, `Boolean`, `String`) + validated renderers |
| M3 | Enum-typed dropdowns and `@DebugInput` enum classes |
| M4 | `rememberDebugInput { … }` lambda rewrite and liveness |
| M5 | Custom renderer registry, `resolveCustom`, "no renderer registered" row |
| M6 | `publish.yml`, `consumer-smoke`, README, `scripts/set-r2-github-secrets.sh` |
