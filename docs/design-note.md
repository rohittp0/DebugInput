# debug-input — design note

Originally written as a handoff from the `plugables` monorepo, 2026-08-14. The
original text is preserved below. A grilling session on the same day amended it;
those amendments are listed first and take precedence.

---

## Amendments from the grilling session

All five open questions in §9 are closed. Decisions live in `docs/adr/`, language in
`CONTEXT.md`.

| Original | Amended | Why |
|---|---|---|
| §4 four library modules, no consumer | `:app` and `:domain` join the root build as dogfood modules and hand-wire the compiler plugin | Gradle cannot apply a plugin from a sibling subproject; see [ADR-0007](adr/0007-app-is-wired-by-hand.md) |
| §3 "the transform is unconditional" | Unconditional on iOS only; Android release compilations skip it entirely | [ADR-0002](adr/0002-android-release-skips-the-transform.md) |
| §3 "hydrated eagerly at startup" | Hydrated lazily on first read, one code path per platform | iOS has no process-start hook for a KMP library; the `ContentProvider` captures `Context`, it does not hydrate |
| §5 `private val speed$default` | No synthetic property — the getter reads the property's own existing backing field | The field already holds the initializer's result; synthesising a second one is redundant |
| §5 `defaultProvider = { … }` | `default: Any?`, read eagerly inside the descriptor function | A `val`'s default cannot change, so a lambda buys nothing and costs IR complexity |
| §5 ids are the FQN | FQN, plus the file name for **private top-level** properties only | Two `private val speed` in one package compile cleanly and would share an id — verified against 2.3.21; [ADR-0005](adr/0005-id-derivation-and-dormant-overrides.md) |
| §5 `rememberDebugInput(::speed)` | `rememberDebugInput { speed }` | The lambda *is* the Android-release lowering; [ADR-0004](adr/0004-live-reads-take-a-lambda.md) |
| §5 (unspecified) how the page gets descriptors | The IR plugin rewrites the `DebugInputsPage()` call site | [ADR-0006](adr/0006-linkage-by-call-site-rewriting.md) |
| §5 (unspecified) descriptor function visibility | Fixed `com.rohittp.debuginput.generated` package, `@Deprecated(level = HIDDEN)` | It must be public to cross module boundaries, but should not pollute consumers' autocomplete or ABI |
| §2 supported types: "scalars + enums + a custom-renderer registry" | Widened: every primitive, `String`, enums, plus `List`/`Set`/`Array`/the eight primitive arrays/`Pair`/`Triple` of scalars — one nesting level, no `Map`, no enums inside containers | Requested after M1; see the [M2 plan](superpowers/plans/2026-08-14-debug-input-m2-types.md), [ADR-0008](adr/0008-length-prefixed-self-describing-encoding.md) and [ADR-0009](adr/0009-array-inputs-return-a-cached-instance.md). The runtime stays dependency-free: the codec is hand-rolled, not `kotlinx-serialization-json` |
| §9.1 Kotlin version policy | One Kotlin minor per release (1.0.0 → 2.3.x) with a build-failing version guard | [ADR-0001](adr/0001-pin-one-kotlin-minor-per-release.md) |
| §9.2 section naming | Two levels: Gradle project path, then declaring class/object/enum/file | Flat lists do not survive a multi-module app |
| §9.3 id stability | Orphaned overrides stay dormant forever and are ignored; one **Reset all** button, no per-orphan UI | Pruning against an incomplete descriptor list would delete live overrides |
| §7 publishing | R2 only; consumers declare the repository in `pluginManagement` **and** `dependencyResolutionManagement` | [ADR-0003](adr/0003-publish-to-r2-only.md) |
| (absent) testing strategy | Behaviour-primary compile tests, a small golden IR set, TestKit fixtures, and `consumer-smoke` as a release gate | The largest gap in the original note |

Two further corrections found while planning:

