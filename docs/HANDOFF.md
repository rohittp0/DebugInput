# Handoff — debug-input, 2026-08-14

Written at the end of a long session. M1 and M2 are complete and committed; M3 was in
flight when this was written. Read this, then `CONTEXT.md`, then `docs/design-note.md`
(amendment table first), then the ADRs.

## Where things stand

Branch **`debug-input-m1-m2`**, two commits, not merged into `main`.

```
51a2b8c  Publish the four artifacts to Maven repositories
f2a15d2  Implement debug-input M1 and M2: transform, page, and the full type set
1ad0e40  Initial commit (the Android Studio scaffold, since deleted)
```

To land it: `git checkout main && git merge --ff-only debug-input-m1-m2`.

A clean full build was green at `51a2b8c`: **509 test executions, 0 failures**, plus debug
and release APKs and both iOS framework link paths.

```
./gradlew --no-configuration-cache clean \
  :debug-input-compiler:test :debug-input:test \
  :debug-input-runtime:testAndroidHostTest :debug-input-runtime:iosSimulatorArm64Test \
  :debug-input-compose:iosSimulatorArm64Test \
  :domain:testAndroidHostTest :domain:iosSimulatorArm64Test \
  :shared:iosSimulatorArm64Test \
  :app:assembleDebug :app:assembleRelease \
  :shared:linkDebugFrameworkIosArm64 :shared:linkReleaseFrameworkIosArm64
```

Published to `~/.m2` at `0.1.0-SNAPSHOT`: `debug-input` (+ plugin marker),
`debug-input-compiler`, `debug-input-runtime` (android, iosarm64, iossimulatorarm64,
iosx64), `debug-input-compose` (android, iosarm64, iossimulatorarm64 — CMP publishes no
iosx64).

**Check for uncommitted work before anything else.** A background agent was implementing
M3 inside `debug-input-compiler/` when this was written. Run `git status` and
`:debug-input-compiler:test`; its work may be complete, partial, or absent.

## What is done

| | |
|---|---|
| M1 | Transform, registry, stores, page, Gradle plugin, cross-module descriptor aggregation, both platforms |
| M2 | All 23 types: 9 scalars, enums, `List`/`Set`/`Array`, 8 primitive arrays, `Pair`, `Triple` |

`:domain` holds one input of every supported type; `TypeSweep.kt` in `domain/src/commonTest`
asserts each resolves its default, takes an override, survives a relaunch and clears —
through real SharedPreferences and real `NSUserDefaults`. The case list is in `commonTest`
deliberately, so the two platform suites cannot drift.

## What is next, in order

### 1. M3 — `@DebugInput` on an enum class (in flight)

The brief given to the agent, in case it needs restating:

- Each constant's constructor `val` becomes an input. Id
  `<enum FQN>.<CONSTANT>.<property>`; section = the enum's simple name; displayName
  `CONSTANT.property`, except `CONSTANT` alone when the enum has exactly one supported
  constructor `val`.
- **One `values` property is shared by every constant**, so the getter cannot be rewritten
  per constant. Bake a private id table into the enum's file ordered by `ordinal` and index
  it — `resolveComposite(IDS[ordinal], field, "darr")`. Do **not** build ids by string
  concatenation: these constants are read inside frame loops.
- Descriptor defaults read the **field** on the constant instance
  (`IrGetField(IrGetEnumValue(CONSTANT), field)`), not the property, because the getter now
  returns the override. Enum constants are statically reachable singletons, which also
  side-steps the open "instance property has no readable default" gap.
- Diagnostics to add: annotated enum class with no supported constructor `val`; `@DebugInput`
  on a **non-enum** class (currently a silent no-op, which is worse than an error); an
  unsupported constructor `val` type (reject only that one, instrument the rest); a
  constructor `var`.

### 2. Answer the KDoc question — the user asked it directly

**Question:** can an enum constant's documentation come from its KDoc instead of an
annotation argument? The user's `MagicNumbers` already carries excellent KDoc — some entries
run twenty lines with measurements and cross-platform history — so re-typing it into
annotation arguments would duplicate it and let the copies drift.

