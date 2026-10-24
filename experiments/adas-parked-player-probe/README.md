# Parked ADAS player probe

Plays one parked ADAS feed scenario (`tools/adas_bridge/scenario.py`) on the car and records
ADAS's answers, to find by controlled experiment why NOA was offered from the stock map's own feed
at the start position but not from our bridge. One scenario per run; the host runner
(`tools/adas_bridge/parked_session.py`) pushes `scenario.json` and `preconditions.json` into the
private files dir, launches `PlayerActivity` with the boolean extra `parkedOnce`, and pulls
`player.jsonl`.

Disposable probe, built only with `-Pexperiments`. Nothing in a product depends on it.

- `PlayerEngine` plays the scenario and **always** ends with the stock stop sequence (status 110
  for 3 s, then 100) and releases its session - on a clean finish, an exception, or a watchdog/kill
  stop. Fresh pathId, counters and `time` per run.
- `Preconditions` refuses unless `com.byd.launchermap` is absent and disabled (enabledState 3) and
  no valid Navigator live arm is held; the device facts are collected the way `live_session.py`
  collects them and handed over as `preconditions.json`.
- `AdasAnswerListener` records every service-13 (PilotStatus/alarm/NOA mode) and service-44
  (MMTPathSelected/PathMatch) answer, with elapsedRealtime and wall time. It never fires a server
  event and never requests 13/0x0001 (the DNP/ICC switch).
- `NaviStShell` (H6) writes the stock `SETTING_MPC_IFC_NAVI_ST_SET` side channel (device 1023,
  `0x4CA0004B`), reading it first and restoring it after. That device needs a `BYDAUTO_*`
  permission the app UID lacks, so the step is **enabled only for a shell-UID launch**; an ordinary
  app-UID launch records it disabled with no invented path.

The pure core (`ScenarioPlan`, `PlayerEngine`, `Preconditions`, `PlayerMessages`) has no Android
imports and is tested off-device by `tools/adas_bridge/parked_player_harness/run.py` against a fake
transport/clock/NaviSt. Payloads are built by the byte-exact `adas-link` encoders.

Durable findings live in `research/adas-bridge/README.md`.
