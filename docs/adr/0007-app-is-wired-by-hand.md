# `:app` wires the compiler plugin by hand

`app/build.gradle.kts` adds `project(":debug-input-compiler")` to
`kotlinCompilerPluginClasspath` and passes plugin options through
`freeCompilerArgs` directly, rather than applying `com.rohittp.debug-input`. This
looks like something a future reader should tidy up. It is not.

Gradle cannot apply a plugin from a sibling subproject in the same build: the plugin
class would need to be on `:app`'s buildscript classpath, which only `buildSrc` or an
included build can provide. `:app` deliberately stays in the root build so the
dogfood loop is a single Gradle invocation with no publishing step and no stale
artifacts, so hand-wiring is the only available option.

## Consequences

The Gradle plugin's own logic — computing plugin options, per-variant debug
detection, collecting descriptor manifests from dependencies — is never exercised by
`:app`. Two independent things now compute the same option strings and can drift.
That is covered from two directions:

- **Gradle TestKit** fixture projects under `debug-input/src/test/resources`, which
  assert the exact option strings the plugin produces.
- **`consumer-smoke`**, a standalone build that writes the real
  `plugins { id("com.rohittp.debug-input") version "…" }` line and is run twice per
  release — once against `build/local-maven`, once against the public R2 URL with a
  fresh Gradle user home.

`consumer-smoke` is the only place the shipped consumer path runs end to end, which
makes it a release gate rather than an optional extra.