**The caveat that decides it:** the K2 CLI parses with LightTree, the IDE with PSI. If KDoc
is reachable from one and not the other you get docs in the IDE and empty docs in the real
build. A spike was requested to determine reachability under **both**. If KDoc is reliably
reachable, precedence is: explicit annotation argument on the entry → KDoc → empty, with the
KDoc normalised for a phone popup (drop `@param`/`@see` block tags, flatten `[a.b.C]` links
to their last segment, collapse the `*` margin into paragraphs, keep the whole body). If it
is **not** reliably reachable, stop and put the choice back to the user — a per-entry
annotation is the fallback, and it should be their decision rather than an implicit one.

Also unverified: which `AnnotationTarget` an enum entry needs. Kotlin appears to have no
`ENUM_ENTRY` target; confirm whether `FIELD` works and add it to `@DebugInput` if so.

### 3. Wire travel-animator-android

Repo: `/Users/rohittp/Data/Lascade/travel-animator-android`. **Kotlin 2.3.21**, matching
ADR-0001's pin, so the version guard passes.

Its `settings.gradle.kts` already trusts `maven("https://maven.rohittp.com")` in **both**
`pluginManagement` and `dependencyResolutionManagement` — the two declarations ADR-0003 says
a consumer needs. So once artifacts reach R2 it needs no settings change at all.

**The user chose `mavenLocal()` temporarily** for the inner loop: add it to both repository
blocks, and take it out before they commit — a stray `mavenLocal()` makes the build
unreproducible and can pass locally while failing CI.

Then:
- apply `id("com.rohittp.debug-input")` to `:shared`
- annotate `enum class MagicNumbers` in
  `shared/src/commonMain/kotlin/com/lascade/ta/shared/constants/MagicNumbers.kt` —
  `vararg val values: Double` is a `DoubleArray`, so every constant becomes a `darr` input
  and multi-rung entries like `ROUTE_LINE_WIDTHS(2.0, 4.0, 6.0)` and
  `FLAG_SCALES(0.6, 0.8, 1.0)` become editable ladders
- host `DebugInputsPage()` in
  `app/src/main/java/com/travelanimator/routemap/ui/settings/debug/DebugOptionsScreen.kt`

**Watch for a Compose version clash.** `debug-input-compose` is built against Compose
Multiplatform 1.12.0-rc01 (and `material3` 1.9.0, which is on its own version line). Their
`:app` uses AndroidX Compose directly. CMP's android variants map onto AndroidX artifacts, so
this should work, but a version conflict is the likeliest first failure.

### 4. Replacing their existing mechanism

The user said: *"Once everything passes I will remove the existing debug inputs in Travel
animator."* Do not remove it for them unless asked. What it consists of:

- `app/src/main/java/com/travelanimator/routemap/ui/settings/debug/InputRegistry.kt` — an
  `enum class InputRegistry(val debugInput: DebugInput<*>)` with 8 entries, each a
  `DebugBoolean`/`DebugDouble` carrying a label and a default, plus `inline fun <reified T>
  get()` and a `@Composable fun UI()`
- rendered by `InputRegistry.entries.forEach { it.UI() }` in `DebugOptionsScreen.kt` (~line 80)
- `app/src/test/java/.../debug/InputRegistryTest.kt`
- **`shared/src/commonMain/kotlin/com/lascade/ta/shared/builder/running/constants.kt`
  references `InputRegistry`** — so removal is not confined to `:app`. Check this before
  suggesting a deletion.

Note their existing design and ours differ in an interesting way: their `DebugInput<*>`
carries its own display label (`"Ripple 1 Max Scale"`), where ours derives the label from the
property name and takes prose from `docs`. Migrating means those labels become either
property names or docs text.

### 5. Remaining milestones

- **M4** — `rememberDebugInput { … }` liveness (ADR-0004). The lambda form is chosen; the
  Android-release lowering is `remember { speed }`, i.e. keep the lambda.
- **M5** — custom renderer registry, `resolveCustom`, and the "no renderer registered for X"
  row (which already exists as the seam).
- **M6** — real publishing: vanniktech + signing + POM metadata, the R2 workflow modelled on
  `/Users/rohittp/Data/Other/rentile/.github/workflows/publish.yml`, and **`consumer-smoke`**
  as a release gate.

## Known gaps, stated plainly

- **The Gradle plugin's descriptor-sharing configuration has never been resolved by a real
  multi-module build.** `:domain` and `:shared` hand-wire `dependencyDescriptors` per
  ADR-0007. The producer/consumer configurations are implemented and unit-tested only.
- **The shipped `plugins { id(...) }` path was verified once, by hand**, against mavenLocal
  (a throwaway consumer resolved the marker, passed the guard, got the runtime on its source
  set unasked, and emitted a descriptor manifest). Nothing re-checks it until `consumer-smoke`
  exists.
