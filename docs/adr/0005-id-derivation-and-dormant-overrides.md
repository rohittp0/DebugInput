# Ids are fully qualified names, file-qualified only when private

An input's id is its fully qualified name — `com.app.physics.speed`,
`com.app.Config.timeout`, `com.app.Tier.FREE.limit`. Private top-level properties
additionally carry their file name: `com.app.physics.Physics.kt.speed`.

The exception exists because the Kotlin compiler does not guarantee FQN uniqueness
there. Verified against Kotlin 2.3.21: two files in the same package each declaring
`private val speed` compile cleanly, while making either one `internal` produces
`conflicting declarations`. So everything visible outside its own file is unique by
construction, and private top-level declarations are the only hole. For those the
file *is* the declaration's scope, so including it is identity rather than an
arbitrary discriminator — and the fragility is proportionate, since a private input
can only be read from that one file anyway.

## Consequences

Renaming a property, moving a class between packages, or renaming a file containing
private inputs changes the id and therefore abandons the persisted override.
Abandoned overrides are **dormant**, not deleted: they stay in the override store,
are ignored on read, and apply again if any input ever claims that id. Renaming
back restores the value. The page offers a single **Reset all** action and no
per-orphan UI.

Automatic pruning of unmatched keys was rejected: the page's descriptor list is only
as complete as hierarchical aggregation made it, so pruning against it would delete
live overrides belonging to modules the page cannot see.

Because a dormant id can later be claimed by an input of a *different* type,
persisted values carry a type tag and a value that does not decode to the requested
type is ignored rather than throwing. The override store uses a dedicated namespace
per platform — its own SharedPreferences file, its own `NSUserDefaults` suite — so
"reset all" is a single `clear()` that cannot touch the consuming app's own data.
