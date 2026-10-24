# Offline route comparison diagnostic

Research only. These Java files and their compile-only SDK stubs are outside
`experiments/yandex-hud-module/src/main/java` and must not be packaged in the APK.
An offline router can choose a different route because the online router uses
live traffic. Route equality is **not a mandatory offline-map readiness gate**;
`DIFFERENT_PATH` means only that this diagnostic did not reproduce the path.

The bounded diagnostic creates a separate `OFFLINE` router, copies request
points and available options, requires the native `builtOffline` flag, and
compares every ordered coordinate after exact duplicate removal. It never
starts or changes guidance. It fences callbacks by route epoch, geometry and
inventory fingerprint, cancels the session on timeout/reroute/final result,
clears retained attempt references, and does not retry an unchanged subject.
Its listener implements the exact SDK interface; no dynamic proxy enters JNI.

Run the host lifecycle harness:

```sh
python3 research/adas-bridge/offline-route-diagnostic/test_verifier.py -v
```

Eleven scenarios cover native-offline provenance, different paths, stale
geometry/inventory, timeout, cancellation, synchronous callbacks, duplicate
normalization, source options preservation and endpoint fallback.

`DrivingRouter.matchRoute(Polyline, ...)` is also **unsupported by the pinned
concrete offline router**: virtual slot `0x60` of `OfflineDrivingRouter`
(`0x39f7f58`) points to `0x20f7294`, which writes an empty shared result and
returns. The online implementation of the same slot is real code. This is
native byte evidence, not an executed claim about the caller's exact error.
Do not use that offline method as a coverage probe.

Frozen source pins, native dispatch evidence, inventory verification and the
host test receipt are local under
`captures/yandex-vector-enrichment-20261024/offline-readiness/`. No device was
accessed by this work. The next pass needs direct coverage of the existing
online polyline in local package graphs, with continuous geometry and explicit
source/version/topology limits; region samples or bounding boxes are not that proof.
