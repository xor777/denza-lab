// Denza Lab download page. The list of apps is fixed here; each app's newest
// release - version, size, date, hash and APK - comes from the repository's
// GitHub releases, found by its tag prefix (CAG-015).
"use strict";

const REPO = "xor777/denza-lab";
const RELEASES = `https://github.com/${REPO}/releases`;

const APPS = [
  { tag: "denza-apps-v", name: "Denza Apps", what: "Приборы, экран справа, проекция, камеры и звук" },
  { tag: "yandex-hud-", name: "Yandex HUD", what: "Навигатор с картой и подсказками на проекции" },
  { tag: "hud-speed-", name: "HUD Speed Mod", what: "Камеры и радары на проекции" },
];

const MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
  "августа", "сентября", "октября", "ноября", "декабря"];

const ARROW = '<svg viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.6" '
  + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 1.5v9M3 7l4 4 4-4M2 12.5h10"/></svg>';

const esc = s => String(s).replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const day = iso => { const [y, m, d] = iso.slice(0, 10).split("-").map(Number); return `${d} ${MONTHS[m - 1]} ${y}`; };
const size = bytes => (bytes / 1048576).toFixed(1).replace(".", ",") + " МБ";
const shortSha = sha => `SHA-256 ${sha.slice(0, 8)}…${sha.slice(-4)}`;

// "29.8.1-YandexHUD.2" reads as the upstream version and our build on its own line.
function splitVersion(name) {
  const dash = name.indexOf("-");
  return dash < 0 ? [name, ""] : [name.slice(0, dash), name.slice(dash + 1)];
}

// The newest published release whose tag carries the app's prefix and has an APK.
function latest(releases, app) {
  for (const release of releases) {
    if (release.draft || release.prerelease || !release.tag_name.startsWith(app.tag)) continue;
    const apk = release.assets.find(asset => asset.name.endsWith(".apk"));
    if (apk) return { release, apk, version: release.tag_name.slice(app.tag.length) };
  }
  return null;
}

function row(app, found) {
  const [version, build] = splitVersion(found.version);
  const digest = found.apk.digest || "";
  const sha = digest.startsWith("sha256:") ? digest.slice(7) : "";
  return `
  <li class="app">
    <a class="name" href="${esc(found.release.html_url)}">${esc(app.name)}</a>
    <div class="what">${esc(app.what)}</div>
    <div class="meta">
      <span>${size(found.apk.size)}</span>
      <span>${day(found.release.published_at)}</span>
      ${sha ? `<button class="sha" type="button" data-sha="${esc(sha)}" title="Скопировать SHA-256">${shortSha(sha)}</button>` : ""}
    </div>
    <div class="ver">${esc(version)}</div>
    <div class="build">${esc(build)}</div>
    <a class="get" href="${esc(found.apk.browser_download_url)}">Скачать APK ${ARROW}</a>
  </li>`;
}

// Without the list (the API's hourly limit, no network) the page still leads to every APK.
function fallback() {
  return `
  <li class="app">
    <a class="name" href="${RELEASES}">Все версии</a>
    <div class="what">Релизы на GitHub: Denza Apps, Yandex HUD и HUD Speed Mod</div>
    <a class="get" href="${RELEASES}">Открыть ${ARROW}</a>
  </li>`;
}

function render(releases) {
  const rows = Array.isArray(releases)
    ? APPS.map(app => [app, latest(releases, app)]).filter(([, found]) => found).map(([app, found]) => row(app, found))
    : [];
  document.getElementById("apps").innerHTML = rows.length ? rows.join("") : fallback();
}

document.addEventListener("click", event => {
  const button = event.target.closest(".sha");
  if (!button || !navigator.clipboard) return;
  navigator.clipboard.writeText(button.dataset.sha).then(() => {
    const label = button.textContent;
    button.textContent = "SHA-256 скопирован";
    button.classList.add("copied");
    setTimeout(() => { button.textContent = label; button.classList.remove("copied"); }, 1400);
  });
});

document.getElementById("host").textContent = location.hostname.toUpperCase();

fetch(`https://api.github.com/repos/${REPO}/releases?per_page=100`, { headers: { Accept: "application/vnd.github+json" } })
  .then(response => (response.ok ? response.json() : null))
  .catch(() => null)
  .then(render);
