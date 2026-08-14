# Live reads take a lambda, not a property reference

Because `debug-input-runtime` deliberately has no Compose dependency, a plain read
of an input inside a `@Composable` does not recompose when the value changes on the
page. Consumers opt into liveness with `rememberDebugInput { speed }`, whose lambda
body FIR restricts to a single read of a `@DebugInput` property. The IR plugin
replaces the lambda with the baked-in id literal.

The lambda form was chosen over the `rememberDebugInput(::speed)` shape the original
design note proposed. On Android release no transform runs, so the call site must
still compile and return a value; with a lambda the release lowering is
`remember { speed }` — keep what the caller already handed us — instead of bespoke
IR that synthesises a constant `State` and is only ever exercised in release builds.
The lambda also expresses class members and enum-class inputs
(`rememberDebugInput { Tier.FREE.limit }`) without bound callable references, and
never materialises a `KProperty` on Kotlin/Native.

## Consequences

`{ … }` invites `rememberDebugInput { speed * 2 }` in a way `::speed` does not, so
the FIR diagnostic carries more of the load. The runtime must expose a small
id-keyed listener API in plain Kotlin for the compose artifact to adapt into a
`State`. Two read paths still exist, and using the plain one inside a composable
still yields a stale value — that trade is unchanged.
