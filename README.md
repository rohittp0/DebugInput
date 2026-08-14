# DebugInput

DebugInput turns annotated Kotlin values and enum constants into persistent, type-safe controls for Android and iOS debug builds.

```kotlin
/** Multiplier applied to preview playback. */
@DebugInput
val playbackSpeed: Double = 1.0
```

The generated Compose page groups inputs into source-derived subpages, uses KDoc as help text, persists overrides across relaunches, and copies changed defaults as JSON for tester-to-developer handoff.

See the full installation guide, supported types, enum behavior, JSON schema, production guarantees, and troubleshooting at [rohittp.com/DebugInput](https://rohittp.com/DebugInput/).

DebugInput currently requires Kotlin 2.3.21. Published artifacts are available from `https://maven.rohittp.com`.
