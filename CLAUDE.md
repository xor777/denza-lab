# CLAUDE.md

Guidance for working in this repository (humans and AI).

## What this is

A monorepo for reverse-engineering a Denza / BYD head unit and building useful
apps on it. Three concerns are kept separate on purpose:

- **Apps** — Denza Gateway, Denza Mirrors, Denza Apps.
- **Poking the car** — host scripts in `tools/`, on-device probes in
  `dev.denza.mirrors.probe`.
- **Knowledge of what works / doesn't** — `docs/` (durable) and `research/`
  (parked code).

Directory name = product. The repo is still called `denza-gateway` for
historical reasons and will be renamed.

## Read before changing code

- [docs/project-map.md](docs/project-map.md) — structure and per-component status.
- [docs/governance.md](docs/governance.md) — product/prototype/research lanes,
  where experiments live, promotion checklist, live-car debugging rules.
- [docs/side-camera-findings.md](docs/side-camera-findings.md) — Denza Mirrors status.
- [docs/dishare-api-notes.md](docs/dishare-api-notes.md) — DiShare/HUD findings.

## Modules

| Gradle | Path | App id / namespace |
| --- | --- | --- |
| `:denza-gateway` | `denza-gateway/` | `dev.denza.gateway` (Kotlin/Compose) |
| `:denza-mirrors` | `denza-mirrors/` | `dev.denza.mirrors` (product) + `dev.denza.mirrors.probe` (research) |
| `:denza-apps` | `denza-apps/` | `dev.denza.apps`, depends on `:dishare-bridge` |
| `:dishare-bridge` | `dishare-bridge/` | `dev.denza.disharebridge` (library) |

## Build

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :denza-gateway:testDebugUnitTest :denza-gateway:assembleDebug
./gradlew :denza-mirrors:assembleDebug
./gradlew :denza-apps:assembleDebug
```

## Conventions

- Product code must not depend on `…​.probe` code. (One known exception is
  documented in project-map/governance as a cleanup TODO.)
- Product apps share car-access code only via `:dishare-bridge`.
- New "poke the car" code goes to `tools/` (host) or a `…​.probe` package
  (on-device), never into a product package.
- Code, manifests, and Gradle files are the source of truth for current behavior.
  Docs should map the repo and record verified findings, not duplicate the code.
- Record durable findings in the closest existing doc, not only in chat. Create a
  new `.md` only when the topic has a durable owner. Parked code → `research/`.
- Never commit APKs, reverse-engineered APKs, or large extracted binaries
  (`reverse/`, `captures/`, build outputs are git-ignored).
- Treat `com.byd.avc` crashes as a hard stop; capture `logcat -b crash -v time`.
