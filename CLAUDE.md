# CLAUDE.md

Working notes for anyone changing this repository.

## What this is

Denza Lab contains apps for a Denza / BYD head unit, the infrastructure around
them, and the research that made those apps possible. The tree has three broad
areas:

- **Apps** — Car ADB Gateway and Denza Apps are active; Denza Mirrors and Denza
  Gateway are frozen under `legacy/`.
- **Experiments** — host scripts in `tools/` and isolated on-device probes;
  historical Mirrors probes stay with the legacy source.
- **What we learned** — durable findings in `docs/` and parked code in
  `research/`.

The GitHub repository is `xor777/denza-lab`. An existing local checkout may
still use the historical `denza-gateway` directory name.

## Read before changing code

- [docs/project-map.md](docs/project-map.md) — structure and per-component status.
- [docs/governance.md](docs/governance.md) — product/prototype/research lanes,
  where experiments live, promotion checklist, live-car debugging rules.
- [docs/instrument-display-findings.md](docs/instrument-display-findings.md) — cluster scene, Mirrors, and navigation status.
- [docs/dishare-api-notes.md](docs/dishare-api-notes.md) — DiShare/HUD findings.
- [docs/fse-app-installation.md](docs/fse-app-installation.md) — verified passenger-screen app installation path.

## Modules

| Gradle | Path | App id / namespace |
| --- | --- | --- |
| `:denza-gateway` | `legacy/denza-gateway/` | `dev.denza.gateway` (legacy/maintenance-only) |
| `:denza-apps` | `apps/denza-apps/` | `dev.denza.apps` (active consolidation app), depends on `:dishare-bridge` |
| `:dishare-bridge` | `libraries/dishare-bridge/` | `dev.denza.disharebridge` (library) |
| `:car-adb-gateway` | `apps/car-adb-gateway/` | `ru.adbgw.gateway` (active product candidate) |

The frozen Denza Mirrors source lives at `legacy/denza-mirrors/` and is not
included in the root Gradle build.

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-apps:assembleDebug
./gradlew :car-adb-gateway:testDebugUnitTest :car-adb-gateway:assembleDebug
```

## Conventions

- Keep `…​.probe` code out of product dependencies. Denza Apps has no probe or
  Denza Mirrors dependency. The frozen standalone Mirrors source retains one
  documented historical product-to-probe exception.
- Product apps share car-access code only via `:dishare-bridge`.
- Do not add features to `:denza-gateway`. Limit changes to maintenance or work
  required to retire it safely.
- New camera behavior belongs in `:denza-apps`; use
  `legacy/denza-mirrors/` only as a frozen historical reference.
- `:car-adb-gateway` is relay-only. Do not add a LAN listener or configurable
  relay without updating the CAG decision log first.
- Deploy `platform/relay/` only through `ops/ansible`; keep code/grant transitions locked,
  atomic, and covered by relay tests.
- New "poke the car" code goes to `tools/` (host) or a `…​.probe` package
  (on-device), never into a product package.
- When docs and implementation disagree, follow the code, manifests, and Gradle
  files, then correct the relevant page.
- Record durable findings in the closest existing doc, not only in chat. Create a
  new `.md` only when the topic has a durable owner. Parked code → `research/`.
- Never commit APKs, reverse-engineered APKs, or large extracted binaries
  (`reverse/`, `captures/`, build outputs are git-ignored).
- Treat a `com.byd.avc` crash as an escalation alert. Capture
  `logcat -b crash -v time`, tell the user once, and continue safe in-scope work
  without repeating the suspected trigger until it is isolated.
