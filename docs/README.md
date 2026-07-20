# Docs Index

Use this folder for durable project knowledge.

| File | Use for |
| --- | --- |
| `project-map.md` | Repo structure, app boundaries, build outputs, product direction. |
| `governance.md` | Rules for product/prototype/research changes and promotion. |
| `instrument-display-findings.md` | Instrument-display selection, Mirrors geometry, navigation projection, verification status, and open issues. |
| `dishare-api-notes.md` | DiShare/HUD reverse-engineering notes and raw API findings. |
| `fse-app-installation.md` | Passenger-screen Android discovery, SMB delivery, stock cross-device install trigger, verification, and limitations. |
| `CLOUD-ARCHITECTURE.md` | Normative relay-only Car ADB Gateway design and verification status. |
| `CAR-ADB-GATEWAY-DECISIONS.md` | ADR-lite product/architecture decisions, rationale, evidence, and revisit conditions. |

If a new investigation creates reusable knowledge, update the closest existing
file first. Add a new doc only when the topic has a durable owner and would make
an existing file hard to scan. Current behavior should still be read from code,
manifests, and build files; docs are the map and evidence log, not a second
source of truth.
