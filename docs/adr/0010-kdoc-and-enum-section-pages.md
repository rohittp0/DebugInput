# KDoc is descriptor documentation, and enum sections have dedicated pages

`@DebugInput(docs = ...)` remains an explicit override, not the primary documentation
source. For an ordinary annotated property and for an enum entry, descriptor documentation
is resolved in this order:

1. an explicitly supplied `docs` argument;
2. the declaration's KDoc, normalised by the existing phone-sized KDoc normaliser;
3. empty text.

For an annotated enum class, the same precedence produces the section description. Reading
all three declaration shapes uses the same FIR-backed light-tree walk, already verified to
return byte-identical text under LightTree and PSI in Kotlin 2.3.21. IR comments are not
available, so the backend reaches source KDoc through `FirMetadataSource`.

The page renders ordinary sections inline. An enum-class section appears once on the root
and opens a dedicated page containing the enum's description and its generated inputs. This
keeps enums such as `MagicNumbers` from adding a hundred editors to the root page.

## Descriptor shape

Each enum-class descriptor carries two shared section facts:

- `sectionDescription`, the normalised enum-class documentation;
- `enumSectionId`, the enum's fully qualified name.

`enumSectionId` is nullable. Its presence both selects dedicated-page navigation and gives
the section a stable identity. The display title alone is insufficient: a top-level property
in `MagicNumbers.kt` also belongs to a display section named `MagicNumbers`, and two packages
in one module may declare enums with the same simple name. Ordinary descriptors leave the id
null and the description empty.

The fields are appended to `DebugInputDescriptor` with defaults so hand-built descriptors
and older call sites remain source-compatible. Repeating the section facts on row descriptors
keeps `DebugInputsPage(descriptors)` as the only interface; adding a parallel section graph
would expose aggregation complexity to every caller.

## Consequences

- KDoc is no longer duplicated into annotation arguments for ordinary inputs.
- An explicit annotation argument can still replace or deliberately suppress KDoc.
- Full enum KDoc is emitted once in the class constant pool and referenced by each descriptor;
  there is no per-read cost.
- The page owns its subpage state and requires no consumer navigation dependency. It provides
  an on-page Back action on every enum page.

## Amendment: named sections and pages for every generated section

The enum-only navigation decision above is superseded. `@DebugInput` now has an optional
`section` string. Empty preserves the existing title derived from the declaring class, object,
enum or file; a non-empty value is the section title and deliberately groups equal values within
one Gradle module.

Every compiler-generated section appears once on the root and opens a dedicated page. This keeps
navigation consistent: file and class sections no longer change shape merely because one of them
is an enum. Descriptors now carry nullable `sectionPageId` instead of `enumSectionId`. The compiler
always fills it; null is retained only so older or hand-built descriptors remain source-compatible
and render inline.

Page identity is distinct from its visible title:

- default class/object sections use `class:<fully-qualified-name>`;
- default file sections use `file:<package>/<file-base-name>`;
- enum sections use `enum:<fully-qualified-name>`;
- explicit sections use `custom:<section>`.

An enum's page description still comes from the enum declaration's explicit `docs`, then KDoc,
then empty. Ordinary section pages currently have no shared description; their individual rows
still use the property documentation precedence defined above.