- The annotation must have **`BINARY`** retention, not `SOURCE`. A cross-module
  `rememberDebugInput { speed }` needs FIR in the *consuming* module to see that the
  property is a debug input, and a source-retained annotation is absent from the
  klib and class metadata.
- The page needs no explicit release guard on Android. With the transform skipped,
  the `DebugInputsPage()` call site is never rewritten, so it receives its default
  empty descriptor list and renders nothing.

---

## Original design note (2026-08-14)

**Status:** design agreed, nothing implemented. No code was written in `plugables`.
**Destination:** a new repository, plugin id `com.rohittp.debug-input`.

### 1. What it does

An annotation that makes a value tweakable at runtime in debug builds, and
inert in release builds.

```kotlin
@DebugInput(docs = "Player speed in m/s")
val speed = 10
```

- **Release:** `print(speed)` always prints `10`.
- **Debug:** `speed` resolves through a registry. If someone changed it on the
  debug page, that value is returned and persists across launches.

Annotating an **enum class** makes each constant's constructor `val`s
individually tweakable, grouped into one page section titled with the enum name:

```kotlin
@DebugInput enum class Tier(val limit: Int, val label: String) {
    FREE(10, "Free"),
    PRO(1000, "Pro"),
}
// page section "Tier" -> FREE.limit, FREE.label, PRO.limit, PRO.label
```

An enum-*typed* property renders as a dropdown of constants under the ordinary
property rule, independently of whether that enum is itself annotated.

A single Compose Multiplatform entry point renders everything:

```kotlin
@Composable fun DebugInputsPage()
```

It lists every input in the project with its current value, a **changed
indicator** when it differs from the default, a **reset** affordance, and an
**info icon** showing the `docs` string. In release it renders nothing.

Targets: **Android and iOS**.

### 2. Decisions, and what they beat

