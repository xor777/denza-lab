// Denza Lab download page. The list of apps is fixed here; versions, sizes and
// hashes come from apk/releases.json, written by platform/downloads/publish.py.
"use strict";

const APPS = [
  { id: "denza-apps", name: "Denza Apps", what: "Приборы, экран справа, проекция, камеры и звук" },
  { id: "yandex-hud", name: "Yandex HUD", what: "Навигатор с картой и подсказками на проекции" },
  { id: "hud-speed", name: "HUD Speed", what: "Камеры и радары на проекции" },
];

const MONTHS = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля",
  "августа", "сентября", "октября", "ноября", "декабря"];

const ARROW = '<svg viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.6" '
  + 'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 1.5v9M3 7l4 4 4-4M2 12.5h10"/></svg>';

const esc = s => String(s).replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const day = iso => { const [y, m, d] = iso.split("-").map(Number); return `${d} ${MONTHS[m - 1]} ${y}`; };
const size = bytes => (bytes / 1048576).toFixed(1).replace(".", ",") + " МБ";
const shortSha = sha => `SHA-256 ${sha.slice(0, 8)}…${sha.slice(-4)}`;

// "29.8.1-YandexHUD.1" reads as the upstream version and our build on its own line.
function splitVersion(name) {
  const dash = name.indexOf("-");
  return dash < 0 ? [name, ""] : [name.slice(0, dash), name.slice(dash + 1)];
}

function row(app, release) {
  const [version, build] = splitVersion(release.version_name);
  return `
  <li class="app">
    <div class="name">${esc(app.name)}</div>
    <div class="what">${esc(app.what)}</div>
    <div class="meta">
      <span>${size(release.bytes)}</span>
      <span>${day(release.date)}</span>
      <button class="sha" type="button" data-sha="${esc(release.sha256)}" title="Скопировать SHA-256">${shortSha(release.sha256)}</button>
    </div>
    <div class="ver">${esc(version)}</div>
    <div class="build">${esc(build)}</div>
    <a class="get" href="apk/${encodeURIComponent(release.file)}" download="${esc(release.file)}">Скачать APK ${ARROW}</a>
  </li>`;
}

function render(releases) {
  const apps = (releases && releases.apps) || {};
  document.getElementById("apps").innerHTML = APPS
    .filter(app => apps[app.id])
    .map(app => row(app, apps[app.id]))
    .join("");
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

fetch("apk/releases.json", { cache: "no-store" })
  .then(response => (response.ok ? response.json() : null))
  .catch(() => null)
  .then(render);
