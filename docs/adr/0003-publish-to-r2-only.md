# Publish to R2 only, not Maven Central or the Plugin Portal

All five coordinates (the Gradle plugin, its plugin marker, and the compiler,
runtime and compose artifacts) publish to a Cloudflare R2 bucket exposed as a
static Maven repository, reusing the workflow already proven in `rentile`: one
`VERSION_NAME` in the root `gradle.properties`, every non-docs push to `main` cuts
a release, and CI resolves the next patch by probing the published
`maven-metadata.xml`. No Maven Central namespace verification, no Plugin Portal
account, no staging API.

## Consequences

Gradle keeps plugin repositories and dependency repositories in separate lists and
R2 is in neither by default, so a consumer must declare the R2 URL **twice** — once
in `pluginManagement.repositories`, once in
`dependencyResolutionManagement.repositories`. The design note's claim that
"consumers only ever write one coordinate" is therefore true of the coordinate but
not of the setup: the README's install section is a `settings.gradle.kts` snippet
plus the plugin line.

Because versions are resolved by probing R2 and are immutable once uploaded, the
version resolver probes one canonical coordinate — the plugin marker
`com.rohittp.debug-input:com.rohittp.debug-input.gradle.plugin`, since that is what
`plugins { }` actually resolves — and all five coordinates publish at that version.

Moving to Maven Central later is purely additive; R2-first does not foreclose it.
