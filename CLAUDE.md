# CLAUDE.md

Guidance for working in this repository (humans and AI).

## What this is

Denza Lab is a monorepo for reverse-engineering a Denza / BYD head unit and
building useful apps on it. Three concerns are kept separate on purpose:

- **Apps** — Car ADB Gateway and Denza Apps are active; Denza Mirrors is being
  merged into Denza Apps; Denza Gateway is legacy/maintenance-only.
- **Poking the car** — host scripts in `tools/`, on-device probes in
  `dev.denza.mirrors.probe`.
- **Knowledge of what works / doesn't** — `docs/` (durable) and `research/`
  (parked code).

The GitHub repository is `xor777/denza-lab`. An existing local checkout may
still use the historical `denza-gateway` directory name.

## Read before changing code

- [docs/project-map.md](docs/project-map.md) — structure and per-component status.
- [docs/governance.md](docs/governance.md) — product/prototype/research lanes,
  where experiments live, promotion checklist, live-car debugging rules.
- [docs/instrument-display-findings.md](docs/instrument-display-findings.md) — cluster scene, Mirrors, and navigation status.
- [docs/dishare-api-notes.md](docs/dishare-api-notes.md) — DiShare/HUD findings.

## Modules

| Gradle | Path | App id / namespace |
| --- | --- | --- |
| `:denza-gateway` | `legacy/denza-gateway/` | `dev.denza.gateway` (legacy/maintenance-only) |
| `:denza-mirrors` | `apps/denza-mirrors/` | `dev.denza.mirrors` (transition) + `dev.denza.mirrors.probe` (research) |
| `:denza-apps` | `apps/denza-apps/` | `dev.denza.apps` (active consolidation app), depends on `:dishare-bridge` |
| `:dishare-bridge` | `libraries/dishare-bridge/` | `dev.denza.disharebridge` (library) |
| `:car-adb-gateway` | `apps/car-adb-gateway/` | `ru.adbgw.gateway` (active product candidate) |

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-mirrors:assembleDebug
./gradlew :denza-apps:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

## Conventions

- Product code must not depend on `…​.probe` code. The active Denza Apps path
  has no probe dependency; the transition-only standalone Mirrors module still
  contains a documented historical exception.
- Product apps share car-access code only via `:dishare-bridge`.
- Do not add features to `:denza-gateway`. Limit changes to maintenance or work
  required to retire it safely.
- New camera behavior belongs in `:denza-apps`; treat `:denza-mirrors` as the
  migration source, not a new standalone product direction.
- `:car-adb-gateway` is relay-only. Do not add a LAN listener or configurable
  relay without updating the CAG decision log first.
- Deploy `platform/relay/` only through `ops/ansible`; keep code/grant transitions locked,
  atomic, and covered by relay tests.
- New "poke the car" code goes to `tools/` (host) or a `…​.probe` package
  (on-device), never into a product package.
- Code, manifests, and Gradle files are the source of truth for current behavior.
  Docs should map the repo and record verified findings, not duplicate the code.
- Record durable findings in the closest existing doc, not only in chat. Create a
  new `.md` only when the topic has a durable owner. Parked code → `research/`.
- Never commit APKs, reverse-engineered APKs, or large extracted binaries
  (`reverse/`, `captures/`, build outputs are git-ignored).
- Treat `com.byd.avc` crashes as a hard stop; capture `logcat -b crash -v time`.
