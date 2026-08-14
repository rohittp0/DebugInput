# Array inputs return one cached instance per id

`IntArray`, `DoubleArray`, the other primitive arrays and `Array<T>` are mutable, so
what a getter hands back is observable in ways a scalar's value is not. The registry
therefore caches one decoded instance per id and returns that same instance on every
read, rebuilding it only when the override changes.

```kotlin
@DebugInput val weights = doubleArrayOf(1.0, 2.0)

weights === weights   // true
weights[0] = 5.0      // sticks, exactly as it does without the plugin
weights.sort()        // works
```

## Considered Options

- **Decode a fresh copy per read** — rejected. It compiles, runs, and silently discards
  `weights[0] = 5.0`, and flips identity comparisons. A debug tool that quietly changes
  the meaning of working code when switched on is worse than one that refuses the type.
- **Reject mutable arrays in FIR**, supporting only `List`/`Set` — rejected: `DoubleArray`
  is common in exactly the numeric-tuning code this tool is most useful for.

## Consequences

With no override stored, reads return the property's own backing-field array, so
identity and mutation are bit-for-bit what they are today. With an override stored,
reads return the cached decoded array instead — mutating it is visible to subsequent
reads in that process but does not write back to the override store, which is the same
relationship a plain `val` array has with its initializer.

The cache is invalidated whenever the override map changes, so a page edit or
`clearAll()` swaps the instance and a subsequent read sees the new one. Anything holding
the previous instance keeps it; that is unavoidable for a mutable value and matches how
a `val` array behaves across a configuration change today.

The registry retains one reference per array input for the process's lifetime. Bounded
by the number of array inputs, so not a leak worth managing.