- **KT-66735**: top-level generated declarations are not covered by incremental compilation,
  so editing a module's inputs may force a non-incremental recompile of anything aggregating
  it. Unmitigated.
- An input declared as an **instance property of an ordinary class** gets `default = null`.
  The getter rewrite still works. Wants a FIR diagnostic.
- **`debug-input-compose` has no `iosX64`** (CMP publishes no such variant) and **no host-test
  task** (`androidx.compose.ui.test` will not run on a plain JVM), so its suite runs on the
  simulator. Android never renders the page in a test.
- No thread-safety tests anywhere. The documented benign hydration race is untested.
- `Map`, containers nested more than one level, and enums inside containers are **rejected by
  design** — see the M2 plan and ADR-0008's last paragraph for the reasons.
- `runComposeUiTest` is deprecated in favour of `androidx.compose.ui.test.v2`; migrating
  changes when effects run, so it should be deliberate.

## Facts that cost real time to discover

All are recorded in the ADRs, but they are the ones worth not rediscovering:

1. **AGP 9 refuses `com.android.application` alongside Kotlin Multiplatform.** Hence
   `:domain` → `:shared` (KMP, hosts the page call site) → `:app` (plain Android, no Compose
   plugin, just hosts a `View`).
2. **`com.android.kotlin.multiplatform.library` has a single Android variant** —
   `compileAndroidMain`, `bundleAndroidMainAar`, no debug/release split. So Android *cannot*
   skip the transform in release for KMP modules, and inertness is a runtime property on both
   platforms, read from `ApplicationInfo.FLAG_DEBUGGABLE`. ADR-0002 is amended with this.
   Consequence: input ids and `docs` strings **do** reach release binaries.
3. **The JVM backend replaces a property read with direct field access based on the
   accessor's origin, not its body.** The rewritten getter must be marked
   `IrDeclarationOrigin.DEFINED` or every read inside the declaring file compiles to
   `getstatic` and bypasses the registry — invisible for a private input, since that is the
   only place it can be read.
4. **A function that exists only in IR carries no Kotlin metadata**, so nothing downstream can
   resolve it. Descriptor functions are declared in FIR and bodied in IR. ADR-0006 amended.
5. **Kotlin backing fields are private to their own file, not their module**, and Native's IR
   validator enforces it. Descriptors are built by a per-file helper emitted into the
   declaring file. The JVM backend tolerated the invalid version, so this only showed on iOS.
6. **An HMPP compilation runs the frontend once per source set** and asks every session's
   generation extensions for their declarations — five sessions for `:domain` on
   `iosSimulatorArm64`. The root session of the `dependsOn` chain must own the declaration.
7. **`verifyIr = "error"` and `verifyIrVisibility = true` are on permanently in the compiler
   test harness.** Two of the bugs above passed JVM-only tests and would have shipped broken
   on iOS. Do not turn these off.
8. `FirDeclarationChecker.check` takes its `CheckerContext` and `DiagnosticReporter` as
   **context parameters** in 2.3.21, requiring `-Xcontext-parameters`.
   `getPluginArtifactForNative()` is an ERROR-level deprecation. `getKotlinPluginVersion()`,
   not `KotlinCompilerVersion.VERSION`, is how a Gradle plugin reads the Kotlin version.
9. **Two `private val speed` in one package but different files compile cleanly**, which is
   why private top-level ids carry the file name (ADR-0005).
10. `parseTypeSpec` must reject a **container-tagged** argument, not merely one with
    arguments: primitive arrays are containers taking no arguments, so `lst<iarr>` otherwise
    parses while the value parser refuses it — an override that could be written and then
    never read.

## Working agreements from this session

- The user's `CLAUDE.md` requires `/grill-with-docs` before writing any plan, and parallel
  subagents for independent implementation work. Both were followed.
- Decisions go in `docs/adr/` as they crystallise, and **amendments are appended to the
  existing ADR rather than silently rewritten** — several ADRs here contain claims that later
  proved wrong, and the correction is more useful than a clean file.
- `CONTEXT.md` is the vocabulary. Use *scalar*, *composite*, *type spec*, *wire form*,
  *codec*, *dormant override*, *resolved value* precisely; the glossary exists because these
  were being conflated.
- Report what is verified and what is merely believed, separately. Several findings this
  session came from an agent saying "I did not prove this".
