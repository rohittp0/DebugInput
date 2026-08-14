# debug-input

A Kotlin compiler plugin and Compose Multiplatform page that make selected `val`s
editable at runtime in debug builds on Android and iOS, and inert in release.

## Language

### The thing being tweaked

**Debug input**:
A `val` annotated `@DebugInput` whose read resolves through the registry in debug builds.
_Avoid_: flag, toggle, setting, knob, tweakable, variable

**Default**:
The value produced by the property's original initializer, preserved as a private backing field.
_Avoid_: initial value, fallback, base value

**Override**:
A value set on the page that replaces an input's default and persists across launches.
_Avoid_: value, custom value, change, edit

**Resolved value**:
What a read of the input actually returns — the override if one exists, otherwise the default.

**Id**:
The string identifying one input across launches and refactors.
_Avoid_: key, name, path

**Dormant override**:
A persisted override whose id currently matches no input; ignored, never deleted, and applied again if an input later claims that id.
_Avoid_: orphan, stale value, garbage

**Inert**:
The release-build property that an input reads exactly its default, with no registry involvement.

### Kinds of input

**Enum-class input**:
Each constructor `val` of each constant of an `@DebugInput`-annotated enum class, grouped into one section named after the enum.

**Enum-typed input**:
A debug input whose type happens to be an enum, rendered as a dropdown of that enum's constants.

### Types and storage

**Scalar**:
A supported leaf type — the primitives, `String`, or an enum.

**Composite**:
A supported container type: `List`, `Set`, `Array`, a primitive array, `Pair` or `Triple`, whose elements are scalars.
_Avoid_: collection, complex type, aggregate

**Type spec**:
The compile-time description of an input's type, baked in at the call site as a literal such as `lst<str>` or `pair<int,str>`.
_Avoid_: signature, schema, type string

**Wire form**:
The single length-prefixed, self-describing string an override is stored as — `pair:20:int:1:3str:7:backoff`.
_Avoid_: serialized value, blob, payload

**Codec**:
The encoder and decoder between a value and its wire form.

### Compile-time products

**Descriptor**:
The generated record of one input — id, display name, section, module, type key, docs, default provider, enum constants.
_Avoid_: metadata, spec, entry, definition

**Descriptor manifest**:
The JSON side output listing one module's descriptors, written for the Gradle plugin to read.
_Avoid_: manifest (unqualified — collides with `AndroidManifest.xml`), index

**Instrumented module**:
A module the compiler plugin ran over, and which therefore has a descriptor function.

### Runtime

**Registry**:
The in-memory object that rewritten getters call to resolve a value.
_Avoid_: store, manager, container

**Override store**:
The per-platform persistence behind the registry — SharedPreferences on Android, `NSUserDefaults` on iOS, each in its own namespace.
_Avoid_: preferences, cache, storage, registry

**Plain read**:
An ordinary read of the input; correct everywhere, but does not recompose when an override changes.

**Live read**:
`rememberDebugInput { … }`; recomposes when the override changes.

### The UI

**Page**:
`DebugInputsPage()`, the single entry point that renders every input it can see. Each compiler-
generated section is one root link and opens its own page. It can copy all currently changed values
as a versioned JSON handoff for developers.
_Avoid_: screen, panel, menu, debug menu

**Module**: The Gradle project an input was declared in; the page's outer grouping level.

**Section**: A named grouping of rows within a module. By default it is the declaring class,
object, enum or file; `@DebugInput(section = "…")` can group properties under an explicit name.
An enum-class section may carry the enum's KDoc as its description.
_Avoid_: category, group, header

**Renderer**: The composable that edits one type of value.

**Type key**: The type's fully qualified name, baked in as a literal and used to look up a renderer.

## Relationships

- A **debug input**'s type is a **scalar** or a **composite**; a **composite**'s elements are **scalars**
- An **override** is stored as one **wire form** string, written and read by the **codec**
- A **type spec** says what shape the **codec** must find; a mismatch leaves the override **dormant**
- A **debug input** has exactly one **default** and at most one **override**
- An **override** is identified by an **id** and persisted in the **override store**
- A **descriptor** describes exactly one **debug input**
- A **descriptor** belongs to one **section**, and a **section** to one **module**; compiler-
  generated descriptors carry a stable section-page id, and enum descriptors also share the
  enum's section description
- An **instrumented module** produces one descriptor function and one **descriptor manifest**
- The **page** renders one **renderer** per input, chosen by **type key**, with sections reached
  through dedicated subpages
- An **enum-class input** is one constructor `val` of one constant; a **enum-typed input** is one input whose type is an enum — these are unrelated mechanisms

## Example dialogue

> **Dev:** "`Tier` is an enum with a `limit`. If I annotate it, do I get one row?"
>
> **Author:** "You get four — that's an **enum-class input**, one per constructor `val` per constant, all in a `Tier` **section**. A dropdown is what you get from an **enum-typed input**: a property whose *type* is `Tier`. Different mechanisms; annotating `Tier` isn't needed for the dropdown."
>
> **Dev:** "I set `speed` to 25 on the **page** but my composable still draws 10."
>
> **Author:** "A **plain read** doesn't recompose. The **resolved value** is 25 — read it again after any recomposition and you'll see it. For it to update live, use a **live read**: `rememberDebugInput { speed }`."
>
> **Dev:** "I renamed the property and my 25 vanished."
>
> **Author:** "The **id** changed, so the **override** is now **dormant** — still stored, ignored, and it comes back if anything ever claims that id again. Rename it back and your 25 returns."

## Flagged ambiguities

- "value" was used for the **default**, the **override**, and the **resolved value** — resolved: three distinct terms, never bare "value".
- "store" was used for both the in-memory **registry** and its persistence — resolved: **registry** resolves, **override store** persists.
- "manifest" collides with `AndroidManifest.xml`, which this project also ships — resolved: always **descriptor manifest**.
- "enum input" was used for both annotating an enum class and having an enum-typed property — resolved: **enum-class input** and **enum-typed input**.
