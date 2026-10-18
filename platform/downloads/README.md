# Download page

The static page at `https://adbgw.ru` and the script that puts APKs on it
([CAG-014](../../docs/car-adb-gateway-decision-log.md#cag-014)).

- `www/` — the page. The app list and descriptions live in `site.js`; versions,
  sizes and hashes come from `apk/releases.json`. Colours and the Jura digits
  follow Luminofor. `ops/ansible/roles/downloads` installs it with the fonts from
  `tools/design-canvas/luminofor/fonts/`.
- `publish.py` — runs on the controller: reads the APK's package and versions
  with `aapt2`, uploads it under a hidden name and runs `releases.py` on the host.
  Usage is in [ops/ansible/README.md](../../ops/ansible/README.md#download-page).
- `releases.py` — runs on the host (`python3 -`): checks the uploaded bytes,
  moves the APK into place, rewrites `releases.json` atomically under a lock and
  removes the app's previous APK.

Run the tests from the repository root:

```bash
PYTHONPATH=platform python3 -m unittest -v \
  downloads.tests.test_releases \
  downloads.tests.test_publish
```
