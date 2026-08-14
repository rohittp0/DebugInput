# debug-input M2: the full type set

**Status: complete, 2026-08-14.** All five tasks landed. A clean build of every module runs
**509 test executions with 0 failures**, and `:domain` carries one input of each of the 23
supported types, each asserted to resolve its default, take an override, survive a relaunch
and clear — through real SharedPreferences on Android and real `NSUserDefaults` on iOS.
`:app` packages debug and release APKs; `:shared` links debug and release iOS frameworks.

The shipped consumer path has also been verified once, by hand, against `mavenLocal`: a
throwaway KMP project writing nothing but
`plugins { id("com.rohittp.debug-input") version "0.1.0-SNAPSHOT" }` compiled, resolved the
plugin marker, passed the version guard, got `debug-input-runtime` on its source set without
declaring it, and emitted a descriptor manifest naming both an `Int` and a `List<String>`
input. That is the path ADR-0007 gives up by hand-wiring `:app`. **Automating it as
`consumer-smoke` is still M6** — until then, nothing re-checks it.

**Goal:** Every primitive, `String`, enums, and the common composites — `List`, `Set`,
`Array<T>`, the eight primitive arrays, `Pair` and `Triple` — are debug inputs that
resolve, persist and edit on both platforms.

**Spec:** [`docs/design-note.md`](../../design-note.md),
[ADR-0008](../../adr/0008-length-prefixed-self-describing-encoding.md) (encoding),
[ADR-0009](../../adr/0009-array-inputs-return-a-cached-instance.md) (array identity).
Language: [`CONTEXT.md`](../../../CONTEXT.md). M1's plan and its amendments still apply.

## Global constraints

Everything from M1, unchanged — Kotlin 2.3.21, zero dependencies in
`debug-input-runtime`, `explicitApi()`, group `com.rohittp`. Plus:

- The codec is hand-rolled and dependency-free. No serialization library.
- **One nesting level.** A container's element type must be a scalar. `List<List<Int>>`,
  `Pair<Int,List<String>>` and `Map<*, *>` are FIR errors naming the type and pointing at
  the custom-renderer registry (M5).
- **No enums inside containers.** `List<Tier>` is a FIR error — see ADR-0008's last
  paragraph for why it cannot work without reflection.
- Malformed stored data never throws. It returns the default.

## Type set

| Category | Types | Codec tag |
|---|---|---|
| Integral | `Int` `Long` `Short` `Byte` | `int` `lng` `sht` `byt` |
| Floating | `Float` `Double` | `flt` `dbl` |
| Other scalar | `Boolean` `Char` `String` | `bln` `chr` `str` |
| Enum | any `enum class` | `enm` |
| Collections | `List<S>` `Set<S>` | `lst` `set` |
| Object array | `Array<S>` | `arr` |
| Primitive arrays | `IntArray` … `CharArray` | `iarr` `larr` `sarr` `barr` `farr` `darr` `zarr` `carr` |
| Tuples | `Pair<S,S>` `Triple<S,S,S>` | `pair` `trip` |

`S` is any scalar above except enum.

## Shared contract

### Codec — `debug-input-runtime`, internal

```kotlin
/** Encodes to the ADR-0008 wire form. */
internal fun encodeValue(value: Any?, spec: TypeSpec): String?

/**
 * Parses the wire form without needing to know the expected type, then checks the shape
 * it found against [spec]. Returns null for malformed input, an unknown tag, a length
 * that does not line up, trailing junk, or a shape mismatch — never throws.
 */
internal fun decodeValue(encoded: String, spec: TypeSpec): Any?

/** The parsed form of a codec spec literal such as `lst<str>` or `pair<int,str>`. */
internal class TypeSpec(val tag: String, val arguments: List<TypeSpec>)

internal fun parseTypeSpec(spec: String): TypeSpec?
```

### Registry — public API added

Fast paths stay unboxed and never parse a spec. Composites go through one generic entry
point whose result the IR casts.

```kotlin
public object DebugInputRegistry {
    public fun resolveInt(id: String, default: Int): Int          // exists
    public fun resolveLong(id: String, default: Long): Long
    public fun resolveShort(id: String, default: Short): Short
    public fun resolveByte(id: String, default: Byte): Byte
    public fun resolveFloat(id: String, default: Float): Float
    public fun resolveDouble(id: String, default: Double): Double
    public fun resolveBoolean(id: String, default: Boolean): Boolean
    public fun resolveChar(id: String, default: Char): Char
    public fun resolveString(id: String, default: String): String

    /** IR passes the constant table, so no reflection is needed on Native. */
    public fun <T : Enum<T>> resolveEnum(id: String, default: T, entries: Array<out T>): T

    /**
     * Composites. [spec] is a literal baked in at the call site; the caller casts.
     * Array-tagged specs return a cached instance per id — see ADR-0009.
     */
    public fun resolveComposite(id: String, default: Any?, spec: String): Any?

    @DebugInputInternalApi public fun setValue(id: String, value: Any?, spec: String)

    /**
     * The overload the page must use. The single-argument `overrideOf` is type-blind, so a
     * dormant override left by another type reads back as that type and a row would show as
     * changed while every read of the input resolves its default.
     */
    @DebugInputInternalApi public fun overrideOf(id: String, spec: String): Any?
    // setInt and the single-argument overrideOf stay.
}
```

`DebugInputDescriptor` gains `spec: String`, the codec spec literal for the input's type.
`typeKey` stays as the human-facing type name, used for the "no renderer registered" row
and for M5's custom-renderer lookup. Until the compiler emits `spec` it is empty, and the
page treats empty as `int` — the only type M1 supported.

`resolveComposite` returning the **backing-field array itself** when no override is
stored is required by ADR-0009, not incidental.

### Codec spec literals baked in by IR

`int` · `str` · `lst<str>` · `set<int>` · `arr<str>` · `iarr` · `darr` ·
`pair<int,str>` · `trip<int,int,bln>`

## Tasks

Task 1 is the critical path and everything else builds on it.

### Task 1 — the codec
`encodeValue` / `decodeValue` / `parseTypeSpec` for every tag, plus the array cache
invalidated on every override-map change. Test it as a parser, not as a happy path:
round trips per tag including empty containers, single-element containers, values
containing `:`, empty strings, `Int.MIN_VALUE`/`MAX_VALUE`, float and double specials
(`NaN`, `±Infinity`, `-0.0`), surrogate pairs in strings and `Char`; then the malformed
corpus from ADR-0008, each returning the default rather than throwing.

### Task 2 — registry fast paths and the composite path
The eight new scalar resolvers, `resolveEnum`, `resolveComposite`, `setValue`. Array
identity per ADR-0009 asserted directly: `===` across reads, in-place mutation visible,
instance swapped after an edit and after `clearAll()`.

### Task 3 — compiler: type dispatch and diagnostics
IR picks the resolver by static type and bakes the spec literal. FIR replaces M1's
"only Int is supported" with: unsupported type, nesting deeper than one level, `Map`,
and enum-inside-container — each naming the offending type.

### Task 4 — renderers
`Boolean` as a switch, enum as a dropdown, `Char` as a single-character field, the
numeric types as validated fields honouring each type's range, `String` as a text field.
Containers as a column of element editors with add and remove; `Pair`/`Triple` as
labelled sub-editors. Reorder is out of scope.

### Task 5 — dogfood
An input of every supported type in `:domain`, exercised end to end on both platforms.
