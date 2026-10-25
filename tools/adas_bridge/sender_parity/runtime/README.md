# Isolated original-APK recorder runtime

Frozen research tooling as of 2026-10-25. Private ART, pinned Frida/Android and
local corpus paths are laboratory constraints, not product dependencies. Reopen
this machinery only to explain a specific discrepancy in retained original-APK
output; do not expand it as a prerequisite to the first product drive. The
decision is recorded in `research/adas-bridge/README.md` under "Product milestone
and bench freeze".

This opt-in launcher runs the unchanged private `online_cache_emulator.py` with a
pinned Java bridge. Device preparation, the owned emulator, server hash
verification and the explicit port are controlled by the parent capture runner.
The phase-2 captures use Frida **16.7.19**, explicit bridge **6.3.9**, the JVMTI
observer and the virtual vehicle fixture described below. Frida 17 is retained
as a separately pinned diagnostic configuration; its bridge repair alone did
not close the missing original calls.

The Frida 17 diagnostic host environment is ignored under
`captures/stock-sender-parity-20261025/phase2/runtime17/`. It pins Python and Node
Frida **17.4.1**, `frida-java-bridge` **7.0.10** and `frida-compile` **19.0.4**.
Bridge 7.0.10 includes the ART visibility trampoline repair from
[`732d17fe`](https://github.com/frida/frida-java-bridge/commit/732d17fe9b6ca6bed50e6b36e269bbb50a56dae2)
and retains the `GetFieldID` initialization used before the 7.0.11 regression.
This is a controlled diagnostic runtime; including the repair does not itself
prove complete recording or sender equivalence.

Frida 17 no longer supplies Java to Python `Session.create_script`. The prelude
imports the explicit bridge and exports `globalThis.Java`. Two compatibility
aliases preserve only the legacy `Module` export lookup calls used by the private
runner and current observers. Build the prelude as **IIFE**, since an ESM Frida
bundle cannot be prefixed by plain text concatenation.

Create a separate venv and npm project in the ignored runtime directory. Its
`package.json` must pin these exact dependencies and override transitive `frida`
to `17.4.1`; preserve `package-lock.json`. Install `frida==17.4.1` in that venv,
copy `bridge-prelude.js` there as `prelude-entry.js`, then run:

```sh
node_modules/.bin/frida-compile prelude-entry.js -B iife -T none -S -o bridge-prelude.bundle.js
```

Download the official Android ARM64 server to the host from
[`frida-server-17.4.1-android-arm64.xz`](https://github.com/frida/frida/releases/download/17.4.1/frida-server-17.4.1-android-arm64.xz).
The decompressed reference SHA256 is
`19fb99a528059a7bd719f72e291dcc8f5c2390a7fdf22397cc73498d43fc7711`;
the compressed SHA256 is
`babf2708d85f5727ae4594aececa7d19d76fbf9b44a57ebe5cf12eecb2a71d19`.
The launcher does not install or start the server.

`runtime-config.json` contains absolute `python` and `launcher` paths, an
`artifacts` list of `{role,path,sha256}`, and these required launcher fields:

```json
{
  "frida_version": "17.4.1",
  "bridge_version": "7.0.10",
  "bridge_git_head": "f72e61ed18fa6b72f0559df223b22a899685b22c",
  "server_sha256": "19fb99a528059a7bd719f72e291dcc8f5c2390a7fdf22397cc73498d43fc7711",
  "prelude_path": "/absolute/path/bridge-prelude.bundle.js",
  "prelude_sha256": "sha256 of this exact built IIFE"
}
```

Invoke `[python, launcher, --runner, originalPrivateRunner, --runtime-config,
config, --, originalArgs...]`. If omitted, the launcher finds its config beside
the isolated venv through `Path(sys.prefix).parent`.

The wrapper preserves `app/agent.original.js`, archives the exact prefixed
`app/agent.js` passed to `create_script`, and writes `app/runtime17.json`. In a
`finally` block it updates `report.json.agent_sha256`, adds the original source
hash and runtime metadata, and preserves other report fields. The configured
server hash is labelled as a pin; the owner separately verifies the running
server. Unit tests verify source/hash identity and fail before running if the
prelude hash differs:

```sh
python3 -m unittest tools.adas_bridge.sender_parity.runtime.test_launch -v
```

`quick-code-probe.js` is an optional observation helper for the old bridge. Pass
already-hooked overload objects to `DenzaQuickCodeProbe.retain(label, overload)`,
then record `snapshot(stage)` before and after the missing source call. It
derives the quickCode offset from the replacement clone's known generic JNI
entrypoint and reports whether the current method entrypoint reverted to nterp.
It reads pointers only; it does not repair them or establish causal completeness.

`bridge16-prelude.js` supports a separate diagnostic configuration for the
previously healthy Frida **16.7.19** client/server with its exact bundled bridge
**6.3.9** (git `283942024e1320a0de10dc07754c2f1e94395891`). Frida 16's
[`core.js`, lines 137–148](https://github.com/frida/frida-gum/blob/16.7.19/bindings/gumjs/runtime/core.js#L137-L148)
defines `Java` through a configurable lazy accessor. This prelude checks that
the accessor has never been consumed, imports the explicit bridge, checks that
the import left the accessor intact, then replaces it. It fails if a built-in
bridge has already initialized. This prevents two active Java bridges.

The ignored `phase2/runtime16-debug/runtime-config.json` selects this variant
through the same launcher. Its bridge differs from the npm 6.3.9 source only by
an appended, lazy `DenzaBridgeArtProbe(methodId)` export that reads the exact
ArtMethod offsets and current replacement mapping. The source patch, npm lock,
bundle hash and source derivation are retained beside its configuration. It
does not include Frida 17 changes or the unmerged PR401 dispatch modification.
For compatibility the launcher still writes the historical `runtime17.json`
and `report.runtime17` keys; their version values identify the actual runtime.

Run the four host guard tests with:

```sh
python3 -m unittest tools.adas_bridge.sender_parity.runtime.test_bridge16_prelude -v
```

## JVMTI event smoke test

`jvmti-event-smoke.js` exports synchronous `DenzaJvmtiEventSmoke()`. Invoke it
inside `Java.perform`, after the first deoptimization and before SDK source
hooks, then save the returned object. It observes three `put` calls on a fresh
`HashMap` using ART method entry/exit events. It does not install Java method
replacements or native ART dispatcher hooks.

The smoke test accesses the pinned bridge's native JVMTI plugin loader, obtains
a **fresh** private ART environment (`0x70010200`), and requests method entry,
method exit and local-variable capabilities. Android 14's
[`art_jvmti.h`](https://android.googlesource.com/platform/art/+/refs/heads/android14-release/openjdkjvmti/art_jvmti.h)
permits those capabilities through the private interface even when the APK is
not debuggable. This differs from the public agent attach path. The code checks
the actual potential/acquired capabilities and every setup result.

Events are enabled only for the current Java thread. A CModule filters the four
original method IDs (`put`, `putVal`, `resize`, `newNode`) before entering JS.
The native MethodExit callback receives a C `jvalue` union and passes its eight
bytes through a pointer, avoiding a libffi assumption about float versus integer
arguments. The observer reads Java arguments from DEX input registers, starting
at `GetMaxLocals - GetArgumentsSize`; receiver identity and object results use
JNI references. It disables both events, clears its own callback table and
disposes its fresh environment before returning.

The parent-owned Android 14/API 34 ARM64 capture
`phase2/debug-24/run/app/events.jsonl` recorded **PASS** with Frida 16.7.19 and the
explicit 6.3.9 bridge: entry and exit counts were `3/3/1/2`, all 18 events were
balanced, entry argument identities and the replaced value's return identity
matched, and all setup/cleanup calls returned zero. The original fixture
continued afterward. The original sender recording still had four mandatory
callback gaps after smoke cleanup; this smoke result does **not** establish full
recording or sender parity. The extracted event and source/ABI evidence are in
`phase2/research/jvmti/`.

ART rejects `GetLocalObject/Int/Long/Float/Double` for native/proxy frames with
`OPAQUE_FRAME`. `GetLocalInstance` uses a separate stack visitor; native receiver
availability still requires a targeted runtime check. Retain entry references
for exit snapshots because method bodies can reuse parameter registers. Native
zero-argument clocks need only actual MethodExit return bits. Exceptional exits
provide a flag and an invalid return sentinel, so a complete recorder also
needs exception observation. The smoke covers Java object-returning HashMap
methods; it does not prove those additional cases.

Host checks execute the extracted C trampoline with several exact 64-bit values
(including NaN bits), verify its method/active filters, and verify unsupported
ABIs are rejected before touching Java:

```sh
python3 -m unittest tools.adas_bridge.sender_parity.runtime.test_jvmti_smoke -v
```

## Quiet terminal boundary and recorder failures

New captures declare `terminal_policy=quiet_no_due_or_overdue_pending_v1` in
their metadata, `ready` event and `capture_end` event. After a stop request, the
observer waits for empty source stacks, no in-flight enqueue, and no remaining
JVMTI frames or other active callbacks. The current MethodExit callback may
finish the capture only after its selected frame has been popped. It also
requires every accepted, uncancelled pending post's `due_uptime_ms` to be
strictly greater than `capture_end.observer_uptime_ms`. Equality is already due
and prevents completion. The observer retries rather than discarding overdue
work; a runner deadline without a terminal event remains incomplete.

`observer_uptime_ms` is a decimal string from native `CLOCK_MONOTONIC`, rounded
down to milliseconds by removing the last six nanosecond digits. This avoids
floating-point precision loss and uses no `Int64.div` operation, which Frida
does not provide. This observer timestamp never enters the original clock
input transcript or the replayed sender state. Future recurring work can remain
pending at this finite boundary. The policy does not prove what would happen
after the cutoff, and older captures retain their declared historical terminal
contract. In particular, the earlier active-03 equality result does not itself
prove that queued arrival, reroute or lane-flush work executed before its cutoff.

The observer marks itself ended only after successfully reporting `capture_end`.
Emission reserves its sequence before reading metadata; a failed reserved row
is never synthesized or repaired. Failure also sends a plain native
`sender_phase2_recorder_diagnostic` message, independent of Java metadata and
the normal reporter. Its schema is `denza-stock-sender-phase2-diagnostic-v1`;
it includes the failure stage and, for emission errors, `reserved_seq` and
`source_kind`. Diagnostics remain in `capture_end.recorder_diagnostics` when a
later terminal event is possible. A diagnostic or sequence hole invalidates the
capture; missing `capture_end` is not a successful quiet boundary. The reset-01
hole at sequence 5137 predates this visibility change, so its cause remains
unproved until a fresh capture supplies evidence.

The reset-03 run exercised this diagnostic path: its terminal timestamp code
called the unsupported `Int64.div`, and the native fallback reported that
failure. That capture remains incomplete; the decimal conversion and host
mock were corrected for subsequent runs.

Host checks cover due/equal/future pending posts, active callback rejection,
terminal-report failure, reserved sequence preservation, and preservation of
original return values when observation fails:

```sh
python3 -m unittest tools.adas_bridge.sender_parity.test_phase2_recorder tools.adas_bridge.sender_parity.test_phase2_terminal -v
```

## Declared position-input disconnection

The SDK can keep emitting fresh location callbacks after the finite GNSS feed
and navigation have ended. With a slower instrumented consumer, that input
stream can grow an overdue queue indefinitely. `input-boundary.js` declares an
emulator fixture boundary instead of dropping queued callbacks. Its policy is
`remove_position_observer_after_fixture_inputs_v1`; captures retain it as
`metadata.input_boundary_policy` and archive the exact helper under artifact role
`input_boundary_fixture`.

Inside `Java.perform`, install it once with
`DenzaInputBoundary.install({sampleCount, callbackCase, report, reference})`.
`callbackCase` is the declared callback fixture name or explicit `null` for the
baseline; `reference` is the recorder's `p2Ref` function. The parent calls
`poll({deliveredSamples, started, callbackFixtureComplete})` during its tail.
`deliveredSamples` counts actual successful `PosService.setSignInfo(data)`
returns, each retained as `gnss_input_return`; a sample's pre-call index is not
delivery proof. The helper requires that count to reach `sampleCount`, guidance
to have started, and the actual callback fixture completion flag when a fixture
exists. It then reads the original `NaviController.isNaving()` and waits until
that method returns false.

The only input action resolves SDK service 13 and the original
`GaoDeSignalManager.getInstance()`, then calls the public
`PosService.removeLocInfoObserver` with that exact singleton. Original source
registers the same singleton through `addLocInfoObserver(this, 0)` in its
constructor. No sender observer is removed, no method is replaced, and no
Handler task is cancelled. Existing in-flight callbacks and every queued task
remain observable and must satisfy the strict terminal policy above.

Immutable `input_boundary_installed`, `input_boundary_disconnect_enter` and
`input_boundary_disconnect_exit` receipts carry schema
`denza-stock-sender-input-boundary-v1`, the policy, declared case/sample count,
prerequisites, exact method and stable original object references. An attempt is
never retried. The exit status is `call-returned`: the original SDK removal
method returns void and catches internal exceptions, so that receipt alone does
not independently verify successful removal. Subsequent input/queue evidence
must establish its effect. `input_boundary_error`, errors or report errors in
`metadata()`, missing receipts, or a missing terminal event invalidate the run.

```sh
python3 -m unittest tools.adas_bridge.sender_parity.runtime.test_input_boundary -v
```

## Emulator vehicle inputs

`vehicle-fixture.js` provides platform inputs without replacing original Java
getter implementations. Load it as source, then call
`DenzaVehicleFixture.installNoa({report})` inside `Java.perform` before original
NOA initialization. `report(kind, fields)` is optional and records fixture
provenance. Installation creates a real virtual subclass of the original
`k.h.e0.a.r` adapter and replaces the one field of that declared type in the
original `NoaStateManager` singleton. Its constructor delegates to the original
two-listener constructor. Existing adapter fields are preserved; installation
fails if a device or a device listener is already attached because such a
listener can retain its original adapter instance.

The ten declared getters preserve the existing input profile:
`A/E/H/w/x/z/C=true`, `s=0`, `t=1`, `G=2`. The returned handle exposes
`className`, `getterMethods`, `proof`, `values()`, `setValues(values)`,
`metadata()` and `validate()`. `setValues` accepts exactly those ten typed
values and records the change. `validate()` calls the unchanged original
`NoaStateManager.i/h/r` methods and requires adapter identity, ADAS type and tip
island results to agree. Those proof reads carry `phase=validation`; actual
virtual getter invocations carry `phase=original_execution`. The original
`NoaStateManager` methods remain the recorder's outer SDK input seams, while the
subclass getter receipts audit their platform source. Fixture report errors are
retained by `metadata()` and must not be treated as successful provenance.

`DenzaVehicleFixture.deliverVehicleSpeed(kmh, {report})` calls the exact original
real-speed listener object owned by the actual `BydAutoSpeed` instance obtained
from `BydAutoHub`. That listener applies the original bounds, updates its real
speed cache, schedules notifications and calls the original registered
listeners. Delivery checks the original `realSpeed()` getter against the input.
It rejects speeds outside the original `0 <= km/h < 282` predicate. It leaves
`currentSpeed()` and the other caches unchanged; the previous fixture's promise
that both getter methods return the same sample is no longer made. Feed this
API from declared vehicle samples, before the corresponding original guidance
callback, and retain both delivery and actual consumed getter receipts.

This fixture replaces only the emulator's platform adapter/input. It never
patches NOA state, sender gates, sender methods or output payloads. Host tests
check construction, virtual dispatch in an API mock, field preservation,
attached-device rejection and original listener delivery. Parent-owned
`phase2/debug-29` then verified actual ART dispatch: the unchanged manager
reported the same adapter, ADAS type `1` and tip-island `true`; observed original
manager reads returned those values, and original listener deliveries at
`9/18/27/36` km/h each matched `realSpeed()`. The exact helper source is present
in that capture's archived agent. Extracted events and source hashes are saved in
`phase2/research/jvmti/vehicle-fixture-debug29-observed-events.json`. This proves
the fixture mechanism, not full sender parity. Run the host checks with:

```sh
python3 -m unittest tools.adas_bridge.sender_parity.runtime.test_vehicle_fixture -v
```

## City-cache parser reference

The original APK embeds Gson **2.9.1**. Its `GsonUtils.jsonToList` first parses a
lenient JSON tree, then converts every element to `Integer`; `m.i` installs the
list only after all conversions succeed. The plain Java port preserves that
accepted integer-array language, including comments before/inside arrays,
single quotes, semicolon separators, missing elements as null, leading BOM and
the anti-XSSI prefix. Numeric JSON tokens wrap through `Number.intValue`, while
quoted or unquoted string tokens use `Integer.parseInt`. Gson restores strict
reader mode before its final document check, so trailing comments are rejected.

The reference fixture was produced with the published Gson 2.9.1 artifact,
whose version and relevant parser/converter code were checked against the
firmware corpus. It is a version-matched library oracle, not direct APK
execution. `NativeCityCacheTest` checks 74 retained cases and cache lifetime;
a bounded deterministic sweep compared 4,074 inputs against that artifact.
Both passed for accepted values/order and rejection. The attempt flag uses the
original `AtomicBoolean` visibility semantics, and a failed parse leaves no
partial city set and cannot trigger a second cache read.

Malformed-input **exception classes and messages are not identical**: the
original helper propagates Gson exceptions (or `IllegalStateException` for a
non-array root), while the dependency-free parser throws its own Java runtime
exceptions. Those captures remain outside an equality claim. Artifact hashes,
oracle generator, sweep and source evidence are retained in
`phase2/research/city-cache/`; the 74-case fixture is test source. This result
does not establish universal equivalence for all possible input strings.
