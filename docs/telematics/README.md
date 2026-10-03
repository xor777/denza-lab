# Telematics findings — companion app, cloud, and vehicle clients

Why the phone app shows the car as offline. Phone access to the cloud and
vehicle telemetry ingestion must be established separately. The head unit
itself runs several cloud clients; ownership of the phone's main status feed
by a separate T-Box has not been established. Open 2026-09-22.

## Pages

- [cloud-tile.md](cloud-tile.md) — the live stock-client activation over Wi-Fi (2026-09-23), the «Облако» tile's contract, builds 55–60, forum reports and registration code 3, the keepalive reboot, the PIN reset and the phone app's language.
- [stock-client-boundaries.md](stock-client-boundaries.md) — what reusing the stock client covers: QuickBoot and Wi-Fi retention, the parked trial, the adapter handoff, cellular retention and incoming commands.
- [cloud-protocol.md](cloud-protocol.md) — the protocol and identity: the 74% SOC upload, registration/discovery/login, factory mutual TLS, `safekeyservice` signing, SOC in message 512, the bootstrap gate and the token.
- [firmware-reading.md](firmware-reading.md) — how the OTA was decoded and `cloudmanager` recovered, the network gate in code, the earlier updater and archive stages, and method notes on the phone app.
- [investigation-2026-09-22.md](investigation-2026-09-22.md) — history: the 2026-09-22 investigation (APN tests, DNS, MikroTik, Dolphin comparison, SIM, ownership, private APN); many of its conclusions were superseded the next day and are marked.
- [other-vehicles.md](other-vehicles.md) — Yangwang U9 / DiLink 6.0 reports, 2026-09-28.
