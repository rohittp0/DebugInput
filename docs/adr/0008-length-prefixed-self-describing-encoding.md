# Overrides are stored as one length-prefixed, self-describing string

An override occupies exactly one store key, holding a string in which every value —
scalar or composite — is framed identically:

```
<tag>:<length>:<payload>
```

`length` is always the payload's char count. Containers put their encoded elements in
the payload back to back, so the element count falls out of parsing until the payload
is consumed rather than being stored separately.

```
Pair<Int,String>(3, "backoff")
  pair:20:int:1:3str:7:backoff

List<String>(["api.example.com", "cdn.example.com"])
  lst:44:str:15:api.example.comstr:15:cdn.example.com
```

Scalar tags: `int` `lng` `sht` `byt` `flt` `dbl` `bln` `chr` `str` `enm`. Container
tags: `lst` `set` `arr` `pair` `trip`, and `iarr` `larr` `sarr` `barr` `farr` `darr`
`zarr` `carr` for the primitive arrays.

## Considered Options

- **One store key per element** (`hosts.$size`, `hosts.0`, `hosts.1`) — reuses the
  scalar codec with no parser at all and no escaping to get wrong. Rejected because a
  composite then has no atomic write: a half-written value is a reachable state that
  every read has to defend against.
- **`kotlinx-serialization-json`** — least code and a format anyone can read out of a
  preferences file. Rejected for the reason `multiplatform-settings` was: this artifact
  lands in every instrumented module of every consumer, so a third-party coordinate can
  collide with the consumer's own version.

## Consequences

Length prefixes mean no escaping, so a value containing `:` needs no special handling
and there is no escape-sequence bug class. Because the encoding is self-describing, a
decoder does not need the expected type to parse — it parses, then compares the shape
it found against the shape the caller wanted, and a mismatch falls back to the default.
That is what keeps dormant overrides (ADR-0005) working when an id is later claimed by
an input of a different type.

Every bug in this scheme lives in one parser. That is the accepted trade, and it is
only acceptable with a test suite that treats the codec as a parser: round trips for
every tag, and malformed input — truncated payloads, lengths that overrun or undershoot,
negative and non-numeric lengths, unknown tags, trailing junk, empty strings, deeply
nested payloads — returning the default rather than throwing.

Enums are supported as a whole input type but **not inside a container**: decoding a
constant name needs that enum's entry table, which cannot travel in a codec spec string,
and Kotlin/Native has no reflection to recover it. `List<Tier>` is a FIR error.
