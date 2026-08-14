# Pin one Kotlin minor per release

`debug-input-compiler` links `kotlin-compiler-embeddable`, which is not a stable
API, so the compiler plugin is only safe against the Kotlin minor it was compiled
against. We pin each debug-input release to a single Kotlin minor (1.0.0 → Kotlin
2.3.x), document it, and have the Gradle plugin fail the build with a
plain-English message when the consumer's Kotlin version does not match.

The version is read with `getKotlinPluginVersion()`, not `KotlinCompilerVersion.VERSION`
as first sketched: the latter lives in `kotlin-compiler-embeddable`, which a Gradle
plugin has no business carrying on its classpath. `getKotlinPluginVersion()` reads the
version from the Kotlin Gradle plugin's own resources, so the guard also works when our
plugin is applied before the Kotlin plugin.

## Considered Options

- **KSP-style version matrix** (`2.3.21-1.0.0`, one artifact per Kotlin version) —
  rejected: CI fans out across every supported Kotlin × four modules, and every
  Kotlin patch becomes a publish event. Too much ongoing cost for a solo-maintained
  plugin.
- **Declared range tested at the edges** (2.2.x–2.3.x from one artifact) — rejected:
  it restricts us to the FIR/IR surface common to the whole range, and a broken
  range is discovered only via a bug report.

## Consequences

`kotlin-compiler-embeddable` is a `compileOnly` dependency, so a version mismatch
does not fail dependency resolution — it surfaces as `NoSuchMethodError` or
`AbstractMethodError` from inside FIR internals. The version guard is therefore
load-bearing, not a nicety: it converts the project's worst diagnostic into a
sentence. AGP 9's built-in Kotlin support means an Android consumer may never
declare a Kotlin version at all, so the guard must read the compiler's own version
rather than any user-declared one.
