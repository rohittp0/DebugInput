# Changed values export as versioned JSON

Testers can copy every effective change from `DebugInputsPage` as one JSON document. The export
contains only overrides that decode against the descriptor's current type spec and differ from its
current default. Dormant overrides and overrides equal to the default are omitted, matching the
page's visible `changed` state.

The payload is deterministic: changes are deduplicated and sorted by stable input id. Version 1 is:

```json
{
  "version": 1,
  "changes": [
    {
      "id": "com.app.physics.speed",
      "module": ":domain",
      "section": "Physics",
      "name": "speed",
      "type": "kotlin.Int",
      "default": 10,
      "value": 42
    }
  ]
}
```

The id is the machine-stable source locator. Module, section, name and type make the handoff readable
without requiring developers to decode the id. Including both default and value makes stale reports
obvious when a default changed after the tester's build.

The Compose artifact writes JSON directly instead of adding a serialization dependency. Supported
scalar values become JSON scalars, composites become arrays, enum and character values become
strings, and non-finite floating-point values become strings so the document remains valid JSON.
The root and every section page expose the same Copy JSON action and show confirmation after the
clipboard write.
