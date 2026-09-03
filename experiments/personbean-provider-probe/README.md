# PersonBean provider probe

Disposable normal-UID APK for one live question on the tested DiLink 5.1 car:
can an ordinary app read, write and observe the AutoVoice role table
`content://com.byd.autovoice/PersonBean` through `ContentResolver`, without
ADB and in milliseconds, instead of the ~1.2 s a shell `content query` costs
Denza Apps today?

Corpus evidence for the attempt: the `BydAutoVoice` manifest exports the
provider with no read or write permission, and its `query()`/`update()` have no
caller check and call `notifyChange` when a row matched.

The APK has no Activity, Service, launcher icon, permission or dependency. Its
whole surface is one receiver, `ProbeReceiver`, guarded by
`android.permission.DUMP` so only the shell can drive it, with four actions
under `dev.denza.personbean.probe.`:

| Action | Extras | Answer |
| --- | --- | --- |
| `QUERY` | `role` optional | `rows=`, `query_ms=`, `rN=<SETTING>:<VALUE>` |
| `UPDATE` | `role`, `expected`, `value` | `count=`, `update_ms=`, `notified=`, `notify_ms=`, `readback=` |
| `OBSERVE` | none | `registered=true already=<bool>` |
| `REPORT` | none | `events=`, the last five `eN=<uri>@<ms>` |

Answers come back on the ordered-broadcast result channel: code 0 when the
operation ran, 1 when it threw (`error=` and `detail=` then say what), and the
same line is logged under the tag `PersonBeanProbe`. Deciding whether a run
confirms the hypothesis - row counts, matched counts, readbacks - is the host
script's job. `UPDATE` writes only through the conditional predicate
`SETTING=? AND VALUE=?`, so a stale expectation matches nothing rather than
overwriting an unknown value.

`OBSERVE` registers a static observer that lives as long as the process, and
every answer carries a per-process `nonce=`, so a later `REPORT` shows whether
the process that saw the events is still the process answering.

A broadcast alone is not enough to reach the receiver on this firmware. BYD's
self-start gate logs `Self start permission detection` for the target UID, finds
no live process (`UID ... is not running`) and drops the broadcast unseen -
no permission denial, no crash. `ProbeWakeActivity` exists only to answer that:
it is an invisible Activity with no intent-filter that logs one `op=wake` line
and finishes, so the host starts it by explicit component before every batch of
broadcasts and the receiver is then reached normally.

Build, install, live run and restore are owned by
`tools/personbean_provider_probe.sh`; the run is restore-wrapped and the APK is
uninstalled at the end unless `--keep`. The live path is unverified until an
acceptance run succeeds.
