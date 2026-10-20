# Download page

The page lists the latest Denza Apps, Yandex HUD and HUD Speed Mod and links to
their APKs ([CAG-015](../../docs/car-adb-gateway-decision-log.md#cag-015)). It is
served by GitHub Pages at <https://xor777.github.io/denza-lab/>.

- `www/` is the page itself. The app list, names and descriptions live in
  `site.js`. Each app's newest release (version, date, size, SHA-256 and APK
  link) comes from the repository's GitHub releases, found by its tag prefix:
  - `denza-apps-v` for Denza Apps;
  - `yandex-hud-` for Yandex HUD;
  - `hud-speed-` for HUD Speed Mod.

  Drafts and pre-releases are skipped. Colours and the Jura digits follow
  Luminofor.
- `deploy_pages.sh` publishes the page: `www/` and the fonts from
  `tools/design-canvas/luminofor/fonts/` go to the `gh-pages` branch as one
  orphan commit. Run it only when the page changes. main is not pushed.

To publish a new version, create a release with its APK under the right tag. The
page shows it on the next load:

```bash
gh release create denza-apps-v0.7.2 denza-apps-v0.7.2.apk --title "Denza Apps v0.7.2" --notes-file notes.md
gh release create yandex-hud-29.8.1-YandexHUD.3 yandex-hud-29.8.1-YandexHUD.3.apk --latest=false --title "Yandex HUD 29.8.1-YandexHUD.3" --notes-file notes.md
```

Only Denza Apps is marked Latest. A custom domain is a `CNAME` file in `www/`,
added after its DNS record points at `xor777.github.io`.