| Decision | Chosen | Rejected, and why |
|---|---|---|
| Interception mechanism | **Kotlin compiler plugin (FIR + IR)** | KSP *cannot* rewrite existing declarations, only emit new files. ASM (as in plugables' `auto-assert`) has no reach on Kotlin/Native. Property delegates would change the syntax and force every debuggable enum to be rewritten by hand. Pre-compile source rewriting desynchronises the IDE and debugger from what actually compiles. |
| UI surface | **Compose Multiplatform**, one page for both platforms | A separate SwiftUI page means writing and maintaining the renderer set twice. Android-only v1 means the feature does not exist on iOS. |
| Runtime packaging | **Two published KMP artifacts** — `-runtime` (no Compose) and `-compose` (page + renderers) | A single artifact drags Compose Multiplatform into pure-logic modules, which get instrumented too. |
| Enablement | **Automatic only, no override** | An opt-in override for internal/TestFlight builds was offered and declined. The release path is provably inert; there is deliberately no escape hatch. |
| Persistence | **Hand-rolled `expect`/`actual`** — SharedPreferences on Android, NSUserDefaults on iOS | `multiplatform-settings` puts a third-party coordinate in a published artifact that can collide with the consumer's own. DataStore reads are suspend-only, so hydration races the synchronous getter. |
| Supported types | **Scalars + enums + a custom-renderer registry** | Scalars-only under-delivers. `@Serializable`-object support would add `kotlinx-serialization-json` to a runtime chosen to be dependency-free, and editing nested JSON on a phone is poor. |
| Liveness | **Live inside the Compose artifact only** | Making the core depend on `androidx.compose.runtime` so every read recomposes was offered and declined. Accepted cost: a second read path. |
| Cross-module aggregation | **Gradle-side manifest collection** | Compile-time classpath scanning fails silently for dependencies built without the plugin. Manual per-module init calls rot the moment someone adds a module. |
| Repository | **Standalone**, not `plugables` | See §7. |

### 3. Hard constraints discovered

- **Kotlin/Native compiles once for debug and release.** `compileKotlinIosArm64`
  produces one klib; both `linkDebugFrameworkIosArm64` and
  `linkReleaseFrameworkIosArm64` consume it. There is no compile-time build-type
  switch on Apple targets.
- **`kotlin.native.Platform.isDebugBinary`** is resolved at link time — exactly
  where debug and release diverge. This is the iOS half of build-type detection.
- **Android `androidTarget()` compiles per variant**, so a generated per-variant
  constant supplies the Android half.
- **The rewritten getter is synchronous.** Any override store must be readable
  synchronously.
- **Kotlin/Native has no `ServiceLoader` and no classpath scanning**, and
  dead-code elimination strips registrations nothing references.
- **Enum entries with a body are anonymous subclasses**, so `thisRef::class`
  does not give the enum class.
- **`implementation` dependencies are not on the consumer's compile classpath.**

### 4. Artifacts

Group `com.rohittp`, Kotlin package `com.rohittp.debuginput`.

| Artifact | Build | Role |
|---|---|---|
| `debug-input` | `kotlin-dsl`, JVM 21 | The Gradle plugin. Id `com.rohittp.debug-input`. |
| `debug-input-compiler` | `kotlin("jvm")`, links `kotlin-compiler-embeddable` | FIR diagnostics + IR transform. |
| `debug-input-runtime` | KMP: `androidTarget()`, `iosX64`, `iosArm64`, `iosSimulatorArm64`. **No dependencies.** | Annotation, registry, `OverrideStore`, descriptor types. |
| `debug-input-compose` | KMP + Compose Multiplatform | `DebugInputsPage()`, built-in renderers, `rememberDebugInput`, custom-renderer registration. |

`debug-input-runtime` needs an `AndroidManifest.xml` declaring a `ContentProvider`
that captures `Context` at process start.

### 5. Architecture

Resolution is **reflection-free** — the IR plugin picks the call by static type:

- `resolveInt` / `resolveLong` / `resolveFloat` / `resolveDouble` / `resolveBoolean` / `resolveString`
- `resolveEnum(id, default, entries)`
- `resolveCustom(id, default, typeKey)`

Ids are fully qualified and baked in as literals. For enum-class inputs the id
carries the constant name: `com.app.Tier.FREE.limit`.

Because `implementation` dependencies are not visible transitively, aggregation
is **hierarchical**: each module's function returns its own descriptors *plus*
its direct dependencies' — which it can see. **Dedupe by id at runtime.**

**v1 scope:** only project dependencies are aggregated. Published libraries
carrying `@DebugInput` are out of scope.

Custom renderers register at **runtime**, so FIR cannot reject unknown types at
compile time. FIR rejects only structurally impossible types, and an unregistered
type shows a row reading "no renderer registered for X" while its getter returns
the default.

### 6. FIR diagnostics

Reject, naming the offending property:

- `const val` — inlined at every use site; there is no getter to intercept
- `var` — the setter would write a backing field the getter ignores
- custom getter or delegated property — no initializer to take a default from
- `@DebugInput` on an enum class with no supported constructor `val`s

### 7. Why standalone

One root `version` drives all modules and CI publishes them together.

**Lifted from `plugables`/`rentile`:** the R2 publishing block
(`org.gradle.s3.endpoint`, `s3://` URL, `AwsCredentials` from env,
`notCompatibleWithConfigurationCache` on `PublishToMavenRepository`), the
`com.vanniktech.maven.publish` setup with signing gated on
`ORG_GRADLE_PROJECT_signingInMemoryKey`, and `scripts/set-r2-github-secrets.sh`.

### 8. Deliberate v1 non-goals

- No `min`/`max` on the annotation — numbers render as validated text fields.
- No aggregation of `@DebugInput`s from published library dependencies.
- No enablement override — release builds cannot turn the page on.
- No `@Serializable` object editing.
- Android and iOS only; no JVM desktop, JS or Wasm targets.

### 9. Open questions

All closed — see the amendment table above.
