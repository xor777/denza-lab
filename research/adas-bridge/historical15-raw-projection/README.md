# Historical15 raw-projected position experiment

Prepared 2026-10-31 on stock-sender-parity. This is an explicitly requested experiment on the historical15 sender. It does not change the active product factory core or establish factory equivalence.

Base: `captures/yandex-feed15-fixed20-20261029/source/`, pinned original source ZIP SHA256 `684744d5de68f45e4ec6eb1df5dc832d2ac03912caca313254975fde07554762`, fixed20 APK SHA256 `69ef8198805fec75b4017b37b644c0067dba2e192e049842accec27dfe5cc002`.

Apply `raw-projection.patch` with `patch -p1` only to a disposable copy of that historical fixed20 source tree. Never apply it to current product sources. Full original build prerequisites and exact signing identity are retained in `captures/yandex-feed15-proj-20261029/build-root/`; `build_candidate.py` overlays historical source then runs the unchanged historical builder with every gate.

The new per-arm `position.source` accepts `yandex` (default) and `rawProjected`. The latter projects fresh raw GNSS onto the original WGS84 route, searches -20/+150m around the last successfully sent position, rejects >25m lateral distance and raw ages outside0..1500ms, then advances along route by finite vehicle speed when supplied, otherwise raw GNSS speed, times raw age. All route-derived dynamic fields use the chosen scalar. No service10 subscription is added. The original SDK freshness/cadence policy remains.

Integration corrections are part of this one position-source experiment: the variant applies NaviInfo and location on the existing serial sender together; it refreshes that path's FINE lease before this synchronous send; it restores local progress state if the position is rejected. No flag leaves those paths active in the default mode.

Two explicit recipe refinements: a fallback Yandex scalar behind the last successful send is floored together with point/course/attributes, marked `yandexForwardClamped`; if raw GNSS expires during preceding Binder calls, that position is dropped with `fireInvoked=false`, and the next observation selects the complete fallback. Audit is attached to each position attempt, distinguishes a drop from transport invocation, and records chosen/Yandex scalar, along-route delta, lateral distance, source age, speed, compensation, wire coordinates and actual result.

Offline evidence and signed APK: `captures/yandex-feed15-proj-20261029/`. Replay report under `replay/REPORT.md`; independent review under `REVIEW.md`. Operational preparation: `captures/bisect-pre-arm-20261027/run15-proj-20261031/`.

Replay assumptions matter: old VehicleLocation logs omit raw GNSS age. With zero assumed age, the target eastbound interval has39 accepted projections with mean advance4.162m; all42 samples including3correlated rerouting fallbacks average3.864m. Accepted lateral median0.879m/p902.690m/max11.497m: the recipe's strict all-sample <=5m expectation fails. Actual compensation and NOA benefit are not established. The preserved final projector matches an independent calculation on8959calls including7boundary cases.

No car commands, installation or arm occur during this offline preparation. When the owner connects, identify the actual APK/boot/arm before closing any prior owned session; require current stationary confirmation before the new arm. No autoservice setters. Install with `-r`, verify full hash and same certificate, then one parked route: SELECTED200, TrafficInfo20, WGS84 projected point on route, current positionSource notes. Export and SHA/CRC verify after the drive, then restore. A single run supports or weakens the candidate; it does not establish the ECU's tolerance or root cause.
